package com.memevault.app

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Layout for the opaque image compatibility profile used by hosts that trim transparent
 * stickers and then enlarge the remaining pixels. The host still receives a normal image; this
 * policy only gives it a predictable square canvas and a little breathing room around the subject.
 */
data class HostShareCanvasLayout(
    val canvasSize: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val left: Int,
    val top: Int,
)

object HostShareCanvasPolicy {
    const val CANVAS_SIZE = 512
    const val CONTENT_FRACTION = 0.72f

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
