package com.dragonfly.di

import com.dragonfly.net.ReleaseSource
import com.dragonfly.settings.PatStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * One client for everything: release API calls, version.json fetches, APK downloads. The auth
     * interceptor attaches the token to the configured release host only (github.com, or the
     * Forgejo root's host) — it must never leak to asset-CDN redirects or anywhere else.
     */
    @Provides
    @Singleton
    fun okHttpClient(patStore: PatStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(gitHubAuth(patStore))
            .build()

    /**
     * A fast-failing client for the status dashboard: liveness pings must not inherit the main
     * client's 15s/60s timeouts, or a single dead host would stall the whole "is my world green"
     * fan-out. No PAT interceptor — these hit the suite/media hosts, never GitHub.
     */
    @Provides
    @Singleton
    @Named("status")
    fun statusOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun gitHubAuth(patStore: PatStore) = Interceptor { chain ->
        val request = chain.request()
        val header = ReleaseSource.authHeaderFor(
            url = request.url,
            source = patStore.releaseSource,
            githubPat = patStore.githubPat,
            forgejoToken = patStore.forgejoToken,
        )
        if (header != null) {
            chain.proceed(request.newBuilder().header("Authorization", header).build())
        } else {
            chain.proceed(request)
        }
    }
}
