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
        val globalSource = stringPreferencesKey("source.global")
        val selfHostBaseUrl = stringPreferencesKey("selfhost.base_url")
        val checkInterval = stringPreferencesKey("check.interval")
        val wifiOnly = booleanPreferencesKey("downloads.wifi_only")
        fun appSource(appKey: String) = stringPreferencesKey("source.app.$appKey")
        fun appServerUrl(appKey: String) = stringPreferencesKey("server_url.app.$appKey")
    }

    val snapshots: Flow<SettingsSnapshot> = context.settingsDataStore.data.map { prefs ->
        SettingsSnapshot(
            globalSource = prefs[Keys.globalSource].toEnum(UpdateSource.GITHUB),
            perAppSource = AppRegistry.apps.mapNotNull { app ->
                prefs[Keys.appSource(app.key)]?.toEnumOrNull<UpdateSource>()?.let { app.key to it }
            }.toMap(),
            selfHostBaseUrl = prefs[Keys.selfHostBaseUrl].orEmpty(),
            checkInterval = prefs[Keys.checkInterval].toEnum(CheckInterval.ON_LAUNCH),
            wifiOnly = prefs[Keys.wifiOnly] ?: false,
            perAppServerUrl = AppRegistry.apps.mapNotNull { app ->
                prefs[Keys.appServerUrl(app.key)]?.takeIf { it.isNotBlank() }?.let { app.key to it }
            }.toMap(),
        )
    }

    suspend fun snapshot(): SettingsSnapshot = snapshots.first()

    suspend fun setGlobalSource(source: UpdateSource) = edit { it[Keys.globalSource] = source.name }

    /** null clears the override so the app follows the global default again. */
    suspend fun setAppSource(appKey: String, source: UpdateSource?) = edit {
        if (source == null) it.remove(Keys.appSource(appKey)) else it[Keys.appSource(appKey)] = source.name
    }

    suspend fun setSelfHostBaseUrl(url: String) = edit { it[Keys.selfHostBaseUrl] = url.trim() }

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
