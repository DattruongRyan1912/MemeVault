package com.memevault.app

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry for the experimental iPhone-like sticker raster. This mirrors the observable image
 * shape (transparent square PNG with a small inset) without copying Apple's private metadata.
 */
object IosStickerCanvasPolicy {
    const val CANVAS_SIZE = 320
    const val CONTENT_FRACTION = 0.94f

    fun layout(sourceWidth: Int, sourceHeight: Int): HostShareCanvasLayout {
        require(sourceWidth > 0) { "sourceWidth must be positive" }
        require(sourceHeight > 0) { "sourceHeight must be positive" }

        val contentLimit = (CANVAS_SIZE * CONTENT_FRACTION).roundToInt()
        val scale = min(
            1f,
            min(
                contentLimit.toFloat() / sourceWidth,
                contentLimit.toFloat() / sourceHeight,
            ),
        )
        val contentWidth = (sourceWidth * scale).roundToInt().coerceIn(1, contentLimit)
        val contentHeight = (sourceHeight * scale).roundToInt().coerceIn(1, contentLimit)
        return HostShareCanvasLayout(
            canvasSize = CANVAS_SIZE,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            left = (CANVAS_SIZE - contentWidth) / 2,
            top = (CANVAS_SIZE - contentHeight) / 2,
        )
    }
}
