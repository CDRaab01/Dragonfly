package com.dragonfly.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dragonfly.MainActivity
import com.dragonfly.R
import com.dragonfly.update.AppState
import com.dragonfly.update.UpdateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The DAILY auto-check: refresh every app's source and nudge with a notification when updates
 * are waiting. Failures are non-fatal — the next period tries again (Tailscale host may be down).
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val statuses = runCatching { updateRepository.checkAll() }.getOrElse { return Result.retry() }
        val updates = statuses.filter { it.state == AppState.UPDATE_AVAILABLE }
        if (updates.isNotEmpty()) notify(updates.map { it.app.displayName })
        return Result.success()
    }

    private fun notify(appNames: List<String>) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // API 33+: user hasn't granted notifications; the Home screen still shows badges.
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                if (appNames.size == 1) "Update available: ${appNames.first()}"
                else "${appNames.size} app updates available"
            )
            .setContentText(appNames.joinToString(", "))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 1
    }
}
