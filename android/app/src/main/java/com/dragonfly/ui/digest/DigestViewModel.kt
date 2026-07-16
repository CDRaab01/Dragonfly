package com.dragonfly.ui.digest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.digest.DigestRepository
import com.dragonfly.digest.DigestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DigestUiState(
    val loading: Boolean = false,
    /** Null before the first load completes; otherwise the latest fetch outcome. */
    val result: DigestResult? = null,
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    private val repository: DigestRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DigestUiState(loading = true))
    val state: StateFlow<DigestUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading && _state.value.result != null) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = repository.fetchWeekly()
            _state.value = DigestUiState(loading = false, result = result)
        }
    }
}
