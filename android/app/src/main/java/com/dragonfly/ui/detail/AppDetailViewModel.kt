package com.dragonfly.ui.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.history.UpdateHistoryStore
import com.dragonfly.history.UpdateRecord
import com.dragonfly.registry.AppRegistry
import com.dragonfly.settings.SettingsRepository
import com.dragonfly.settings.UpdateSource
import com.dragonfly.update.AppState
import com.dragonfly.update.AppStatus
import com.dragonfly.update.UpdateFlowManager
import com.dragonfly.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val status: AppStatus? = null,
    val checking: Boolean = false,
    /** null = follows the global default. */
    val sourceOverride: UpdateSource? = null,
    val history: List<UpdateRecord> = emptyList(),
    val phase: UpdateFlowManager.Phase = UpdateFlowManager.Phase(),
    /** Rolled-up notes across every release newer than the installed build; null = show latest only. */
    val changesSince: String? = null,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val updateRepository: UpdateRepository,
    private val settingsRepository: SettingsRepository,
    private val flowManager: UpdateFlowManager,
    historyStore: UpdateHistoryStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val app = AppRegistry.byKey(checkNotNull(savedStateHandle["key"]))
        ?: error("unknown app key")

    private val status = MutableStateFlow<AppStatus?>(null)
    private val checking = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _needsInstallPermission = MutableStateFlow(false)
    val needsInstallPermission = _needsInstallPermission.asStateFlow()

    private val changesSince = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DetailUiState> = combine(
        status,
        checking,
        settingsRepository.snapshots.map { it.perAppSource[app.key] },
        historyStore.records.map { records -> records.filter { it.appKey == app.key } },
        flowManager.phases.map { it[app.key] ?: UpdateFlowManager.Phase() },
    ) { appStatus, isChecking, override, history, phase ->
        DetailUiState(appStatus, isChecking, override, history, phase)
    }.combine(changesSince) { s, changes -> s.copy(changesSince = changes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        check()
        viewModelScope.launch {
            flowManager.messages.collect {
                check() // install finished (or failed) — refresh the installed version
            }
        }
    }

    fun check() {
        if (checking.value) return
        viewModelScope.launch {
            checking.value = true
            try {
                val result = updateRepository.check(app)
                status.value = result
                // Only worth a second call (a releases-list fetch) when there's actually an update
                // to roll up; otherwise the single latest note the check already carries is enough.
                changesSince.value = if (result.state == AppState.UPDATE_AVAILABLE) {
                    updateRepository.changesSinceInstalled(app, result.installedVersionName)
                } else {
                    null
                }
            } catch (e: Exception) {
                _message.value = "Check failed: ${e.message ?: "network error"}"
            } finally {
                checking.value = false
            }
        }
    }

    fun setSourceOverride(source: UpdateSource?) {
        viewModelScope.launch {
            settingsRepository.setAppSource(app.key, source)
            check()
        }
    }

    fun update() {
        val latest = status.value?.latest ?: return
        viewModelScope.launch {
            when (flowManager.start(app, latest)) {
                UpdateFlowManager.StartResult.NeedsWifi ->
                    _message.value = "Downloads are Wi-Fi-only — connect to Wi-Fi or change Settings"
                UpdateFlowManager.StartResult.NeedsInstallPermission ->
                    _needsInstallPermission.value = true
                else -> Unit
            }
        }
    }

    fun openUnknownSourcesSettings() {
        _needsInstallPermission.value = false
        context.startActivity(flowManager.unknownSourcesIntent())
    }

    fun dismissInstallPermissionDialog() {
        _needsInstallPermission.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }
}
