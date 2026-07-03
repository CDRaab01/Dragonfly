package com.dragonfly.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
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
     * The app's launcher glyph as a WHITE monochrome [ImageBitmap] for Compose — a small mark shown
     * to the left of the app name on each hub card. We take the adaptive icon's foreground layer
     * (the logo shape, without its colored background) and force it white with a SRC_IN filter, so
     * every app reads consistently. Null when the app isn't installed. Only runs on refresh.
     */
    fun appIcon(packageName: String, sizePx: Int = 96): ImageBitmap? {
        val drawable = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val glyph = (if (drawable is AdaptiveIconDrawable) drawable.foreground ?: drawable else drawable).mutate()
        glyph.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        glyph.setBounds(0, 0, sizePx, sizePx)
        glyph.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
