package com.dragonfly.net

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small-body GETs at arbitrary URLs (GitHub version.json assets) — everything Retrofit's fixed
 * base URL doesn't fit. Uses the same client (and so the same GitHub auth interceptor) as the
 * rest of the app.
 */
@Singleton
class HttpFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetchString(url: String, accept: String? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                if (accept != null) header("Accept", accept)
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GET $url -> HTTP ${response.code}")
                response.body?.string() ?: throw IOException("GET $url -> empty body")
            }
        }
}
