package com.dragonfly.update

import com.dragonfly.net.GitHubApi
import com.dragonfly.net.HttpFetcher
import com.dragonfly.net.downloadUrl
import com.dragonfly.registry.AppRegistry
import com.dragonfly.registry.ManagedApp
import com.dragonfly.settings.PatStore
import com.dragonfly.settings.SettingsRepository
import com.dragonfly.settings.SettingsSnapshot
import com.dragonfly.settings.UpdateSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resolves "what's the newest version of each app?" across the two backends.
 *
 * Self-host is fetched once per refresh (one manifest covers every app); each GitHub app is an
 * independent release lookup. A self-host failure falls back to GitHub when the app has a repo,
 * per CLAUDE.md's graceful-degradation constraint.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubApi: GitHubApi,
    private val fetcher: HttpFetcher,
    private val settingsRepository: SettingsRepository,
    private val installedApps: InstalledAppsDataSource,
    private val patStore: PatStore,
) {
    suspend fun checkAll(): List<AppStatus> {
        val settings = settingsRepository.snapshot()
        val manifest = fetchManifestIfNeeded(settings)
        return coroutineScope {
            AppRegistry.apps.map { app -> async { check(app, settings, manifest) } }.awaitAll()
        }
    }

    suspend fun check(app: ManagedApp): AppStatus {
        val settings = settingsRepository.snapshot()
        return check(app, settings, fetchManifestIfNeeded(settings, onlyFor = app))
    }

    /** null = no app wanted the self-host source (or it isn't configured). */
    private suspend fun fetchManifestIfNeeded(
        settings: SettingsSnapshot,
        onlyFor: ManagedApp? = null,
    ): Result<Map<String, ManifestEntry>>? {
        val apps = onlyFor?.let { listOf(it) } ?: AppRegistry.apps
        val wanted = apps.any { settings.sourceFor(it.key) == UpdateSource.SELF_HOST }
        if (!wanted) return null
        if (!settings.selfHostConfigured) {
            return Result.failure(IllegalStateException("self-host URL not configured"))
        }
        return runCatching { ReleaseResolver.parseManifest(fetcher.fetchString(settings.manifestUrl)) }
    }

    private suspend fun check(
        app: ManagedApp,
        settings: SettingsSnapshot,
        manifest: Result<Map<String, ManifestEntry>>?,
    ): AppStatus {
        val installed = installedApps.installedVersion(app.packageName)
        var note: String? = null

        val latest: Result<LatestRelease> = when (settings.sourceFor(app.key)) {
            UpdateSource.SELF_HOST -> {
                val fromManifest = manifest?.mapCatching { entries ->
                    entries[app.key]?.toLatest()
                        ?: throw NoSuchElementException("no \"${app.key}\" entry in manifest")
                } ?: Result.failure(IllegalStateException("manifest not fetched"))
                if (fromManifest.isFailure && app.githubRepo != null) {
                    note = "Self-host unavailable — checked GitHub instead"
                    fetchFromGitHub(app.githubRepo)
                } else {
                    fromManifest
                }
            }
            UpdateSource.GITHUB ->
                if (app.githubRepo != null) {
                    fetchFromGitHub(app.githubRepo)
                } else {
                    Result.failure(IllegalStateException("${app.displayName} has no GitHub repo"))
                }
        }

        if (latest.isSuccess && latest.getOrThrow().sha256 == null) {
            note = "Release has no SHA-256 — integrity can't be verified"
        }

        return AppStatus(
            app = app,
            installedVersionCode = installed?.versionCode,
            installedVersionName = installed?.versionName,
            latest = latest.getOrNull(),
            state = ReleaseResolver.stateFor(installed?.versionCode, latest.getOrNull()),
            note = latest.exceptionOrNull()?.let { it.message ?: "check failed" } ?: note,
        )
    }

    private suspend fun fetchFromGitHub(repo: String): Result<LatestRelease> = runCatching {
        val (owner, name) = repo.split('/', limit = 2)
        val release = gitHubApi.latestRelease(owner, name)
        val apk = ReleaseResolver.pickApkAsset(release.assets)
            ?: throw NoSuchElementException("release ${release.tagName} has no .apk asset")
        val versionAsset = ReleaseResolver.pickVersionJsonAsset(release.assets)
            ?: throw NoSuchElementException(
                "release ${release.tagName} has no version.json asset — fix the release workflow"
            )
        val usePat = patStore.githubPat != null
        val versionJson = ReleaseResolver.parseVersionJson(
            fetcher.fetchString(versionAsset.downloadUrl(repo, usePat), accept = "application/octet-stream")
        )
        LatestRelease(
            versionCode = versionJson.versionCode,
            versionName = versionJson.versionName,
            apkUrl = apk.downloadUrl(repo, usePat),
            sha256 = versionJson.sha256,
            changelog = release.body?.takeIf { it.isNotBlank() },
            source = UpdateSource.GITHUB,
            usesGitHubApiDownload = usePat,
        )
    }

    /**
     * The rolled-up "what changed since your installed version" notes for a GitHub app that is
     * several releases behind. Fetches one page of releases and rolls up every release newer than
     * the installed one (see [ReleaseResolver.notesSinceInstalled]). Best-effort — returns null on
     * any failure or for a self-host-only app, so the UI just falls back to the latest notes.
     */
    suspend fun changesSinceInstalled(app: ManagedApp, installedVersionName: String?): String? {
        val repo = app.githubRepo ?: return null
        return runCatching {
            val (owner, name) = repo.split('/', limit = 2)
            ReleaseResolver.notesSinceInstalled(gitHubApi.releases(owner, name), installedVersionName)
        }.getOrNull()
    }
}

private fun ManifestEntry.toLatest() = LatestRelease(
    versionCode = versionCode,
    versionName = versionName,
    apkUrl = apkUrl,
    sha256 = sha256,
    source = UpdateSource.SELF_HOST,
)
