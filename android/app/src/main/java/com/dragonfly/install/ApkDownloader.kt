package com.dragonfly.install

import android.content.Context
import com.dragonfly.update.LatestRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp streaming download into app-scoped cache (no DownloadManager — it's unreliable over VPN
 * interfaces like Tailscale), followed by the mandatory SHA-256 check. A hash mismatch deletes
 * the file and throws; a release without a published hash downloads with a warning note upstream.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    class IntegrityException(message: String) : IOException(message)

    /** @param onProgress 0f..1f, or -1f while the total size is unknown. */
    suspend fun download(
        appKey: String,
        release: LatestRelease,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "$appKey-${release.versionCode}.apk")

        val request = Request.Builder().url(release.apkUrl).apply {
            // GitHub's API asset endpoint serves the binary only with this Accept header.
            if (release.usesGitHubApiDownload) header("Accept", "application/octet-stream")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK download -> HTTP ${response.code}")
            val body = response.body ?: throw IOException("APK download -> empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(if (total > 0) copied.toFloat() / total else -1f)
                    }
                }
            }
        }

        val expected = release.sha256
        if (expected != null && !Sha256.matches(target, expected)) {
            target.delete()
            throw IntegrityException("SHA-256 mismatch — download discarded")
        }
        target
    }
}
