package com.memevault.app

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object Hashing {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun sha256Text(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

object DisplayNames {
    fun fromUri(resolver: ContentResolver, uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        // Sender-controlled names are stored and later forwarded to other apps; sanitize them.
        return DisplayNamePolicy.sanitize(queried).takeIf { it.isNotBlank() }
            ?: DisplayNamePolicy.sanitize(uri.lastPathSegment?.substringAfterLast('/')).takeIf { it.isNotBlank() }
            ?: "Meme ${System.currentTimeMillis()}"
    }

}

internal class FileSizeLimitExceededException(maxBytes: Long) :
    IOException("File vượt quá giới hạn ${maxBytes / (1024 * 1024)} MB")

/** Copies an untrusted provider stream without ever writing beyond the configured limit. */
internal object BoundedStreams {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0L) { "Giới hạn file không hợp lệ" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > maxBytes - read) throw FileSizeLimitExceededException(maxBytes)
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }
}

internal object VideoDimensions {
    fun oriented(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        return if (normalizedRotation == 90 || normalizedRotation == 270) {
            height to width
        } else {
            width to height
        }
    }
}
