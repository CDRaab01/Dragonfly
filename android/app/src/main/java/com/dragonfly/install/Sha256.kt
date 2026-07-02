package com.dragonfly.install

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256, lowercase hex — matches what the manifest/version.json publish. */
object Sha256 {
    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(file: File): String = file.inputStream().use { of(it) }

    /** Case-insensitive compare; expected may arrive with mixed case from hand-built manifests. */
    fun matches(file: File, expected: String): Boolean = of(file).equals(expected.trim(), ignoreCase = true)
}
