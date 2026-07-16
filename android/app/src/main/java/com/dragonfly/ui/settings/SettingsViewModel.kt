package com.dragonfly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.settings.CheckInterval
import com.dragonfly.settings.DigestKeyStore
import com.dragonfly.settings.PatStore
import com.dragonfly.settings.SettingsRepository
import com.dragonfly.settings.SettingsSnapshot
import com.dragonfly.work.UpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val patStore: PatStore,
    private val digestKeyStore: DigestKeyStore,
    private val scheduler: UpdateScheduler,
) : ViewModel() {

    val settings: StateFlow<SettingsSnapshot> = settingsRepository.snapshots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsSnapshot())

    /** Whether a PAT is stored; the value itself is never echoed back to the UI. */
    private val _hasPat = MutableStateFlow(patStore.githubPat != null)
    val hasPat = _hasPat.asStateFlow()

    /** Whether a digest key is stored; the value itself is never echoed back to the UI. */
    private val _hasDigestKey = MutableStateFlow(digestKeyStore.digestKey != null)
    val hasDigestKey = _hasDigestKey.asStateFlow()

    /** Broker-managed server URL for one app; blank clears it (sibling falls back to its own). */
    fun setAppServerUrl(appKey: String, url: String) = viewModelScope.launch {
        settingsRepository.setAppServerUrl(appKey, url)
    }

    fun setCheckInterval(interval: CheckInterval) = viewModelScope.launch {
        settingsRepository.setCheckInterval(interval)
        reschedule()
    }

    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setWifiOnly(enabled)
        reschedule()
    }

    fun savePat(pat: String) {
        patStore.githubPat = pat
        _hasPat.value = patStore.githubPat != null
    }

    fun clearPat() {
        patStore.githubPat = null
        _hasPat.value = false
    }

    fun setDigestBaseUrl(url: String) = viewModelScope.launch {
        settingsRepository.setDigestBaseUrl(url)
    }

    fun saveDigestKey(key: String) {
        digestKeyStore.digestKey = key
        _hasDigestKey.value = digestKeyStore.digestKey != null
    }

    fun clearDigestKey() {
        digestKeyStore.digestKey = null
        _hasDigestKey.value = false
    }

    private suspend fun reschedule() {
        scheduler.apply(settingsRepository.snapshots.first())
    }
}
