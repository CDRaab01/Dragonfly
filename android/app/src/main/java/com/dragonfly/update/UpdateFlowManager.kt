package com.dragonfly.update

import com.dragonfly.di.ApplicationScope
import com.dragonfly.install.ApkDownloader
import com.dragonfly.install.ApkInstaller
import com.dragonfly.install.InstallEventBus
import com.dragonfly.net.NetworkStatus
import com.dragonfly.registry.ManagedApp
import com.dragonfly.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the download → verify → install pipeline and its per-app progress, shared by the Home and
 * App-detail ViewModels. Lives in the application scope so an in-flight update survives leaving
 * the screen; terminal results come back through [InstallEventBus].
 */
@Singleton
class UpdateFlowManager @Inject constructor(
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val networkStatus: NetworkStatus,
    private val settingsRepository: SettingsRepository,
    private val eventBus: InstallEventBus,
    @ApplicationScope private val scope: CoroutineScope,
) {
    enum class PhaseKind { IDLE, DOWNLOADING, VERIFYING, INSTALLING }

    /** [progress] is 0f..1f while downloading; -1f when the size is unknown. */
    data class Phase(val kind: PhaseKind = PhaseKind.IDLE, val progress: Float = 0f)

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyRunning : StartResult
        data object NeedsWifi : StartResult
        data object NeedsInstallPermission : StartResult
    }

    private val _phases = MutableStateFlow<Map<String, Phase>>(emptyMap())
    val phases = _phases.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        // Any terminal install result ends that app's pipeline phase.
        scope.launch {
            eventBus.results.collect { result ->
                setPhase(result.appKey, Phase())
                _messages.tryEmit(
                    if (result.success) "${result.appKey} updated to ${result.versionName}"
                    else "${result.appKey} install failed: ${result.message ?: "unknown error"}"
                )
            }
        }
    }

    fun unknownSourcesIntent() = installer.unknownSourcesIntent()

    suspend fun start(app: ManagedApp, release: LatestRelease): StartResult {
        if (_phases.value[app.key]?.kind?.let { it != PhaseKind.IDLE } == true) {
            return StartResult.AlreadyRunning
        }
        val settings = settingsRepository.snapshot()
        if (settings.wifiOnly && !networkStatus.isUnmetered()) return StartResult.NeedsWifi
        if (!installer.canRequestInstalls()) return StartResult.NeedsInstallPermission

        scope.launch {
            try {
                setPhase(app.key, Phase(PhaseKind.DOWNLOADING))
                val apk = downloader.download(app.key, release) { progress ->
                    setPhase(app.key, Phase(PhaseKind.DOWNLOADING, progress))
                }
                setPhase(app.key, Phase(PhaseKind.VERIFYING))
                // (verification already ran inside download; the phase makes the step visible)
                setPhase(app.key, Phase(PhaseKind.INSTALLING))
                installer.install(app.key, app.packageName, release.versionName, release.versionCode, apk)
                // Stay INSTALLING until the system prompt resolves via the event bus.
            } catch (e: Exception) {
                setPhase(app.key, Phase())
                _messages.tryEmit("${app.displayName} update failed: ${e.message ?: "download error"}")
            }
        }
        return StartResult.Started
    }

    private fun setPhase(appKey: String, phase: Phase) {
        _phases.update { it + (appKey to phase) }
    }
}
