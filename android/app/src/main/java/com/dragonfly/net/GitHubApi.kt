package com.dragonfly.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val id: Long,
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GitHubRelease

    /** Releases newest-first — used to roll up "what changed since installed" for an app several
     *  versions behind. One page is plenty for a personal suite. */
    @GET("repos/{owner}/{repo}/releases")
    suspend fun releases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
    ): List<GitHubRelease>
}

/**
 * Download URL for a release asset. Public repos serve `browser_download_url` directly; private
 * repos 404 there for unauthenticated CDN fetches, so when a PAT is configured we go through the
 * API asset endpoint with `Accept: application/octet-stream` instead (the auth interceptor adds
 * the token; GitHub answers with a redirect OkHttp follows).
 */
fun GitHubAsset.downloadUrl(repo: String, usePat: Boolean): String =
    if (usePat) "https://api.github.com/repos/$repo/releases/assets/$id" else browserDownloadUrl
