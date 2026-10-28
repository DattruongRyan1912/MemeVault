package com.memevault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class BackgroundRemovalResult(
    val confidenceMask: FloatArray,
    val engineId: String,
    val engineVersion: String,
    val elapsedMs: Long,
)

class BackgroundRemovalModelException(cause: Throwable) :
    IllegalStateException("Model tách nền chưa sẵn sàng", cause)

interface BackgroundRemovalEngine : AutoCloseable {
    fun segment(bitmap: Bitmap): BackgroundRemovalResult
}

/**
 * ML Kit adapter for still-image subject segmentation. The app owns the bitmap and mask
 * composition; this class only translates the vendor result into a stable domain result.
 */
class MlKitBackgroundRemovalEngine(context: Context) : BackgroundRemovalEngine {
    private val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build(),
    )

    override fun segment(bitmap: Bitmap): BackgroundRemovalResult {
        require(bitmap.width > 0 && bitmap.height > 0) { "Ảnh làm việc không hợp lệ" }
        val startedAt = System.nanoTime()
        runCatching {
            Tasks.await(segmenter.getInitTask(), SEGMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrElse { throw BackgroundRemovalModelException(it) }
        val result = Tasks.await(
            segmenter.process(InputImage.fromBitmap(bitmap, 0)),
            SEGMENT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        val buffer = result.foregroundConfidenceMask
            ?: throw IllegalStateException("Bộ tách nền không trả về mask")
        buffer.rewind()
        val expectedSize = bitmap.width * bitmap.height
        if (buffer.remaining() != expectedSize) {
            throw IllegalStateException("Kích thước mask không khớp ảnh")
        }
        val mask = FloatArray(expectedSize)
        buffer.get(mask)
        return BackgroundRemovalResult(
            confidenceMask = mask,
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    override fun close() {
        segmenter.close()
    }

    companion object {
        const val ENGINE_ID = "mlkit-subject-segmentation"
        const val ENGINE_VERSION = "16.0.0-beta1"
        private const val SEGMENT_TIMEOUT_SECONDS = 90L
    }
}

object StickerImageLoader {
    fun decode(file: File, maxDimension: Int = MAX_WORK_DIMENSION): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return applyExifOrientation(file, bitmap)
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private const val MAX_WORK_DIMENSION = 2048
}

object StickerRenderingPolicy {
    fun alphaFor(confidence: Float, threshold: Float): Int {
        if (!confidence.isFinite()) return 0
        val clampedThreshold = threshold.coerceIn(0f, 0.99f)
        if (confidence <= clampedThreshold) return 0
        return (((confidence - clampedThreshold) / (1f - clampedThreshold)) * 255f)
            .roundToInt()
            .coerceIn(0, 255)
    }
}

/**
 * User-facing outline settings are kept as a small, deterministic step scale instead of raw
 * pixels. The working bitmap can be sampled to different dimensions, so each step maps to a
 * stable fraction of the longest edge and still looks consistent across source resolutions.
 */
object StickerBorderPolicy {
    const val MIN_THICKNESS = 0
    const val MAX_THICKNESS = 12
    const val DEFAULT_THICKNESS = 4

    private const val FRACTION_PER_STEP = 0.0015f

    fun clampThickness(value: Int): Int = value.coerceIn(MIN_THICKNESS, MAX_THICKNESS)

    fun radiusPx(maxDimension: Int, thickness: Int): Int {
        if (maxDimension <= 0) return 0
        val clamped = clampThickness(thickness)
        if (clamped == MIN_THICKNESS) return 0
        return (maxDimension * clamped * FRACTION_PER_STEP)
            .roundToInt()
            .coerceAtLeast(1)
    }
}

object StickerRenderer {
    const val DEFAULT_PADDING_FRACTION = 0.05f

    fun render(
        source: Bitmap,
        confidenceMask: FloatArray,
        threshold: Float,
        borderColor: Int? = null,
        borderThickness: Int = StickerBorderPolicy.MIN_THICKNESS,
        paddingFraction: Float = DEFAULT_PADDING_FRACTION,
    ): Bitmap {
        val pixelCount = source.width * source.height
        require(confidenceMask.size == pixelCount) { "Mask không khớp kích thước ảnh" }
        // Crop before outline work so the expensive alpha buffers follow the subject bounds
        // rather than the full source canvas whenever possible.
        val masked = maskAndCrop(source, confidenceMask, threshold, paddingFraction)
        return try {
            // Resolve the radius from the same source dimensions shown by the UI. Applying it
            // after crop would make the same slider value vary with subject position and crop size.
            val borderRadius = StickerBorderPolicy.radiusPx(
                max(source.width, source.height),
                borderThickness,
            )
            val bordered = applyBorder(masked, borderColor, borderRadius)
            if (bordered !== masked) masked.recycle()
            bordered
        } catch (error: Throwable) {
            if (!masked.isRecycled) masked.recycle()
            throw error
        }
    }

    @Suppress("UseKtx")
    private fun maskAndCrop(
        source: Bitmap,
        confidenceMask: FloatArray,
        threshold: Float,
        paddingFraction: Float,
    ): Bitmap {
        // Scan rows instead of materializing a second full-frame ARGB buffer. The crop pass below
        // writes directly into the output bitmap, keeping peak memory bounded on low-RAM phones.
        val sourceRow = IntArray(source.width)
        var left = source.width
        var top = source.height
        var right = -1
        var bottom = -1
        for (y in 0 until source.height) {
            checkNotInterrupted()
            source.getPixels(sourceRow, 0, source.width, 0, y, source.width, 1)
            val maskOffset = y * source.width
            for (x in 0 until source.width) {
                val sourcePixel = sourceRow[x]
                val alpha = maskedAlpha(sourcePixel, confidenceMask[maskOffset + x], threshold)
                if (alpha > MIN_VISIBLE_ALPHA) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) {
            throw IllegalStateException("Không tìm thấy chủ thể trong ảnh")
        }

        val padding = (max(source.width, source.height) * paddingFraction.coerceIn(0f, 0.2f))
            .roundToInt()
            .coerceAtLeast(2)
        val cropLeft = (left - padding).coerceAtLeast(0)
        val cropTop = (top - padding).coerceAtLeast(0)
        val cropRight = (right + padding).coerceAtMost(source.width - 1)
        val cropBottom = (bottom + padding).coerceAtMost(source.height - 1)
        val cropWidth = cropRight - cropLeft + 1
        val cropHeight = cropBottom - cropTop + 1
        val cropped = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        return try {
            val croppedRow = IntArray(cropWidth)
            for (outputY in 0 until cropHeight) {
                checkNotInterrupted()
                val sourceY = cropTop + outputY
                source.getPixels(sourceRow, 0, source.width, 0, sourceY, source.width, 1)
                val maskOffset = sourceY * source.width + cropLeft
                for (x in 0 until cropWidth) {
                    croppedRow[x] = maskedPixel(
                        sourceRow[cropLeft + x],
                        confidenceMask[maskOffset + x],
                        threshold,
                    )
                }
                cropped.setPixels(croppedRow, 0, cropWidth, 0, outputY, cropWidth, 1)
            }
            cropped
        } catch (error: Throwable) {
            if (!cropped.isRecycled) cropped.recycle()
            throw error
        }
    }

    private fun maskedPixel(sourcePixel: Int, confidence: Float, threshold: Float): Int {
        val alpha = maskedAlpha(sourcePixel, confidence, threshold)
        return Color.argb(
            alpha,
            Color.red(sourcePixel),
            Color.green(sourcePixel),
            Color.blue(sourcePixel),
        )
    }

    private fun maskedAlpha(sourcePixel: Int, confidence: Float, threshold: Float): Int {
        val sourceAlpha = Color.alpha(sourcePixel)
        val maskAlpha = StickerRenderingPolicy.alphaFor(confidence, threshold)
        return (sourceAlpha * maskAlpha / 255).coerceIn(0, 255)
    }

    /**
     * Paints a solid outline behind the masked subject. A max filter dilates the alpha mask in
     * two linear passes, so a 2048px working image remains bounded and never needs one operation
     * per pixel per radius. The source pixels are then composited over that outline, preserving
     * antialiased subject edges.
     */
    @Suppress("UseKtx")
    private fun applyBorder(bitmap: Bitmap, borderColor: Int?, radius: Int): Bitmap {
        val color = borderColor ?: return bitmap
        if (radius <= 0) return bitmap

        // Expand before dilation so an outline around a subject touching the crop edge is never
        // clipped. The crop already contains the normal editor padding.
        val expandedWidth = bitmap.width + radius * 2
        val expandedHeight = bitmap.height + radius * 2
        val expandedPixelCount = expandedWidth * expandedHeight
        val sourcePixels = IntArray(expandedPixelCount)
        bitmap.getPixels(
            sourcePixels,
            radius * expandedWidth + radius,
            expandedWidth,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        val sourceAlpha = ByteArray(expandedPixelCount) { Color.alpha(sourcePixels[it]).toByte() }
        val dilatedAlpha = dilateAlpha(sourceAlpha, expandedWidth, expandedHeight, radius)
        val colorAlpha = Color.alpha(color)
        for (index in sourcePixels.indices) {
            if (index and CHECK_INTERRUPT_MASK == 0) checkNotInterrupted()
            val outlineAlpha = ((dilatedAlpha[index].toInt() and 0xFF) * colorAlpha / 255)
                .coerceIn(0, 255)
            sourcePixels[index] = compositeOverOutline(sourcePixels[index], color, outlineAlpha)
        }
        return Bitmap.createBitmap(
            sourcePixels,
            expandedWidth,
            expandedHeight,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun compositeOverOutline(sourcePixel: Int, outlineColor: Int, outlineAlpha: Int): Int {
        val sourceAlpha = Color.alpha(sourcePixel)
        if (sourceAlpha == 255 || outlineAlpha == 0) return sourcePixel
        val underAlpha = outlineAlpha * (255 - sourceAlpha) / 255
        val outputAlpha = (sourceAlpha + underAlpha).coerceIn(0, 255)
        if (outputAlpha == 0) return Color.TRANSPARENT
        val sourceWeight = sourceAlpha
        val outlineWeight = underAlpha
        return Color.argb(
            outputAlpha,
            ((Color.red(sourcePixel) * sourceWeight + Color.red(outlineColor) * outlineWeight) /
                outputAlpha).coerceIn(0, 255),
            ((Color.green(sourcePixel) * sourceWeight + Color.green(outlineColor) * outlineWeight) /
                outputAlpha).coerceIn(0, 255),
            ((Color.blue(sourcePixel) * sourceWeight + Color.blue(outlineColor) * outlineWeight) /
                outputAlpha).coerceIn(0, 255),
        )
    }

    private fun dilateAlpha(alpha: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        if (radius <= 0) return alpha.copyOf()
        val horizontal = ByteArray(alpha.size)
        val output = ByteArray(alpha.size)
        val maxLineLength = max(width, height)
        val inputLine = ByteArray(maxLineLength)
        val outputLine = ByteArray(maxLineLength)
        val deque = IntArray(maxLineLength)

        for (y in 0 until height) {
            checkNotInterrupted()
            val rowOffset = y * width
            for (x in 0 until width) inputLine[x] = alpha[rowOffset + x]
            maxFilterLine(inputLine, width, radius, outputLine, deque)
            for (x in 0 until width) horizontal[rowOffset + x] = outputLine[x]
        }

        for (x in 0 until width) {
            checkNotInterrupted()
            for (y in 0 until height) inputLine[y] = horizontal[y * width + x]
            maxFilterLine(inputLine, height, radius, outputLine, deque)
            for (y in 0 until height) output[y * width + x] = outputLine[y]
        }
        return output
    }

    /** Inclusive max filter over [center - radius, center + radius] in O(length). */
    private fun maxFilterLine(
        input: ByteArray,
        length: Int,
        radius: Int,
        output: ByteArray,
        deque: IntArray,
    ) {
        var head = 0
        var tail = 0
        for (index in 0 until length + radius) {
            if (index < length) {
                val value = input[index].toInt() and 0xFF
                while (tail > head &&
                    (input[deque[tail - 1]].toInt() and 0xFF) <= value
                ) {
                    tail--
                }
                deque[tail++] = index
            }
            val center = index - radius
            if (center >= 0) {
                val minimum = center - radius
                while (tail > head && deque[head] < minimum) head++
                output[center] = input[deque[head]]
            }
        }
    }

    private fun checkNotInterrupted() {
        // Clear the flag before leaving so a reused single-thread executor can run the next
        // render normally after Future.cancel(true).
        if (Thread.interrupted()) {
            throw InterruptedException("Sticker render canceled")
        }
    }

    private const val CHECK_INTERRUPT_MASK = 0x3FFF

    internal const val MIN_VISIBLE_ALPHA = 8
}
