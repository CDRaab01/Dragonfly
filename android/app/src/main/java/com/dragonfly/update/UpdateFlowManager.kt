package com.dragonfly.update

import com.dragonfly.di.ApplicationScope
import com.dragonfly.install.ApkDownloader
import com.dragonfly.install.ApkInstaller
import com.dragonfly.install.InstallEventBus
import com.dragonfly.net.NetworkStatus
import com.dragonfly.registry.ManagedApp
import com.dragonfly.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * The install step ends by committing a PackageInstaller session and waiting for the system
 * confirmation dialog — which is out of our hands. So a card can never wedge in INSTALLING: a
 * per-app [reset] clears it on demand, and a watchdog clears it if no result arrives in time.
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

    // In-flight pipeline coroutine per app, so a wedged/unwanted card can be canceled.
    private val jobs = ConcurrentHashMap<String, Job>()
    // Generation token per app: a stale INSTALLING watchdog only fires if it still matches, so a
    // reset-then-retry can't be clobbered by the previous attempt's watchdog.
    private val installGen = ConcurrentHashMap<String, Int>()

    init {
        // Any terminal install result ends that app's pipeline phase.
        scope.launch {
            eventBus.results.collect { result ->
                clearTracking(result.appKey)
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

        jobs[app.key] = scope.launch {
            try {
                setPhase(app.key, Phase(PhaseKind.DOWNLOADING))
                val apk = downloader.download(app.key, release) { progress ->
                    setPhase(app.key, Phase(PhaseKind.DOWNLOADING, progress))
                }
                setPhase(app.key, Phase(PhaseKind.VERIFYING))
                // (verification already ran inside download; the phase makes the step visible)
                val gen = (installGen[app.key] ?: 0) + 1
                installGen[app.key] = gen
                setPhase(app.key, Phase(PhaseKind.INSTALLING))
                installer.install(app.key, app.packageName, release.versionName, release.versionCode, apk)
                // The system confirmation is out of our hands. If no terminal result arrives, don't
                // wedge the card forever — clear it so the user can retry. Non-destructive: a late OS
                // result still records to history and flips the card via the event bus.
                scope.launch {
                    delay(INSTALL_CONFIRM_TIMEOUT_MS)
                    if (installGen[app.key] == gen &&
                        _phases.value[app.key]?.kind == PhaseKind.INSTALLING
                    ) {
                        clearTracking(app.key)
                        setPhase(app.key, Phase())
                        _messages.tryEmit("${app.displayName}: install didn't confirm — tap to retry")
                    }
                }
            } catch (e: Exception) {
                clearTracking(app.key)
                setPhase(app.key, Phase())
                _messages.tryEmit("${app.displayName} update failed: ${e.message ?: "download error"}")
            }
        }
        return StartResult.Started
    }

    /** Clear a wedged (or unwanted) pipeline back to idle so the card is actionable again. */
    fun reset(appKey: String) {
        clearTracking(appKey)
        setPhase(appKey, Phase())
    }

    private fun clearTracking(appKey: String) {
        jobs.remove(appKey)?.cancel()
        installGen.remove(appKey)
    }

    private fun setPhase(appKey: String, phase: Phase) {
        _phases.update { it + (appKey to phase) }
    }

    private companion object {
        // Generous: the OS install prompt can sit while the user reads it. This only resets the
        // UI, never the actual install, so erring long is safe.
        const val INSTALL_CONFIRM_TIMEOUT_MS = 120_000L
    }
}
