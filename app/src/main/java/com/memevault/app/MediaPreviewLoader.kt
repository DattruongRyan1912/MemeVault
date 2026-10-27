package com.memevault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import java.io.File

/**
 * Bounded preview decoder used by scrolling surfaces. It never decodes an original at full
 * resolution and returns one poster frame for animated/video media; animation belongs to detail.
 */
internal object MediaPreviewLoader {
    // One eighth of the heap (in KB): grid, picker and keyboard share this cache, and a 3–4
    // column grid needs dozens of tiles resident to scroll back without re-decoding.
    private val cacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
        .coerceIn(8L * 1024L, 64L * 1024L).toInt()

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun shouldPlayInline(mimeType: String): Boolean {
        val mime = MediaPolicy.normalizeMime(mimeType)
        return mime == MediaPolicy.GIF_MIME || mime == MediaPolicy.WEBP_MIME
    }

    fun decodeAnimatedDrawable(file: File, mimeType: String, targetPx: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (!shouldPlayInline(mimeType) || !file.isFile) return null
        return runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = calculateSample(info.size.width, info.size.height, targetPx)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        }.getOrNull()
    }

    fun startIfAnimated(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    fun stopIfAnimated(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.stop()
        }
    }

    fun decode(file: File, mimeType: String, targetPx: Int): Bitmap? {
        if (!file.isFile) return null
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}:$targetPx"
        synchronized(cache) {
            cache.get(cacheKey)?.takeUnless(Bitmap::isRecycled)?.let { return it }
        }
        val decoded = if (mimeType.startsWith("video/")) {
            decodeVideo(file, targetPx)
        } else {
            decodeImage(file, mimeType, targetPx)
        } ?: return null
        synchronized(cache) { cache.put(cacheKey, decoded) }
        return decoded
    }

    private fun decodeImage(file: File, mimeType: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, targetPx)
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (mimeType == MediaPolicy.PNG_MIME || mimeType == "image/webp") {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
        })
    }

    private fun decodeVideo(file: File, targetPx: Int): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetPx,
                    targetPx,
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun calculateSample(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > target || height / sample > target) sample *= 2
        return sample
    }
}
