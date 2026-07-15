package com.dragonfly.update

import com.dragonfly.net.GitHubApi
import com.dragonfly.net.HttpFetcher
import com.dragonfly.net.downloadUrl
import com.dragonfly.registry.AppRegistry
import com.dragonfly.registry.ManagedApp
import com.dragonfly.settings.PatStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resolves "what's the newest version of each app?" from GitHub Releases. Each app is an
 * independent release lookup — versionCode (the source of truth) comes from the release's
 * `version.json` asset, not the tag.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubApi: GitHubApi,
    private val fetcher: HttpFetcher,
    private val installedApps: InstalledAppsDataSource,
    private val patStore: PatStore,
) {
    suspend fun checkAll(): List<AppStatus> = coroutineScope {
        AppRegistry.apps.map { app -> async { check(app) } }.awaitAll()
    }

    suspend fun check(app: ManagedApp): AppStatus {
        val installed = installedApps.installedVersion(app.packageName)
        val latest: Result<LatestRelease> =
            if (app.githubRepo != null) {
                fetchFromGitHub(app.githubRepo)
            } else {
                Result.failure(IllegalStateException("${app.displayName} has no GitHub repo"))
            }

        val note = when {
            latest.isSuccess && latest.getOrThrow().sha256 == null ->
                "Release has no SHA-256 — integrity can't be verified"
            else -> latest.exceptionOrNull()?.let { it.message ?: "check failed" }
        }

        return AppStatus(
            app = app,
            installedVersionCode = installed?.versionCode,
            installedVersionName = installed?.versionName,
            latest = latest.getOrNull(),
            state = ReleaseResolver.stateFor(installed?.versionCode, latest.getOrNull()),
            note = note,
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
            usesGitHubApiDownload = usePat,
        )
    }

    /**
     * The rolled-up "what changed since your installed version" notes for an app that is several
     * releases behind. Fetches one page of releases and rolls up every release newer than the
     * installed one (see [ReleaseResolver.notesSinceInstalled]). Best-effort — returns null on any
     * failure, so the UI just falls back to the latest release's notes.
     */
    suspend fun changesSinceInstalled(app: ManagedApp, installedVersionName: String?): String? {
        val repo = app.githubRepo ?: return null
        return runCatching {
            val (owner, name) = repo.split('/', limit = 2)
            ReleaseResolver.notesSinceInstalled(gitHubApi.releases(owner, name), installedVersionName)
        }.getOrNull()
    }
}
