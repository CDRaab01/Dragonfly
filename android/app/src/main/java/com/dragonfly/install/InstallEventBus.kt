package com.dragonfly.install

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class InstallResult(
    val appKey: String,
    val versionName: String,
    val versionCode: Long,
    val success: Boolean,
    val message: String? = null,
)

/**
 * Bridges PackageInstaller's broadcast results back into the UI layer: the receiver emits,
 * ViewModels collect (refresh cards, show a snackbar).
 */
@Singleton
class InstallEventBus @Inject constructor() {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 16)
    val results = _results.asSharedFlow()

    fun emit(result: InstallResult) {
        _results.tryEmit(result)
    }
}
