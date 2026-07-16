package com.dragonfly.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Redraws the home-screen suite-status widget after a probe pass refreshes the snapshot, so the
 * widget never lags the in-app status. Cheap no-op when no widget is placed (Cookbook precedent). */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh() {
        scope.launch {
            runCatching { DragonflyWidget().updateAll(context) }
        }
    }
}
