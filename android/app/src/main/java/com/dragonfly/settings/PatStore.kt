package com.dragonfly.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dragonfly.net.ReleaseSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release-server tokens (GitHub PAT, Forgejo access token), encrypted at rest (CLAUDE.md requirement) — kept out of the plain-text DataStore
 * the rest of the settings live in. Reads are synchronous so the OkHttp auth interceptor can use
 * them on the request path.
 */
@Singleton
class PatStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var githubPat: String?
        get() = prefs.getString("github_pat", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("github_pat") else putString("github_pat", value.trim())
            }.apply()
        }

    /**
     * Root URL of the self-hosted Forgejo that publishes sibling releases; null = GitHub.
     * Not a secret, but it lives beside the token because the auth interceptor needs both
     * synchronously on the request path (DataStore reads are suspend-only).
     */
    var releaseServerUrl: String?
        get() = prefs.getString("release_server_url", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("release_server_url") else putString("release_server_url", value.trim())
            }.apply()
        }

    /**
     * Forgejo access token (read:repository). Kept apart from [githubPat] because during the
     * migration a phone checks Forgejo first and falls back to GitHub for repos not moved yet,
     * so it needs both.
     */
    var forgejoToken: String?
        get() = prefs.getString("forgejo_token", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("forgejo_token") else putString("forgejo_token", value.trim())
            }.apply()
        }

    val releaseSource: ReleaseSource get() = ReleaseSource.from(releaseServerUrl)
}
