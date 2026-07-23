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
        // Remnant: tailnet-only like Magpie (personal notes incl. a MEDICAL category), served on
        // HTTPS :8445 — Magpie owns 443 and Hawksnest owns :8443 on the host, so Remnant took the
        // next Serve port (moved off :8443 on 2026-07-22; see OPERATIONS.md §6 / Remnant deploy/README.md).
        MonitoredService(
            key = "remnant",
            displayName = "Remnant",
            group = ServiceGroup.SUITE,
            baseUrl = "https://dragonfly.tail2ce561.ts.net:8445",
            probe = ProbeType.SUITE,
            reachability = Reachability.TAILNET_ONLY,
            overrideKey = "remnant",
        ),

        media("plex", "Plex", "https://plex.dragonflymedia.org"),
        media("radarr", "Radarr", "https://radarr.dragonflymedia.org"),
        media("sonarr", "Sonarr", "https://sonarr.dragonflymedia.org"),
        media("sabnzbd", "SABnzbd", "https://sabnzbd.dragonflymedia.org"),
        media("overseerr", "Overseerr", "https://overseerr.dragonflymedia.org"),
        media("agregarr", "Agregarr", "https://agregarr.dragonflymedia.org"),

        // Home Assistant frontend (nginx pod, k3s NodePort 30080) — tailnet-only. Reached over the
        // tailnet via Tailscale Serve :8443 → the Hawksnest nginx pod (host socat 8090 → NodePort
        // 30080), NOT the raw :30080 (that only exists inside WSL, unreachable from the phone).
        // Confirmed 2026-07-22 (OPERATIONS.md §1.2/§6). REACHABILITY probe: any non-gateway response = up.
        MonitoredService(
            key = "hawksnest",
            displayName = "Hawksnest",
            group = ServiceGroup.AUTOMATION,
            baseUrl = "https://dragonfly.tail2ce561.ts.net:8443",
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
