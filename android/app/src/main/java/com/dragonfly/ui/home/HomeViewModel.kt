package com.dragonfly.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.update.AppState
import com.dragonfly.update.AppStatus
import com.dragonfly.update.InstalledAppsDataSource
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val cards: List<CardUi> = emptyList(),
    val checking: Boolean = false,
    val updatesAvailable: Int = 0,
) {
    data class CardUi(
        val status: AppStatus,
        val phase: UpdateFlowManager.Phase,
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val flowManager: UpdateFlowManager,
    private val installedApps: InstalledAppsDataSource,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val statuses = MutableStateFlow<List<AppStatus>>(emptyList())
    private val checking = MutableStateFlow(false)

    /** One-shot snackbar text; null when consumed. */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** True while the "grant install permission" dialog should show. */
    private val _needsInstallPermission = MutableStateFlow(false)
    val needsInstallPermission = _needsInstallPermission.asStateFlow()

    val uiState: StateFlow<HomeUiState> =
        combine(statuses, checking, flowManager.phases) { statusList, isChecking, phases ->
            HomeUiState(
                cards = statusList.map {
                    HomeUiState.CardUi(it, phases[it.app.key] ?: UpdateFlowManager.Phase())
                },
                checking = isChecking,
                updatesAvailable = statusList.count { it.state == AppState.UPDATE_AVAILABLE },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
        viewModelScope.launch {
            flowManager.messages.collect { text ->
                _message.value = text
                refresh() // a finished install changes an installed version
            }
        }
    }

    fun refresh() {
        if (checking.value) return
        viewModelScope.launch {
            checking.value = true
            try {
                statuses.value = updateRepository.checkAll()
            } catch (e: Exception) {
                _message.value = "Check failed: ${e.message ?: "network error"}"
            } finally {
                checking.value = false
            }
        }
    }

    fun updateApp(appKey: String) {
        val status = statuses.value.firstOrNull { it.app.key == appKey } ?: return
        val latest = status.latest ?: return
        viewModelScope.launch {
            when (flowManager.start(status.app, latest)) {
                UpdateFlowManager.StartResult.NeedsWifi ->
                    _message.value = "Downloads are Wi-Fi-only — connect to Wi-Fi or change Settings"
                UpdateFlowManager.StartResult.NeedsInstallPermission ->
                    _needsInstallPermission.value = true
                else -> Unit
            }
        }
    }

    /** Clear a stuck/unwanted download-or-install so the card can be retried. */
    fun resetInstall(appKey: String) {
        flowManager.reset(appKey)
    }

    fun launchApp(appKey: String) {
        val status = statuses.value.firstOrNull { it.app.key == appKey } ?: return
        val intent = installedApps.launchIntent(status.app.packageName)
        if (intent == null) {
            _message.value = "${status.app.displayName} isn't installed"
            return
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openUnknownSourcesSettings() {
        _needsInstallPermission.value = false
        context.startActivity(flowManager.unknownSourcesIntent())
    }

    fun dismissInstallPermissionDialog() {
        _needsInstallPermission.value = false
    }

    fun consumeMessage() {
        _message.update { null }
    }
}
