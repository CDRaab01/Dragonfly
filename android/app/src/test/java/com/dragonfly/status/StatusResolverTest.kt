package com.dragonfly.status

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusResolverTest {

    private fun http(code: Int, body: String? = null) = ProbeOutcome.Http(code, body)

    // --- reachability classification ---

    @Test
    fun `reachability is up for any non-gateway response including caddy 401`() {
        for (code in listOf(200, 204, 301, 302, 401, 403, 404)) {
            assertEquals(ServiceState.UP, StatusResolver.classifyReachability(http(code), Reachability.PUBLIC), "code $code")
        }
    }

    @Test
    fun `reachability is down on gateway errors`() {
        for (code in listOf(502, 503, 504, 530)) {
            assertEquals(ServiceState.DOWN, StatusResolver.classifyReachability(http(code), Reachability.PUBLIC), "code $code")
        }
    }

    @Test
    fun `connect failure is down when public, off-network when tailnet-only`() {
        assertEquals(ServiceState.DOWN, StatusResolver.classifyReachability(ProbeOutcome.ConnectFailed, Reachability.PUBLIC))
        assertEquals(ServiceState.OFF_NETWORK, StatusResolver.classifyReachability(ProbeOutcome.ConnectFailed, Reachability.TAILNET_ONLY))
    }

    // --- suite /health classification ---

    @Test
    fun `suite health up only on 200 status-ok`() {
        assertEquals(ServiceState.UP, StatusResolver.classifySuiteHealth(http(200, """{"status":"ok"}"""), Reachability.PUBLIC))
    }

    @Test
    fun `suite health down on wrong body or non-200`() {
        assertEquals(ServiceState.DOWN, StatusResolver.classifySuiteHealth(http(200, """{"status":"degraded"}"""), Reachability.PUBLIC))
        assertEquals(ServiceState.DOWN, StatusResolver.classifySuiteHealth(http(200, "not json"), Reachability.PUBLIC))
        assertEquals(ServiceState.DOWN, StatusResolver.classifySuiteHealth(http(500), Reachability.PUBLIC))
        assertEquals(ServiceState.DOWN, StatusResolver.classifySuiteHealth(http(401), Reachability.PUBLIC))
    }

    // --- /version parsing ---

    @Test
    fun `parses full version json`() {
        val v = StatusResolver.parseVersion("""{"name":"Spotter API","version":"1.1.2","commit":"f48b3a2","built_at":"2026-07-04T20:58:58Z"}""")
        assertEquals("1.1.2", v?.version)
        assertEquals("f48b3a2", v?.commit)
        assertEquals(Instant.parse("2026-07-04T20:58:58Z"), v?.deployedAt)
    }

    @Test
    fun `version missing version field is null, bad built_at leaves deployedAt null`() {
        assertNull(StatusResolver.parseVersion("""{"name":"x"}"""))
        val v = StatusResolver.parseVersion("""{"version":"0.1.0","commit":"","built_at":"nope"}""")
        assertEquals("0.1.0", v?.version)
        assertNull(v?.commit)      // blank commit → null
        assertNull(v?.deployedAt)  // unparseable timestamp → null, but version still resolves
    }

    // --- relative time ---

    @Test
    fun `relative time buckets`() {
        val now = Instant.parse("2026-07-04T12:00:00Z")
        assertEquals("just now", StatusResolver.relativeTime(now.minusSeconds(10), now))
        assertEquals("5m ago", StatusResolver.relativeTime(now.minusSeconds(5 * 60), now))
        assertEquals("3h ago", StatusResolver.relativeTime(now.minusSeconds(3 * 3600), now))
        assertEquals("2d ago", StatusResolver.relativeTime(now.minusSeconds(2 * 86_400), now))
        assertEquals("just now", StatusResolver.relativeTime(now.plusSeconds(30), now)) // future clamps
    }

    // --- aggregate ---

    private fun status(state: ServiceState) = ServiceStatus(
        service = MonitoredService("k", "K", ServiceGroup.SUITE, "https://x", ProbeType.SUITE, Reachability.PUBLIC),
        state = state,
    )

    @Test
    fun `aggregate is problem when any service down`() {
        val a = StatusResolver.aggregate(listOf(status(ServiceState.UP), status(ServiceState.DOWN), status(ServiceState.UP)))
        assertEquals(Overall.PROBLEM, a.overall)
        assertEquals(1, a.down)
        assertEquals(2, a.up)
        assertEquals(3, a.total)
    }

    @Test
    fun `aggregate all good ignores off-network, checking dominates when nothing down`() {
        val good = StatusResolver.aggregate(listOf(status(ServiceState.UP), status(ServiceState.OFF_NETWORK)))
        assertEquals(Overall.ALL_GOOD, good.overall)
        assertEquals(1, good.offNetwork)

        val checking = StatusResolver.aggregate(listOf(status(ServiceState.UP), status(ServiceState.CHECKING)))
        assertEquals(Overall.CHECKING, checking.overall)
    }
}
