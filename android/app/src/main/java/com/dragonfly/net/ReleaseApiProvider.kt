package com.dragonfly.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Builds the release API for the currently configured [ReleaseSource]. Retrofit fixes its base
 * URL at build time and the source is a user setting, so instances are cached per base URL
 * instead of being a single Hilt singleton.
 */
@Singleton
class ReleaseApiProvider @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, GitHubApi>()

    @Synchronized
    fun api(source: ReleaseSource): GitHubApi = cache.getOrPut(source.apiBaseUrl) {
        Retrofit.Builder()
            .baseUrl(source.apiBaseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubApi::class.java)
    }
}
