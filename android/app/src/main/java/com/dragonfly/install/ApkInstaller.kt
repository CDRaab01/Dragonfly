package com.dragonfly.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The decided install mechanism (CLAUDE.md): a standard PackageInstaller session ending in the
 * system confirmation prompt — no silent install. Results come back through
 * [InstallResultReceiver] via the [InstallEventBus].
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** False until the user grants "Install unknown apps" for Dragonfly. */
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Deep link to the system toggle for this app. */
    fun unknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(
        appKey: String,
        packageName: String,
        versionName: String,
        versionCode: Long,
        apk: File,
    ) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("$appKey.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(InstallResultReceiver.EXTRA_APP_KEY, appKey)
                .putExtra(InstallResultReceiver.EXTRA_VERSION_NAME, versionName)
                .putExtra(InstallResultReceiver.EXTRA_VERSION_CODE, versionCode)
            // Mutable: the installer appends its status extras to this intent.
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}
