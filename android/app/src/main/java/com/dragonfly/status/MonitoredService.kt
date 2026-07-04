package com.dragonfly.status

/** Which part of the world a service belongs to (used to group the status screen). */
enum class ServiceGroup(val label: String) {
    SUITE("Suite"),
    MEDIA("Media"),
    AUTOMATION("Automation"),
}

/**
 * How a service reports health.
 *  - SUITE: the app backends expose `GET /health` ({"status":"ok"}) + `GET /version`
 *    ({name, version, commit, built_at}); we get up/down plus version and last-deploy.
 *  - REACHABILITY: everything else (media stack behind Caddy, the Home Assistant frontend) only
 *    answers a plain GET — up if any non-gateway HTTP response comes back (Caddy basic_auth 401
 *    counts as healthy), no version.
 */
enum class ProbeType { SUITE, REACHABILITY }

/**
 * Where a service can be reached from. A PUBLIC service is expected to answer anywhere; a
 * TAILNET_ONLY service only answers when the phone is on the tailnet, so a connection failure is
 * "off-network", not an outage.
 */
enum class Reachability { PUBLIC, TAILNET_ONLY }

/** One backend the hub watches. Kept separate from the app registry: services ≠ managed apps. */
data class MonitoredService(
    val key: String,
    val displayName: String,
    val group: ServiceGroup,
    val baseUrl: String,
    val probe: ProbeType,
    val reachability: Reachability,
    /**
     * App-registry key whose broker-configured `server_base_url` overrides [baseUrl] when set;
     * null for services the broker doesn't manage (media, automation, the identity server).
     */
    val overrideKey: String? = null,
) {
    val healthUrl: String
        get() = if (probe == ProbeType.SUITE) baseUrl.trimEnd('/') + "/health" else baseUrl
    val versionUrl: String
        get() = baseUrl.trimEnd('/') + "/version"
}
