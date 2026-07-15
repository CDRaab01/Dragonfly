package com.dragonfly.update

import com.dragonfly.net.GitHubAsset
import com.dragonfly.net.GitHubRelease
import kotlinx.serialization.json.Json

/**
 * The pure half of the update pipeline — JSON parsing, asset selection, and the version diff —
 * kept free of Android/OkHttp so it's trivially unit-testable.
 */
object ReleaseResolver {
    val json = Json { ignoreUnknownKeys = true }

    fun parseVersionJson(text: String): VersionJson = json.decodeFromString(text)

    /** The installable APK asset: exactly one is expected; prefer the first `.apk` by name. */
    fun pickApkAsset(assets: List<GitHubAsset>): GitHubAsset? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    fun pickVersionJsonAsset(assets: List<GitHubAsset>): GitHubAsset? =
        assets.firstOrNull { it.name.equals("version.json", ignoreCase = true) }

    /**
     * versionCode is the source of truth (CLAUDE.md). A missing installed code means the app
     * isn't installed — that's NOT_INSTALLED, not an update.
     */
    fun stateFor(installedCode: Long?, latest: LatestRelease?): AppState = when {
        latest == null -> AppState.ERROR
        installedCode == null -> AppState.NOT_INSTALLED
        latest.versionCode > installedCode -> AppState.UPDATE_AVAILABLE
        else -> AppState.UP_TO_DATE
    }

    /**
     * Roll up the release notes for every release newer than the installed build — the "changed
     * since installed" view for an app several versions behind, so the changelog isn't just the
     * single latest release. GitHub returns releases newest-first; the installed build is located
     * by matching its `versionName` to a release tag (`v<versionName>`, per the suite's release
     * automation) and everything above it is newer.
     *
     * Returns null when there's nothing to show. When the installed version isn't among the fetched
     * releases (e.g. a local build, or it's older than the page), falls back to the latest release's
     * notes alone rather than mislabeling unrelated history.
     */
    fun notesSinceInstalled(releases: List<GitHubRelease>, installedVersionName: String?): String? {
        if (releases.isEmpty()) return null
        val installedIdx = installedVersionName
            ?.let { name -> releases.indexOfFirst { it.tagName.removePrefix("v") == name } }
            ?: -1
        val newer = when {
            installedIdx == 0 -> emptyList()               // already on the latest release
            installedIdx > 0 -> releases.subList(0, installedIdx)
            else -> listOf(releases.first())               // installed unknown → latest notes only
        }
        return newer
            .mapNotNull { release ->
                val body = release.body?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val header = release.name?.takeIf { it.isNotBlank() } ?: release.tagName
                "$header\n$body"
            }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }
}
