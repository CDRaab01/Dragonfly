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
) {
    /** Effective source for one app: per-app override, else the global default. */
    fun sourceFor(appKey: String): UpdateSource = perAppSource[appKey] ?: globalSource

    val selfHostConfigured: Boolean get() = selfHostBaseUrl.isNotBlank()

    /** `<base>/manifest.json`, tolerant of a trailing slash in the stored URL. */
    val manifestUrl: String get() = selfHostBaseUrl.trimEnd('/') + "/manifest.json"
}
