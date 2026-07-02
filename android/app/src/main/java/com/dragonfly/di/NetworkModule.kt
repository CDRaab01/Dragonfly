package com.dragonfly.di

import com.dragonfly.net.GitHubApi
import com.dragonfly.settings.PatStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * One client for everything: GitHub API calls, manifest fetches, APK downloads. The auth
     * interceptor attaches the PAT to github.com hosts only — it must never leak to the
     * self-host or asset-CDN redirects off GitHub.
     */
    @Provides
    @Singleton
    fun okHttpClient(patStore: PatStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(gitHubAuth(patStore))
            .build()

    private fun gitHubAuth(patStore: PatStore) = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val pat = patStore.githubPat
        if (pat != null && (host == "api.github.com" || host == "github.com")) {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $pat").build())
        } else {
            chain.proceed(request)
        }
    }

    @Provides
    @Singleton
    fun gitHubApi(client: OkHttpClient): GitHubApi {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubApi::class.java)
    }
}
