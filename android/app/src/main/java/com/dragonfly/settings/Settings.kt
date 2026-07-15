package com.dragonfly.settings

/** How often the background worker checks for updates. Manual/on-launch schedule no work. */
enum class CheckInterval { MANUAL, ON_LAUNCH, DAILY }

/** A point-in-time read of every setting the update pipeline needs. */
data class SettingsSnapshot(
    val checkInterval: CheckInterval = CheckInterval.ON_LAUNCH,
    val wifiOnly: Boolean = false,
    /**
     * Per-app server base URL the suite broker hands to siblings (BROKER.md Phase 1). Empty/absent
     * ⇒ the broker has no opinion and the sibling keeps its own configured URL. This is the config
     * broker's server URL — unrelated to where the hub fetches update APKs (always GitHub Releases).
     */
    val perAppServerUrl: Map<String, String> = emptyMap(),
) {
    /** Broker-managed server URL for an app, or null if unset (sibling falls back to its own). */
    fun serverUrlFor(appKey: String): String? = perAppServerUrl[appKey]?.takeIf { it.isNotBlank() }
}
