package com.dragonfly.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.status.Overall
import com.dragonfly.status.ServiceGroup
import com.dragonfly.status.ServiceStatus
import com.dragonfly.status.StatusAggregate
import com.dragonfly.status.StatusRepository
import com.dragonfly.status.StatusResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusUiState(
    val groups: List<GroupUi> = emptyList(),
    val aggregate: StatusAggregate = StatusAggregate(Overall.CHECKING, 0, 0, 0, 0),
    val checking: Boolean = false,
    val lastChecked: Instant? = null,
) {
    data class GroupUi(val group: ServiceGroup, val rows: List<ServiceStatus>)
}

@HiltViewModel
class SuiteStatusViewModel @Inject constructor(
    private val repository: StatusRepository,
) : ViewModel() {

    private val checking = MutableStateFlow(false)

    val uiState: StateFlow<StatusUiState> =
        combine(repository.statuses, checking) { statuses, isChecking ->
            StatusUiState(
                groups = ServiceGroup.entries.mapNotNull { group ->
                    statuses.filter { it.service.group == group }
                        .takeIf { it.isNotEmpty() }
                        ?.let { StatusUiState.GroupUi(group, it) }
                },
                aggregate = StatusResolver.aggregate(statuses),
                checking = isChecking,
                lastChecked = statuses.mapNotNull { it.checkedAt }.maxOrNull(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (checking.value) return
        viewModelScope.launch {
            checking.value = true
            try {
                repository.refresh()
            } finally {
                checking.value = false
            }
        }
    }
}
