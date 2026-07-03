package com.dragonfly.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    /**
     * The installed app's launcher icon, rasterized to an [ImageBitmap] for Compose. Null when the
     * app isn't installed. Adaptive icons are drawn with both layers into a square; the UI rounds
     * the corners. Rasterizing is cheap (one small bitmap) and only runs on refresh.
     */
    fun appIcon(packageName: String, sizePx: Int = 144): ImageBitmap? {
        val drawable = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
