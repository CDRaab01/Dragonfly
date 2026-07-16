package com.dragonfly.digest

import com.dragonfly.settings.DigestKeyStore
import com.dragonfly.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the suite's weekly recap from dragonfly-id. A raw OkHttp call (the `status/StatusProber`
 * precedent) rather than Retrofit, because the base URL is a user-configurable setting and the
 * screen branches on the raw 200/404/401 status codes. Uses the shared client — its GitHub auth
 * interceptor only fires on github.com hosts, so the digest key is never leaked to it.
 */
@Singleton
class DigestRepository @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val digestKeyStore: DigestKeyStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchWeekly(): DigestResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.snapshot()
        val baseUrl = settings.digestBaseUrl.trim().trimEnd('/')
        val key = digestKeyStore.digestKey
        val url = "$baseUrl/digest/weekly"

        val request = Request.Builder()
            .url(url)
            .get()
            .apply { if (!key.isNullOrBlank()) header("X-Digest-Key", key) }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        runCatching { json.decodeFromString<WeeklyDigest>(body) }
                            .fold(
                                onSuccess = { DigestResult.Success(it) },
                                onFailure = { DigestResult.Error("Couldn't read this week's digest") },
                            )
                    }
                    401 -> DigestResult.NeedsKey
                    404 -> DigestResult.NotYet
                    else -> DigestResult.Error("Digest server error (HTTP ${response.code})")
                }
            }
        } catch (_: IOException) {
            DigestResult.Error("Can't reach the digest server")
        } catch (_: IllegalArgumentException) {
            // Malformed/blank base URL — surface it, never crash.
            DigestResult.Error("Digest server URL isn't valid")
        }
    }
}
