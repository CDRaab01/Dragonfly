package com.dragonfly.status

import com.dragonfly.registry.AppRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The dashboard's claim is "is my world green". A backend it does not know about is a backend
 * that can be down while the banner says all systems go — which is worse than having no banner,
 * because it is trusted.
 *
 * Crate went live 2026-08-14 and was missing from here until 2026-08-16; Tote went live
 * 2026-08-15 and was deferred on purpose until its ts.net URL was real (the Hawksnest
 * URL-guess lesson). Nothing caught either omission, so this test exists.
 */
class ServiceRegistryTest {

    private val services = ServiceRegistry.services

    @Test
    fun `every suite app with a backend is watched`() {
        // Derived from AppRegistry rather than hardcoded, so adding an app to the hub without
        // adding its backend here fails HERE, at the moment the omission is made.
        val watched = services.map { it.key }.toSet()
        val expected = AppRegistry.apps.map { it.key }.toSet() - EXEMPT

        val missing = expected - watched
        assertTrue(
            missing.isEmpty(),
            "these apps are managed by the hub but their backends are unmonitored: $missing",
        )
    }

    @Test
    fun `tailnet-only services are marked as such, not as public`() {
        // A tailnet-only service probed as PUBLIC reports "down" from any network that cannot
        // reach the tailnet — a false alarm that trains someone to ignore the dashboard. The
        // correct degradation is "off-network".
        val tailnetOnly = setOf("magpie", "remnant", "crate", "tote", "hawksnest")
        services.filter { it.key in tailnetOnly }.forEach {
            assertEquals(
                Reachability.TAILNET_ONLY,
                it.reachability,
                "${it.key} is tailnet-only but is probed as if it were public",
            )
        }
    }

    @Test
    fun `no two services claim the same URL`() {
        // Tailscale Serve ports are a shared namespace and `--https=<port>` silently overwrites
        // an existing mapping — that is how Remnant took Home Assistant down for an hour. Two
        // rows pointing at one port here is the same collision, written down.
        val urls = services.map { it.baseUrl }
        assertEquals(urls.size, urls.toSet().size, "two services share a base URL: $urls")
    }

    private companion object {
        /**
         * Apps with no backend of their own to watch.
         *
         * `dragonfly` is the hub itself and its backend is the identity server, listed under its
         * own key. `hawksnest` targets Home Assistant and has no suite backend — it is watched,
         * but as an AUTOMATION reachability probe rather than a suite one.
         */
        val EXEMPT = setOf("dragonfly", "hawksnest")
    }
}
