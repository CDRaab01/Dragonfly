package com.dragonfly.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where sibling releases are published. GitHub until the suite's repos move to the self-hosted
 * Forgejo; then the Release server setting points here and the GitHub branch can be deleted.
 *
 * Forgejo's release API is GitHub-shaped (same `tag_name` / `assets[].browser_download_url`
 * JSON), so [GitHubApi] serves both — only the base URL, the auth header and the asset download
 * path differ.
 */
sealed interface ReleaseSource {
    val apiBaseUrl: String

    data object GitHub : ReleaseSource {
        override val apiBaseUrl = "https://api.github.com/"
    }

    /** @param root the instance root, e.g. `https://forgejo.example.ts.net/`. */
    data class Forgejo(val root: HttpUrl) : ReleaseSource {
        override val apiBaseUrl = root.newBuilder().addPathSegments("api/v1/").build().toString()

        /**
         * Forgejo builds `browser_download_url` from its own ROOT_URL, which may not be the
         * hostname this phone reaches it by (LAN IP vs tailnet name). Re-home the URL onto the
         * configured root so the auth interceptor — which matches on host — attaches the token.
         */
        fun rehome(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            return parsed.newBuilder()
                .scheme(root.scheme)
                .host(root.host)
                .port(root.port)
                .build()
                .toString()
        }
    }

    companion object {
        /**
         * The Authorization header for [url], or null. GitHub hosts get the PAT (always — the
         * Forgejo setting falls back to GitHub per repo); the Forgejo root's exact host+port gets
         * the Forgejo token. Nothing else ever gets a token (asset-CDN redirects, other hosts).
         */
        fun authHeaderFor(url: HttpUrl, source: ReleaseSource, githubPat: String?, forgejoToken: String?): String? =
            when {
                source is Forgejo && url.host == source.root.host && url.port == source.root.port ->
                    forgejoToken?.let { "token $it" }
                url.host == "api.github.com" || url.host == "github.com" -> githubPat?.let { "Bearer $it" }
                else -> null
            }

        /** Blank or unparseable → GitHub (the setting is optional during the migration). */
        fun from(forgejoUrl: String?): ReleaseSource {
            val trimmed = forgejoUrl?.trim().orEmpty()
            if (trimmed.isEmpty()) return GitHub
            val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            return withSlash.toHttpUrlOrNull()?.let { Forgejo(it) } ?: GitHub
        }
    }
}
