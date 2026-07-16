package com.dragonfly.status

import com.dragonfly.settings.SettingsRepository
import com.dragonfly.widget.WidgetRefresher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the suite status check and caches the latest result so Home's banner and the Suite
 * Status screen share one fan-out. Suite backends whose broker `server_base_url` is configured use
 * that URL; everything else uses the registry default.
 */
@Singleton
class StatusRepository @Inject constructor(
    private val prober: StatusProber,
    private val settingsRepository: SettingsRepository,
    private val snapshotStore: StatusSnapshotStore,
    private val widgetRefresher: WidgetRefresher,
) {
    private val _statuses = MutableStateFlow(
        ServiceRegistry.services.map { ServiceStatus(it, ServiceState.CHECKING) },
    )
    val statuses: StateFlow<List<ServiceStatus>> = _statuses.asStateFlow()

    // One refresh at a time; a concurrent caller (Home + screen) reuses the same in-flight pass.
    private val mutex = Mutex()

    suspend fun refresh(): List<ServiceStatus> = mutex.withLock {
        val settings = settingsRepository.snapshot()
        val result = coroutineScope {
            ServiceRegistry.services.map { service ->
                val effective = service.overrideKey
                    ?.let { settings.serverUrlFor(it) }
                    ?.let { service.copy(baseUrl = it) }
                    ?: service
                async { prober.probe(effective) }
            }.awaitAll()
        }
        _statuses.value = result
        // Persist the last-known snapshot and redraw the home-screen widget — the widget reads this,
        // never the network. Failures here must not fail the in-app refresh.
        runCatching { snapshotStore.save(buildWidgetSnapshot(result)) }
        widgetRefresher.refresh()
        result
    }
}
