package com.dragonfly.status

import java.time.Instant

/** Live state of one service. */
enum class ServiceState {
    UP,
    DOWN,
    /** Tailnet-only service unreachable from where we are — not an outage. */
    OFF_NETWORK,
    /** Probe in flight (initial state before the first result). */
    CHECKING,
    UNKNOWN,
}

/** Version readout parsed from a suite backend's `/version`. */
data class VersionInfo(
    val version: String,
    val commit: String?,
    val deployedAt: Instant?,
)

/** Result of probing a [MonitoredService]. */
data class ServiceStatus(
    val service: MonitoredService,
    val state: ServiceState,
    val version: VersionInfo? = null,
    val latencyMs: Long? = null,
    val checkedAt: Instant? = null,
)

/** Roll-up of every service's state, for Home's glanceable banner. */
enum class Overall { ALL_GOOD, PROBLEM, CHECKING }

data class StatusAggregate(
    val overall: Overall,
    val up: Int,
    val down: Int,
    val offNetwork: Int,
    val total: Int,
)

/**
 * Raw outcome of a single HTTP probe, produced by the network layer and classified by the pure
 * [StatusResolver]. Keeping this seam means classification is testable without a socket.
 */
sealed interface ProbeOutcome {
    /** An HTTP response arrived (any status code). [body] is only read for SUITE /health. */
    data class Http(val code: Int, val body: String? = null) : ProbeOutcome

    /** Connection failed / timed out — no HTTP response at all. */
    data object ConnectFailed : ProbeOutcome
}
