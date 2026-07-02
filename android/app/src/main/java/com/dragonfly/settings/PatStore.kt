package com.dragonfly.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub PAT, encrypted at rest (CLAUDE.md requirement) — kept out of the plain-text DataStore
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
}
