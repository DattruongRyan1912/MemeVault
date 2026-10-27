package com.memevault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Small dependency-free crop editor used by Sticker Studio. The selection is kept as normalized
 * coordinates so rotation and different screen sizes do not change the selected source region.
 */
class CropEditorView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val dimPaint = Paint().apply { color = Color.argb(155, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 91, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private var bitmap: Bitmap? = null
    private var selection = RectF(0f, 0f, 1f, 1f)
    private var activeHandle = HANDLE_NONE
    private var startX = 0f
    private var startY = 0f
    private val startSelection = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        selection = RectF(0f, 0f, 1f, 1f)
        invalidate()
    }

    fun setSelection(rect: CropRect?) {
        val image = bitmap ?: return
        val selected = rect?.takeIf { it.isValidFor(image.width, image.height) }
            ?: CropRect.full(image.width, image.height)
        val normalized = selected.normalized(image.width, image.height)
        selection = RectF(normalized.left, normalized.top, normalized.right, normalized.bottom)
        invalidate()
    }

    fun resetSelection() {
        selection = RectF(0f, 0f, 1f, 1f)
        invalidate()
    }

    fun selectedBitmapRect(): CropRect? {
        val image = bitmap ?: return null
        return NormalizedCrop(
            left = selection.left,
            top = selection.top,
            right = selection.right,
            bottom = selection.bottom,
        ).toPixelRect(image.width, image.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val content = contentRect(image)
        if (content.width() <= 0f || content.height() <= 0f) return
        canvas.drawBitmap(image, null, content, imagePaint)

        val selected = selectionInView(content)
        canvas.drawRect(0f, 0f, width.toFloat(), selected.top, dimPaint)
        canvas.drawRect(0f, selected.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selected.top, selected.left, selected.bottom, dimPaint)
        canvas.drawRect(selected.right, selected.top, width.toFloat(), selected.bottom, dimPaint)
        canvas.drawRect(selected, borderPaint)

        val handleRadius = dp(7f)
        listOf(
            selected.left to selected.top,
            selected.right to selected.top,
            selected.right to selected.bottom,
            selected.left to selected.bottom,
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadius, handlePaint)
            canvas.drawCircle(x, y, handleRadius, handleStrokePaint)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = CropEditorView::class.java.name
        info.isFocusable = true
        info.isClickable = true
        val rect = selectedBitmapRect()
        info.text = rect?.let { "${it.width} × ${it.height} px" }
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                ACTION_CROP_EXPAND,
                context.getString(R.string.sticker_crop_accessibility_expand),
            ),
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                ACTION_CROP_SHRINK,
                context.getString(R.string.sticker_crop_accessibility_shrink),
            ),
        )
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            ACTION_CROP_EXPAND -> adjustSelectionForAccessibility(-ACCESSIBILITY_STEP)
            ACTION_CROP_SHRINK -> adjustSelectionForAccessibility(ACCESSIBILITY_STEP)
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        val content = contentRect(image)
        if (content.width() <= 0f || content.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val selected = selectionInView(content)
                activeHandle = hitHandle(event.x, event.y, selected)
                if (activeHandle == HANDLE_NONE && !selected.contains(event.x, event.y)) return false
                startX = event.x
                startY = event.y
                startSelection.set(selection)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == HANDLE_NONE) return false
                val dx = (event.x - startX) / content.width()
                val dy = (event.y - startY) / content.height()
                selection = adjustedSelection(dx, dy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle == HANDLE_NONE) return false
                activeHandle = HANDLE_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun adjustedSelection(dx: Float, dy: Float): RectF {
        val next = RectF(startSelection)
        val minSize = MIN_SELECTION_FRACTION
        when (activeHandle) {
            HANDLE_MOVE -> {
                var offsetX = dx
                var offsetY = dy
                if (next.left + offsetX < 0f) offsetX = -next.left
                if (next.right + offsetX > 1f) offsetX = 1f - next.right
                if (next.top + offsetY < 0f) offsetY = -next.top
                if (next.bottom + offsetY > 1f) offsetY = 1f - next.bottom
                next.offset(offsetX, offsetY)
            }

            HANDLE_TOP_LEFT -> {
                next.left = (startSelection.left + dx).coerceIn(0f, startSelection.right - minSize)
                next.top = (startSelection.top + dy).coerceIn(0f, startSelection.bottom - minSize)
            }

            HANDLE_TOP_RIGHT -> {
                next.right = (startSelection.right + dx).coerceIn(startSelection.left + minSize, 1f)
                next.top = (startSelection.top + dy).coerceIn(0f, startSelection.bottom - minSize)
            }

            HANDLE_BOTTOM_RIGHT -> {
                next.right = (startSelection.right + dx).coerceIn(startSelection.left + minSize, 1f)
                next.bottom = (startSelection.bottom + dy).coerceIn(startSelection.top + minSize, 1f)
            }

            HANDLE_BOTTOM_LEFT -> {
                next.left = (startSelection.left + dx).coerceIn(0f, startSelection.right - minSize)
                next.bottom = (startSelection.bottom + dy).coerceIn(startSelection.top + minSize, 1f)
            }
        }
        return next
    }

    private fun adjustSelectionForAccessibility(inset: Float): Boolean {
        val next = RectF(
            (selection.left + inset).coerceIn(0f, 1f),
            (selection.top + inset).coerceIn(0f, 1f),
            (selection.right - inset).coerceIn(0f, 1f),
            (selection.bottom - inset).coerceIn(0f, 1f),
        )
        if (next.width() < MIN_SELECTION_FRACTION || next.height() < MIN_SELECTION_FRACTION) {
            return false
        }
        selection = next
        invalidate()
        return true
    }

    private fun hitHandle(x: Float, y: Float, selected: RectF): Int {
        val radius = dp(22f)
        return when {
            distanceSquared(x, y, selected.left, selected.top) <= radius * radius -> HANDLE_TOP_LEFT
            distanceSquared(x, y, selected.right, selected.top) <= radius * radius -> HANDLE_TOP_RIGHT
            distanceSquared(x, y, selected.right, selected.bottom) <= radius * radius -> HANDLE_BOTTOM_RIGHT
            distanceSquared(x, y, selected.left, selected.bottom) <= radius * radius -> HANDLE_BOTTOM_LEFT
            selected.contains(x, y) -> HANDLE_MOVE
            else -> HANDLE_NONE
        }
    }

    private fun selectionInView(content: RectF): RectF = RectF(
        content.left + selection.left * content.width(),
        content.top + selection.top * content.height(),
        content.left + selection.right * content.width(),
        content.top + selection.bottom * content.height(),
    )

    private fun contentRect(image: Bitmap): RectF {
        if (width <= 0 || height <= 0) return RectF()
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val contentWidth = image.width * scale
        val contentHeight = image.height * scale
        return RectF(
            (width - contentWidth) / 2f,
            (height - contentHeight) / 2f,
            (width + contentWidth) / 2f,
            (height + contentHeight) / 2f,
        )
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MIN_SELECTION_FRACTION = 0.03f
        private const val HANDLE_NONE = 0
        private const val HANDLE_MOVE = 1
        private const val HANDLE_TOP_LEFT = 2
        private const val HANDLE_TOP_RIGHT = 3
        private const val HANDLE_BOTTOM_RIGHT = 4
        private const val HANDLE_BOTTOM_LEFT = 5
        private const val ACCESSIBILITY_STEP = 0.03f
        private const val ACTION_CROP_EXPAND = 0x01010001
        private const val ACTION_CROP_SHRINK = 0x01010002
    }
}
