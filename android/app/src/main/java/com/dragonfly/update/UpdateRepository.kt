package com.dragonfly.update

import com.dragonfly.net.ReleaseApiProvider
import com.dragonfly.net.HttpFetcher
import com.dragonfly.net.ReleaseSource
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
 * Resolves "what's the newest version of each app?" from the release server (GitHub Releases, or
 * the self-hosted Forgejo once Settings → Release server is set; same API shape). Each app is an
 * independent release lookup — versionCode (the source of truth) comes from the release's
 * `version.json` asset, not the tag.
 *
 * Migration: with a Forgejo set, a repo Forgejo doesn't have (404) is looked up on GitHub instead,
 * so apps move over one repo at a time. A repo imported but not yet cut over can show a stale
 * latest release there — the hub only offers higher versionCodes, so that delays updates, never
 * downgrades.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val releaseApis: ReleaseApiProvider,
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

    private suspend fun fetchFromGitHub(repo: String): Result<LatestRelease> {
        val source = patStore.releaseSource
        val first = fetchRelease(repo, source)
        return if (source != ReleaseSource.GitHub && ReleaseSource.shouldFallBackToGitHub(first.exceptionOrNull())) fetchRelease(repo, ReleaseSource.GitHub) else first
    }

    private suspend fun fetchRelease(repo: String, source: ReleaseSource): Result<LatestRelease> = runCatching {
        val (owner, name) = repo.split('/', limit = 2)
        val release = releaseApis.api(source).latestRelease(owner, name)
        val apk = ReleaseResolver.pickApkAsset(release.assets)
            ?: throw NoSuchElementException("release ${release.tagName} has no .apk asset")
        val versionAsset = ReleaseResolver.pickVersionJsonAsset(release.assets)
            ?: throw NoSuchElementException(
                "release ${release.tagName} has no version.json asset — fix the release workflow"
            )
        val usePat = patStore.githubPat != null
        val versionJson = ReleaseResolver.parseVersionJson(
            fetcher.fetchString(versionAsset.downloadUrl(repo, usePat, source), accept = "application/octet-stream")
        )
        LatestRelease(
            versionCode = versionJson.versionCode,
            versionName = versionJson.versionName,
            apkUrl = apk.downloadUrl(repo, usePat, source),
            sha256 = versionJson.sha256,
            changelog = release.body?.takeIf { it.isNotBlank() },
            usesGitHubApiDownload = usePat && source == ReleaseSource.GitHub,
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
            val source = patStore.releaseSource
            val releases = runCatching { releaseApis.api(source).releases(owner, name) }
                .recoverCatching { e ->
                    if (source != ReleaseSource.GitHub && ReleaseSource.shouldFallBackToGitHub(e)) {
                        releaseApis.api(ReleaseSource.GitHub).releases(owner, name)
                    } else throw e
                }.getOrThrow()
            ReleaseResolver.notesSinceInstalled(releases, installedVersionName)
        }.getOrNull()
    }
}

