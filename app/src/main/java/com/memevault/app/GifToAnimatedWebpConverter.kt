@file:Suppress("DEPRECATION")

package com.memevault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Best-effort GIF → animated WebP adapter for the experimental sticker-like share path.
 *
 * Movie is the same decoder GifToMp4Converter uses. Transparency depends on that decoder and
 * must be verified on a device; encode failures leave the original GIF untouched.
 */
@Suppress("DEPRECATION")
object GifToAnimatedWebpConverter {
    fun convert(input: File, output: File) {
        require(input.isFile) { "GIF source does not exist" }
        try {
            convertWithLimits(
                input,
                output,
                AnimatedWebpPolicy.CANVAS_SIZE,
                AnimatedWebpPolicy.MAX_FRAMES,
            )
            if (output.isFile && output.length() > AnimatedWebpPolicy.MAX_OUTPUT_BYTES) {
                output.delete()
                convertWithLimits(
                    input,
                    output,
                    AnimatedWebpPolicy.FALLBACK_CANVAS_SIZE,
                    AnimatedWebpPolicy.FALLBACK_MAX_FRAMES,
                )
            }
        } catch (error: Exception) {
            output.delete()
            throw if (error is IOException) error else IOException(error.message ?: "Không tạo được WebP", error)
        }
        if (!output.isFile || output.length() <= 0L) {
            output.delete()
            throw IOException("WebP encoder did not produce a file")
        }
        if (output.length() > AnimatedWebpPolicy.MAX_OUTPUT_BYTES) {
            output.delete()
            throw IOException("Bản sticker WebP vượt giới hạn kích thước")
        }
    }

    private fun convertWithLimits(input: File, output: File, canvasSize: Int, maxFrames: Int) {
        val deadlineNanos = System.nanoTime() + AnimatedWebpPolicy.ENCODE_DEADLINE_MS * 1_000_000L
        val movie = decodeGif(input)
        val sourceWidth = movie.width()
        val sourceHeight = movie.height()
        if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("GIF dimensions are invalid")
        val duration = movie.duration().coerceAtLeast(0)
        val timestamps = AnimatedWebpPolicy.sampleTimestamps(duration, maxFrames)
        val layout = layout(sourceWidth, sourceHeight, canvasSize)
        val bitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val frames = ArrayList<AnimatedWebpFrame>(timestamps.size)
        try {
            timestamps.forEachIndexed { index, timeMs ->
                ensureActive(deadlineNanos)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.save()
                canvas.translate(layout.left.toFloat(), layout.top.toFloat())
                val scale = min(
                    layout.contentWidth.toFloat() / sourceWidth,
                    layout.contentHeight.toFloat() / sourceHeight,
                )
                canvas.scale(scale, scale)
                movie.setTime(timeMs)
                movie.draw(canvas, 0f, 0f, paint)
                canvas.restore()
                val still = compressStillWebp(bitmap)
                frames += AnimatedWebpFrame(
                    payload = AnimatedWebpMuxer.framePayloadFromStillWebp(still),
                    durationMs = AnimatedWebpPolicy.frameDurationMs(timestamps, index, duration),
                    width = canvasSize,
                    height = canvasSize,
                )
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val muxed = AnimatedWebpMuxer.mux(canvasSize, canvasSize, frames)
        output.parentFile?.mkdirs()
        output.writeBytes(muxed)
    }

    private fun decodeGif(input: File): Movie {
        Movie.decodeFile(input.absolutePath)?.takeIf { it.width() > 0 && it.height() > 0 }?.let { return it }
        return input.inputStream().use { stream ->
            Movie.decodeStream(stream)?.takeIf { it.width() > 0 && it.height() > 0 }
        } ?: throw IOException("GIF decoder returned no frames")
    }

    private fun compressStillWebp(bitmap: Bitmap): ByteArray {
        val encoded = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, 100, encoded)) { "Không mã hóa được frame WebP" }
        val bytes = encoded.toByteArray()
        require(bytes.isNotEmpty()) { "Frame WebP rỗng" }
        return bytes
    }

    private fun layout(sourceWidth: Int, sourceHeight: Int, canvasSize: Int): HostShareCanvasLayout {
        val contentLimit = (canvasSize * AnimatedWebpPolicy.CONTENT_FRACTION).roundToInt().coerceAtLeast(1)
        val scale = min(
            1f,
            min(contentLimit.toFloat() / sourceWidth, contentLimit.toFloat() / sourceHeight),
        )
        val contentWidth = (sourceWidth * scale).roundToInt().coerceIn(1, contentLimit)
        val contentHeight = (sourceHeight * scale).roundToInt().coerceIn(1, contentLimit)
        return HostShareCanvasLayout(
            canvasSize = canvasSize,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            left = (canvasSize - contentWidth) / 2,
            top = (canvasSize - contentHeight) / 2,
        )
    }

    private fun ensureActive(deadlineNanos: Long) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Animated WebP conversion was canceled")
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw IOException("Animated WebP quá thời gian mã hóa")
        }
    }
}
