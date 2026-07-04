package com.dragonfly.status

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The pure half of the status dashboard — probe classification, `/version` parsing, and relative
 * time — kept free of Android/OkHttp so it's trivially unit-testable (the `update/ReleaseResolver`
 * precedent).
 */
object StatusResolver {
    private val json = Json { ignoreUnknownKeys = true }

    /** HTTP statuses that mean the gateway/service is down rather than merely guarded. */
    private val DOWN_CODES = setOf(502, 503, 504, 530)

    /** {"status":"ok"} from a suite backend's /health. */
    @Serializable
    private data class HealthJson(val status: String? = null)

    @Serializable
    private data class VersionJson(
        val name: String? = null,
        val version: String? = null,
        val commit: String? = null,
        val built_at: String? = null,
    )

    /**
     * Classify a suite backend from its /health outcome. UP only on a 200 whose body is
     * {"status":"ok"}; a reachable-but-wrong response is DOWN (the process answered but is unhealthy).
     */
    fun classifySuiteHealth(outcome: ProbeOutcome, reachability: Reachability): ServiceState =
        when (outcome) {
            is ProbeOutcome.ConnectFailed -> offlineOrDown(reachability)
            is ProbeOutcome.Http ->
                if (outcome.code == 200 && parseHealthOk(outcome.body)) ServiceState.UP
                else ServiceState.DOWN
        }

    /**
     * Classify a reachability-only service. Any HTTP response that isn't a gateway error means the
     * service is up behind its proxy — including Caddy's basic_auth 401 (OPERATIONS.md §3) and Plex
     * redirects. A connection failure is DOWN, or OFF_NETWORK for a tailnet-only service.
     */
    fun classifyReachability(outcome: ProbeOutcome, reachability: Reachability): ServiceState =
        when (outcome) {
            is ProbeOutcome.ConnectFailed -> offlineOrDown(reachability)
            is ProbeOutcome.Http -> if (outcome.code in DOWN_CODES) ServiceState.DOWN else ServiceState.UP
        }

    private fun offlineOrDown(reachability: Reachability): ServiceState =
        if (reachability == Reachability.TAILNET_ONLY) ServiceState.OFF_NETWORK else ServiceState.DOWN

    private fun parseHealthOk(body: String?): Boolean =
        body != null && runCatching { json.decodeFromString<HealthJson>(body).status == "ok" }.getOrDefault(false)

    /** Parse `/version`; returns null if the body isn't the expected shape. */
    fun parseVersion(body: String): VersionInfo? = runCatching {
        val v = json.decodeFromString<VersionJson>(body)
        val name = v.version ?: return null
        VersionInfo(
            version = name,
            commit = v.commit?.takeIf { it.isNotBlank() },
            deployedAt = v.built_at?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }.getOrNull()

    /** Roll up per-service states for the Home banner: any DOWN dominates; else CHECKING; else good. */
    fun aggregate(statuses: List<ServiceStatus>): StatusAggregate {
        val down = statuses.count { it.state == ServiceState.DOWN }
        val overall = when {
            down > 0 -> Overall.PROBLEM
            statuses.any { it.state == ServiceState.CHECKING } -> Overall.CHECKING
            else -> Overall.ALL_GOOD
        }
        return StatusAggregate(
            overall = overall,
            up = statuses.count { it.state == ServiceState.UP },
            down = down,
            offNetwork = statuses.count { it.state == ServiceState.OFF_NETWORK },
            total = statuses.size,
        )
    }

    /** Compact "deployed 3h ago" style label from an instant to now (clamps future to "just now"). */
    fun relativeTime(from: Instant, now: Instant): String {
        val d = Duration.between(from, now)
        val secs = d.seconds
        return when {
            secs < 45 -> "just now"
            secs < 90 -> "1m ago"
            secs < 3600 -> "${(secs + 30) / 60}m ago"
            secs < 5400 -> "1h ago"
            secs < 86_400 -> "${(secs + 1800) / 3600}h ago"
            secs < 172_800 -> "1d ago"
            else -> "${(secs + 43_200) / 86_400}d ago"
        }
    }
}
