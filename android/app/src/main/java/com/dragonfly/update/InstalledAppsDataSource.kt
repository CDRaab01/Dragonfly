package com.dragonfly.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledVersion(val versionCode: Long, val versionName: String)

/** PackageManager reads for the managed packages (declared in the manifest <queries> block). */
@Singleton
class InstalledAppsDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun installedVersion(packageName: String): InstalledVersion? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        InstalledVersion(code, info.versionName ?: "?")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
}
