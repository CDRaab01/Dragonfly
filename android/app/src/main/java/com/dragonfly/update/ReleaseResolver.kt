package com.dragonfly.update

import com.dragonfly.net.GitHubAsset
import kotlinx.serialization.json.Json

/**
 * The pure half of the update pipeline — JSON parsing, asset selection, and the version diff —
 * kept free of Android/OkHttp so it's trivially unit-testable.
 */
object ReleaseResolver {
    val json = Json { ignoreUnknownKeys = true }

    fun parseVersionJson(text: String): VersionJson = json.decodeFromString(text)

    fun parseManifest(text: String): Map<String, ManifestEntry> = json.decodeFromString(text)

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
}
