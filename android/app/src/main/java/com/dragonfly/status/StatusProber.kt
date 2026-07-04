package com.dragonfly.status

import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Runs the actual HTTP probes. Blocking OkHttp calls on [Dispatchers.IO]; a dedicated
 * short-timeout client (see NetworkModule) so a dead host fails fast instead of hanging the
 * dashboard. Classification lives in the pure [StatusResolver].
 */
@Singleton
class StatusProber @Inject constructor(
    @Named("status") private val client: OkHttpClient,
) {
    /** Probe one service (whose [MonitoredService.baseUrl] is already the effective/overridden URL). */
    suspend fun probe(service: MonitoredService): ServiceStatus = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        // SUITE reads the small /health body; REACHABILITY only needs the status code.
        val outcome = get(service.healthUrl, readBody = service.probe == ProbeType.SUITE)
        val latency = (System.nanoTime() - start) / 1_000_000

        val state = when (service.probe) {
            ProbeType.SUITE -> StatusResolver.classifySuiteHealth(outcome, service.reachability)
            ProbeType.REACHABILITY -> StatusResolver.classifyReachability(outcome, service.reachability)
        }

        val version = if (service.probe == ProbeType.SUITE && state == ServiceState.UP) {
            (get(service.versionUrl, readBody = true) as? ProbeOutcome.Http)
                ?.takeIf { it.code == 200 }?.body
                ?.let { StatusResolver.parseVersion(it) }
        } else {
            null
        }

        ServiceStatus(service, state, version, latencyMs = latency, checkedAt = Instant.now())
    }

    private fun get(url: String, readBody: Boolean): ProbeOutcome =
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                ProbeOutcome.Http(response.code, if (readBody) response.body?.string() else null)
            }
        } catch (_: IOException) {
            ProbeOutcome.ConnectFailed
        } catch (_: IllegalArgumentException) {
            // Malformed URL (e.g. a blank broker override) — treat as unreachable, never crash.
            ProbeOutcome.ConnectFailed
        }
}
