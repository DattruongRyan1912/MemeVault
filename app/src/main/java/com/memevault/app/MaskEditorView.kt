package com.memevault.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Keep/Erase surface over the Studio working bitmap. One finger paints; a second finger cancels
 * the stroke in progress and switches to pinch-zoom and pan until every finger is lifted.
 *
 * The cutout is an ALPHA_8 coverage bitmap drawn through a shader of the source, so a dab only
 * rewrites the alpha of its dirty rect. The source is also drawn faintly underneath so erased
 * areas stay visible as targets for Keep. Coverage uses the renderer's threshold mapping, so what
 * is painted here is what [StickerRenderer] will cut out.
 */
@SuppressLint("ViewConstructor")
internal class MaskEditorView(
    context: Context,
    private val source: Bitmap,
    val session: MaskBrushSession,
    threshold: Float,
) : View(context) {
    var mode: BrushMode = BrushMode.ERASE

    /** Brush radius on screen; converted to mask pixels through the current zoom. */
    var brushRadiusDp: Int = DEFAULT_RADIUS_DP
        set(value) {
            field = value
            previewCursor()
        }

    /** Called after every committed stroke, undo or redo. */
    var onHistoryChanged: (() -> Unit)? = null

    private val coverage = Bitmap.createBitmap(session.width, session.height, Bitmap.Config.ALPHA_8)
    private val levelAlpha = IntArray(256) { StickerRenderingPolicy.alphaFor(it / 255f, threshold) }
    private var buffer = IntArray(0)
    private val cutoutPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = GHOST_ALPHA }
    private val cursorOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = context.dp(3).toFloat()
        alpha = 160
    }
    private val cursorInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = context.dp(1).toFloat().coerceAtLeast(1.5f)
    }
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private val point = FloatArray(2)
    private val imageBounds = RectF()
    private var fitScale = 1f
    private var touchMode = TOUCH_NONE
    private var showCursor = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val hideCursor = Runnable {
        if (touchMode != TOUCH_PAINT) {
            showCursor = false
            invalidate()
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (touchMode != TOUCH_GESTURE) return false
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    init {
        isClickable = true
        refreshCoverage(MaskRect(0, 0, session.width, session.height))
    }

    fun undo() {
        session.undo()?.let(::refresh) ?: return
        onHistoryChanged?.invoke()
    }

    fun redo() {
        session.redo()?.let(::refresh) ?: return
        onHistoryChanged?.invoke()
    }

    /** Frees the coverage bitmap; the view must not be drawn afterwards. */
    fun release() {
        removeCallbacks(hideCursor)
        if (!coverage.isRecycled) coverage.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val padding = context.dp(12).toFloat()
        fitScale = min((w - 2 * padding) / session.width, (h - 2 * padding) / session.height)
            .coerceAtLeast(MIN_FIT_SCALE)
        viewMatrix.setScale(fitScale, fitScale)
        viewMatrix.postTranslate(
            (w - session.width * fitScale) / 2f,
            (h - session.height * fitScale) / 2f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (coverage.isRecycled || source.isRecycled) return
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(source, 0f, 0f, ghostPaint)
        canvas.drawBitmap(coverage, 0f, 0f, cutoutPaint)
        canvas.restore()
        if (showCursor) {
            val radius = context.dp(brushRadiusDp).toFloat()
            canvas.drawCircle(cursorX, cursorY, radius, cursorOuter)
            canvas.drawCircle(cursorX, cursorY, radius, cursorInner)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(hideCursor)
                touchMode = TOUCH_PAINT
                moveCursor(event.x, event.y)
                val (x, y) = toImage(event.x, event.y)
                session.beginStroke(mode, x, y, imageRadius())?.let(::refresh)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touchMode == TOUCH_PAINT) session.cancelStroke()?.let(::refresh)
                touchMode = TOUCH_GESTURE
                showCursor = false
                focusOf(event, skipIndex = -1)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (touchMode) {
                TOUCH_PAINT -> {
                    val radius = imageRadius()
                    var dirty: MaskRect? = null
                    for (index in 0 until event.historySize) {
                        val (x, y) = toImage(event.getHistoricalX(index), event.getHistoricalY(index))
                        session.strokeTo(x, y, radius)?.let { dirty = it.union(dirty) }
                    }
                    val (x, y) = toImage(event.x, event.y)
                    session.strokeTo(x, y, radius)?.let { dirty = it.union(dirty) }
                    moveCursor(event.x, event.y)
                    dirty?.let(::refresh)
                }
                TOUCH_GESTURE -> if (event.pointerCount >= 2) {
                    val previousX = lastFocusX
                    val previousY = lastFocusY
                    focusOf(event, skipIndex = -1)
                    viewMatrix.postTranslate(lastFocusX - previousX, lastFocusY - previousY)
                    clampPan()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (touchMode == TOUCH_GESTURE) {
                focusOf(event, skipIndex = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                if (touchMode == TOUCH_PAINT && session.endStroke()) onHistoryChanged?.invoke()
                touchMode = TOUCH_NONE
                showCursor = false
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchMode == TOUCH_PAINT) session.cancelStroke()?.let(::refresh)
                touchMode = TOUCH_NONE
                showCursor = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(hideCursor)
        super.onDetachedFromWindow()
    }

    private fun refresh(rect: MaskRect) {
        refreshCoverage(rect)
        invalidate()
    }

    private fun refreshCoverage(rect: MaskRect) {
        if (coverage.isRecycled || rect.width <= 0 || rect.height <= 0) return
        // Strips bound the temporary buffer even when a full 2048px frame is refreshed.
        val stripRows = max(1, min(rect.height, STRIP_PIXELS / rect.width))
        var top = rect.top
        while (top < rect.bottom) {
            val bottom = min(rect.bottom, top + stripRows)
            val strip = MaskRect(rect.left, top, rect.right, bottom)
            val count = strip.width * strip.height
            if (buffer.size < count) buffer = IntArray(count)
            session.readLevels(strip, buffer)
            for (index in 0 until count) buffer[index] = levelAlpha[buffer[index]] shl 24
            coverage.setPixels(buffer, 0, strip.width, strip.left, strip.top, strip.width, strip.height)
            top = bottom
        }
    }

    private fun currentScale(): Float {
        viewMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun imageRadius(): Float = context.dp(brushRadiusDp) / currentScale()

    private fun toImage(x: Float, y: Float): Pair<Float, Float> {
        viewMatrix.invert(inverse)
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)
        return point[0] to point[1]
    }

    private fun moveCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        showCursor = true
    }

    /** Shows the brush size at the centre for a moment while the size slider moves. */
    private fun previewCursor() {
        if (touchMode == TOUCH_PAINT) return
        moveCursor(width / 2f, height / 2f)
        invalidate()
        removeCallbacks(hideCursor)
        postDelayed(hideCursor, CURSOR_PREVIEW_MS)
    }

    private fun focusOf(event: MotionEvent, skipIndex: Int) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == skipIndex) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            count++
        }
        if (count == 0) return
        lastFocusX = sumX / count
        lastFocusY = sumY / count
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val current = currentScale()
        val target = (current * factor).coerceIn(fitScale, fitScale * MAX_ZOOM)
        val applied = target / current
        viewMatrix.postScale(applied, applied, focusX, focusY)
        clampPan()
        invalidate()
    }

    /** Keeps the image centred while it fits, and edge-to-edge once it is larger than the view. */
    private fun clampPan() {
        imageBounds.set(0f, 0f, session.width.toFloat(), session.height.toFloat())
        viewMatrix.mapRect(imageBounds)
        val dx = when {
            imageBounds.width() <= width -> width / 2f - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        val dy = when {
            imageBounds.height() <= height -> height / 2f - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
        viewMatrix.postTranslate(dx, dy)
    }

    companion object {
        const val MIN_RADIUS_DP = 4
        const val MAX_RADIUS_DP = 48
        const val DEFAULT_RADIUS_DP = 16
        private const val MAX_ZOOM = 8f
        private const val MIN_FIT_SCALE = 0.01f
        private const val GHOST_ALPHA = 70
        private const val STRIP_PIXELS = 64 * 1024
        private const val CURSOR_PREVIEW_MS = 700L
        private const val TOUCH_NONE = 0
        private const val TOUCH_PAINT = 1
        private const val TOUCH_GESTURE = 2
    }
}
