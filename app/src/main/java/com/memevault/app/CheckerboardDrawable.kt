package com.memevault.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.content.Context

/** Lightweight transparency backdrop for sticker previews. */
internal class CheckerboardDrawable(
    private val tilePx: Int,
    private val lightColor: Int = Color.rgb(247, 248, 252),
    private val darkColor: Int = Color.rgb(226, 230, 240),
) : Drawable() {
    constructor(tilePx: Int, context: Context) : this(
        tilePx,
        context.getColor(R.color.mv_checker_light),
        context.getColor(R.color.mv_checker_dark),
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val tile = tilePx.coerceAtLeast(4)
        val width = bounds.width()
        val height = bounds.height()
        var row = 0
        var y = 0
        while (y < height) {
            var column = 0
            var x = 0
            while (x < width) {
                paint.color = if ((row + column) % 2 == 0) lightColor else darkColor
                paint.alpha = alphaValue
                canvas.drawRect(x.toFloat(), y.toFloat(),
                    (x + tile).coerceAtMost(width).toFloat(),
                    (y + tile).coerceAtMost(height).toFloat(), paint)
                column++
                x += tile
            }
            row++
            y += tile
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity contract")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
