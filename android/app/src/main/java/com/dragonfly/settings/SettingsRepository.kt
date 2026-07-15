package com.dragonfly.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dragonfly.registry.AppRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val checkInterval = stringPreferencesKey("check.interval")
        val wifiOnly = booleanPreferencesKey("downloads.wifi_only")
        fun appServerUrl(appKey: String) = stringPreferencesKey("server_url.app.$appKey")
    }

    val snapshots: Flow<SettingsSnapshot> = context.settingsDataStore.data.map { prefs ->
        SettingsSnapshot(
            checkInterval = prefs[Keys.checkInterval].toEnum(CheckInterval.ON_LAUNCH),
            wifiOnly = prefs[Keys.wifiOnly] ?: false,
            perAppServerUrl = AppRegistry.apps.mapNotNull { app ->
                prefs[Keys.appServerUrl(app.key)]?.takeIf { it.isNotBlank() }?.let { app.key to it }
            }.toMap(),
        )
    }

    suspend fun snapshot(): SettingsSnapshot = snapshots.first()

    /** Blank clears the broker's opinion so the sibling falls back to its own configured URL. */
    suspend fun setAppServerUrl(appKey: String, url: String) = edit {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) it.remove(Keys.appServerUrl(appKey)) else it[Keys.appServerUrl(appKey)] = trimmed
    }

    suspend fun setCheckInterval(interval: CheckInterval) = edit { it[Keys.checkInterval] = interval.name }
    suspend fun setWifiOnly(enabled: Boolean) = edit { it[Keys.wifiOnly] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.toEnumOrNull<T>() ?: default

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }
