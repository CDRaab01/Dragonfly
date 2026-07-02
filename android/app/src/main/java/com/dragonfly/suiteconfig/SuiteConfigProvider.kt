package com.dragonfly.suiteconfig

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.dragonfly.registry.AppRegistry
import com.dragonfly.settings.SettingsRepository
import com.dragonfly.settings.SettingsSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Read-only suite connection config for sibling apps (BROKER.md Phase 1). Guarded by the
 * signature-level `READ_SUITE_CONFIG` permission, so only apps signed with the same suite key can
 * read it.
 *
 *   content://com.dragonfly.suiteconfig/config/{appKey}  → this app's config rows
 *   content://com.dragonfly.suiteconfig/config           → every app's rows (keys prefixed
 *                                                          `{appKey}.`), for diagnostics
 *
 * Each row is (key, value). Keys: `server_base_url`, `selfhost_base_url`. An empty
 * `server_base_url` means the broker has no opinion — the sibling keeps its own configured URL.
 * Contract is additive-only; a breaking change bumps the path to `/v2/…`.
 */
class SuiteConfigProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settings(): SettingsRepository
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "config", MATCH_ALL)
        addURI(AUTHORITY, "config/*", MATCH_ONE)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val appContext = context!!.applicationContext
        // ContentProvider.query is synchronous (binder thread); a single DataStore read is quick.
        val settings = runBlocking {
            EntryPointAccessors.fromApplication(appContext, Deps::class.java).settings().snapshot()
        }
        val cursor = MatrixCursor(arrayOf(COL_KEY, COL_VALUE))
        when (matcher.match(uri)) {
            MATCH_ONE -> addApp(cursor, uri.lastPathSegment.orEmpty(), settings)
            MATCH_ALL -> AppRegistry.apps.forEach { addApp(cursor, it.key, settings, prefix = "${it.key}.") }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        return cursor
    }

    private fun addApp(cursor: MatrixCursor, appKey: String, settings: SettingsSnapshot, prefix: String = "") {
        cursor.addRow(arrayOf(prefix + KEY_SERVER_BASE_URL, settings.serverUrlFor(appKey).orEmpty()))
        cursor.addRow(arrayOf(prefix + KEY_SELFHOST_BASE_URL, settings.selfHostBaseUrl))
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.config"

    // Read-only: config is edited only in Dragonfly's own UI.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.dragonfly.suiteconfig"
        const val COL_KEY = "key"
        const val COL_VALUE = "value"
        const val KEY_SERVER_BASE_URL = "server_base_url"
        const val KEY_SELFHOST_BASE_URL = "selfhost_base_url"

        /** content://com.dragonfly.suiteconfig/config/{appKey} */
        fun configUri(appKey: String): Uri =
            Uri.parse("content://$AUTHORITY/config/$appKey")

        private const val MATCH_ALL = 1
        private const val MATCH_ONE = 2
    }
}
