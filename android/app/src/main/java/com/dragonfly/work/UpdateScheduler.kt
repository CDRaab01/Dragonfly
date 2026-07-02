package com.dragonfly.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dragonfly.settings.CheckInterval
import com.dragonfly.settings.SettingsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the periodic check aligned with the auto-check interval + Wi-Fi-only settings. */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(settings: SettingsSnapshot) {
        val workManager = WorkManager.getInstance(context)
        if (settings.checkInterval != CheckInterval.DAILY) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "update-check"
    }
}
