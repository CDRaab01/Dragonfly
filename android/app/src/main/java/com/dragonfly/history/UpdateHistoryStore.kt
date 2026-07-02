package com.dragonfly.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UpdateRecord(
    val appKey: String,
    val versionName: String,
    val versionCode: Long,
    val timestampMs: Long,
    val success: Boolean,
    val message: String? = null,
)

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore("update_history")

/**
 * Install outcomes, newest first, capped — a small JSON list in DataStore. Room stays out until
 * history needs querying (CLAUDE.md).
 */
@Singleton
class UpdateHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("records")
    private val json = Json { ignoreUnknownKeys = true }

    val records: Flow<List<UpdateRecord>> = context.historyDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<UpdateRecord>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun add(record: UpdateRecord) {
        context.historyDataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<UpdateRecord>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[key] = json.encodeToString(listOf(record) + current.take(MAX_RECORDS - 1))
        }
    }

    private companion object {
        const val MAX_RECORDS = 50
    }
}
