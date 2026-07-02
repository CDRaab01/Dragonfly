package com.dragonfly.settings

/** Where an app's updates come from. */
enum class UpdateSource { GITHUB, SELF_HOST }

/** How often the background worker checks for updates. Manual/on-launch schedule no work. */
enum class CheckInterval { MANUAL, ON_LAUNCH, DAILY }

/** A point-in-time read of every setting the update pipeline needs. */
data class SettingsSnapshot(
    val globalSource: UpdateSource = UpdateSource.GITHUB,
    val perAppSource: Map<String, UpdateSource> = emptyMap(),
    val selfHostBaseUrl: String = "",
    val checkInterval: CheckInterval = CheckInterval.ON_LAUNCH,
    val wifiOnly: Boolean = false,
    /**
     * Per-app server base URL the suite broker hands to siblings (BROKER.md Phase 1). Empty/absent
     * ⇒ the broker has no opinion and the sibling keeps its own configured URL.
     */
    val perAppServerUrl: Map<String, String> = emptyMap(),
) {
    /** Effective source for one app: per-app override, else the global default. */
    fun sourceFor(appKey: String): UpdateSource = perAppSource[appKey] ?: globalSource

    /** Broker-managed server URL for an app, or null if unset (sibling falls back to its own). */
    fun serverUrlFor(appKey: String): String? = perAppServerUrl[appKey]?.takeIf { it.isNotBlank() }

    val selfHostConfigured: Boolean get() = selfHostBaseUrl.isNotBlank()

    /** `<base>/manifest.json`, tolerant of a trailing slash in the stored URL. */
    val manifestUrl: String get() = selfHostBaseUrl.trimEnd('/') + "/manifest.json"
}
