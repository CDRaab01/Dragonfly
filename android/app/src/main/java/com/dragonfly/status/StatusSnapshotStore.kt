package com.dragonfly.status

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Last-known per-service probe state, persisted so the home-screen widget can render the suite at a
 * glance without running a network probe of its own (the Magpie `SnapshotStore` precedent). The
 * [StatusRepository] writes a fresh snapshot after every probe pass; the widget only ever reads it.
 *
 * Only the fields the widget needs are stored — no version/latency/timestamps — so this stays a
 * tiny, stable JSON blob. Nothing sensitive lives here (it's the same up/down truth the Suite
 * status screen shows), so plain DataStore, no encryption.
 */
@Singleton
class StatusSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(snapshot: WidgetStatusSnapshot) {
        val encoded = json.encodeToString(snapshot)
        context.statusSnapshotDataStore.edit { it[KEY] = encoded }
    }

    suspend fun read(): WidgetStatusSnapshot? {
        val raw = context.statusSnapshotDataStore.data.map { it[KEY] }.first() ?: return null
        return runCatching { json.decodeFromString<WidgetStatusSnapshot>(raw) }.getOrNull()
    }

    private companion object {
        val KEY = stringPreferencesKey("suite_status")
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val Context.statusSnapshotDataStore by preferencesDataStore(name = "dragonfly_status_snapshot")

/** The compact per-service view the widget renders. State/group are enum names, kept as strings so
 * an added enum case never crashes an old widget decode. */
@Serializable
data class WidgetServiceStatus(
    val key: String,
    val displayName: String,
    val group: String,
    val state: String,
)

@Serializable
data class WidgetStatusSnapshot(val services: List<WidgetServiceStatus>)

/** Pure projection of a probe pass into the widget snapshot — unit-testable without a socket. */
fun buildWidgetSnapshot(statuses: List<ServiceStatus>): WidgetStatusSnapshot =
    WidgetStatusSnapshot(
        statuses.map {
            WidgetServiceStatus(
                key = it.service.key,
                displayName = it.service.displayName,
                group = it.service.group.name,
                state = it.state.name,
            )
        },
    )
