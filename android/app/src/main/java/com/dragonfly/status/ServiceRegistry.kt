package com.dragonfly.status

/**
 * The services the hub's status dashboard watches — the single source of truth for "is my world
 * green". Distinct from `registry/AppRegistry` (installable apps): a service is a backend, and the
 * mapping isn't 1:1 (the `dragonfly` app's backend is the identity server at id.*, Hawksnest has no
 * public backend, and the media stack has no app at all).
 *
 * Suite backends expose uniform /health + /version. The media stack is public via
 * Cloudflare → Caddy where a basic_auth 401 means healthy (OPERATIONS.md §3), so a plain
 * reachability GET suffices. Hawksnest (k3s NodePort) is tailnet-only.
 */
object ServiceRegistry {

    val services: List<MonitoredService> = listOf(
        suite("spotter", "Spotter", "https://spotter.dragonflymedia.org"),
        suite("plate", "Plate", "https://plate.dragonflymedia.org"),
        suite("cookbook", "Cookbook", "https://cookbook.dragonflymedia.org"),
        // The identity server is the `dragonfly` app's backend; hostname is id.*, not dragonfly.*.
        suite("dragonfly-id", "Dragonfly ID", "https://id.dragonflymedia.org"),
        // Magpie is deliberately tailnet-only (no public hostname, CLAUDE.md §0/§8 — it holds
        // financial data) — a SUITE probe (real /health + /version), but TAILNET_ONLY
        // reachability, so it degrades to "off-network" rather than a false "down" from a
        // network that can't reach it, same treatment as Hawksnest below.
        MonitoredService(
            key = "magpie",
            displayName = "Magpie",
            group = ServiceGroup.SUITE,
            baseUrl = "https://dragonfly.tail2ce561.ts.net",
            probe = ProbeType.SUITE,
            reachability = Reachability.TAILNET_ONLY,
            overrideKey = "magpie",
        ),

        media("plex", "Plex", "https://plex.dragonflymedia.org"),
        media("radarr", "Radarr", "https://radarr.dragonflymedia.org"),
        media("sonarr", "Sonarr", "https://sonarr.dragonflymedia.org"),
        media("sabnzbd", "SABnzbd", "https://sabnzbd.dragonflymedia.org"),
        media("overseerr", "Overseerr", "https://overseerr.dragonflymedia.org"),
        media("agregarr", "Agregarr", "https://agregarr.dragonflymedia.org"),

        // Home Assistant frontend on k3s (NodePort 30080) — tailnet-only. The exact
        // tailnet-reachable URL is unconfirmed (k3s runs in WSL; exposure is still open in
        // hawksnest-automation), so this degrades to "off-network" until verified on the phone —
        // a neutral state, never a false outage.
        MonitoredService(
            key = "hawksnest",
            displayName = "Hawksnest",
            group = ServiceGroup.AUTOMATION,
            baseUrl = "http://dragonfly.tail2ce561.ts.net:30080",
            probe = ProbeType.REACHABILITY,
            reachability = Reachability.TAILNET_ONLY,
        ),
    )

    private fun suite(key: String, name: String, url: String) = MonitoredService(
        key = key,
        displayName = name,
        group = ServiceGroup.SUITE,
        baseUrl = url,
        probe = ProbeType.SUITE,
        reachability = Reachability.PUBLIC,
        // dragonfly-id has no app-registry override key; the three app backends do.
        overrideKey = key.takeIf { it != "dragonfly-id" },
    )

    private fun media(key: String, name: String, url: String) = MonitoredService(
        key = key,
        displayName = name,
        group = ServiceGroup.MEDIA,
        baseUrl = url,
        probe = ProbeType.REACHABILITY,
        reachability = Reachability.PUBLIC,
    )
}
