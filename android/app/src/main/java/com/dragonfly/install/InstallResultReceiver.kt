package com.dragonfly.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.dragonfly.history.UpdateHistoryStore
import com.dragonfly.history.UpdateRecord
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives PackageInstaller session status. STATUS_PENDING_USER_ACTION relays the system
 * confirmation dialog (the one tap per update the spec accepts); terminal statuses land in
 * update history and on the [InstallEventBus].
 */
class InstallResultReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun historyStore(): UpdateHistoryStore
        fun eventBus(): InstallEventBus
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            return
        }

        val appKey = intent.getStringExtra(EXTRA_APP_KEY) ?: return
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "?"
        val versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1)
        val success = status == PackageInstaller.STATUS_SUCCESS
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?: if (success) null else "install failed (status $status)"

        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val result = InstallResult(appKey, versionName, versionCode, success, message)
        deps.eventBus().emit(result)
        // goAsync so the DataStore write survives the receiver returning.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                deps.historyStore().add(
                    UpdateRecord(
                        appKey = appKey,
                        versionName = versionName,
                        versionCode = versionCode,
                        timestampMs = System.currentTimeMillis(),
                        success = success,
                        message = message,
                    )
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.dragonfly.INSTALL_RESULT"
        const val EXTRA_APP_KEY = "app_key"
        const val EXTRA_VERSION_NAME = "version_name"
        const val EXTRA_VERSION_CODE = "version_code"
    }
}
