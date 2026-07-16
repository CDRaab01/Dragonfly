package com.dragonfly.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly-digest read key, encrypted at rest — the same treatment the GitHub PAT gets
 * ([PatStore]), stored in the same `secrets` EncryptedSharedPreferences file under a distinct key.
 * Reads are synchronous so the digest fetch can attach the `X-Digest-Key` header on the request path.
 */
@Singleton
class DigestKeyStore @Inject constructor(
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

    var digestKey: String?
        get() = prefs.getString("digest_key", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("digest_key") else putString("digest_key", value.trim())
            }.apply()
        }
}
