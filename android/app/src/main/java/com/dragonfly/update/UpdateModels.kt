package com.dragonfly.update

import com.dragonfly.registry.ManagedApp
import com.dragonfly.settings.UpdateSource
import kotlinx.serialization.Serializable

/**
 * The `version.json` asset every sibling's release CI uploads next to the APK. Required because
 * the GitHub API only exposes the semver tag — versionCode (the actual source of truth) has to
 * be published explicitly.
 */
@Serializable
data class VersionJson(
    val versionCode: Long,
    val versionName: String,
    val sha256: String? = null,
    val minSdk: Int? = null,
)

/** One app's entry in the self-hosted manifest.json. */
@Serializable
data class ManifestEntry(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int? = null,
)

/** The newest release one source knows about, normalized across backends. */
data class LatestRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,          // null only for a GitHub release missing the hash — warn
    val changelog: String? = null,
    val source: UpdateSource,
    /** When downloading via the GitHub API asset endpoint (private repo + PAT). */
    val usesGitHubApiDownload: Boolean = false,
)

enum class AppState { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE, ERROR }

/** Everything the UI needs to render one app card. */
data class AppStatus(
    val app: ManagedApp,
    val installedVersionCode: Long?,
    val installedVersionName: String?,
    val latest: LatestRelease?,
    val state: AppState,
    /** Human-readable problem/context line ("self-host unreachable — fell back to GitHub"). */
    val note: String? = null,
)
