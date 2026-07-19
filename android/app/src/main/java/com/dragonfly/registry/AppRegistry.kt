package com.dragonfly.registry

/**
 * One app the hub manages. The registry is the single source of truth for what Dragonfly
 * launches, version-checks, and updates — including Dragonfly itself (self-update path).
 */
data class ManagedApp(
    val key: String,          // stable id (also the broker config key)
    val displayName: String,
    val packageName: String,
    val githubRepo: String?,  // "owner/repo"; null ⇒ not updatable from GitHub Releases
    val isSelf: Boolean = false,
)

object AppRegistry {
    const val SELF_KEY = "dragonfly"

    val apps = listOf(
        ManagedApp("spotter", "Spotter", "com.spotter", "CDRaab01/Spotter"),
        ManagedApp("plate", "Plate", "com.plate", "CDRaab01/Plate"),
        ManagedApp("cookbook", "Cookbook", "com.cookbook", "CDRaab01/Cookbook"),
        ManagedApp("hawksnest", "Hawksnest", "com.hawksnest", "CDRaab01/Hawksnest"),
        ManagedApp("magpie", "Magpie", "com.magpie", "CDRaab01/Magpie"),
        ManagedApp("remnant", "Remnant", "com.remnant", "CDRaab01/Remnant"),
        ManagedApp(SELF_KEY, "Dragonfly", "com.dragonfly", "CDRaab01/Dragonfly", isSelf = true),
    )

    fun byKey(key: String): ManagedApp? = apps.firstOrNull { it.key == key }
}
