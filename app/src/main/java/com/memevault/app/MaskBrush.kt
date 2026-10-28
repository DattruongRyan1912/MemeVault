package com.memevault.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixel rectangle in mask coordinates; right and bottom are exclusive. */
internal data class MaskRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun union(other: MaskRect?): MaskRect = if (other == null) this else MaskRect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )
}

internal enum class BrushMode {
    /** Paint the subject back in. */
    KEEP,

    /** Paint the background out. */
    ERASE,
}

/**
 * Editable copy of a sticker confidence mask for Keep/Erase brushing.
 *
 * Values are held as the same 0..255 levels [MaskCodec] persists, so undo patches are lossless
 * and the saved mask is exactly what the user saw. A stroke is a run of soft round dabs: Keep
 * raises pixels towards 255 and Erase lowers them towards 0, so painted pixels end fully on or
 * off and no longer move with the threshold slider.
 *
 * Undo stores one dirty-rect patch (before and after bytes) per stroke and is capped by bytes,
 * not by count: a stroke across a 2048px image costs 8 MB, a dab-sized touch-up a few KB.
 */
internal class MaskBrushSession(
    val width: Int,
    val height: Int,
    initial: ByteArray,
    private val historyBudgetBytes: Long = DEFAULT_HISTORY_BUDGET_BYTES,
) {
    private val mask = initial.copyOf()

    /** The mask as of the last finished stroke; source of truth for cancel and undo patches. */
    private val committed = initial.copyOf()
    private val undoStack = ArrayDeque<Patch>()
    private val redoStack = ArrayDeque<Patch>()
    private var historyBytes = 0L
    private var trimmedHistory = false
    private var strokeMode: BrushMode? = null
    private var strokeRect: MaskRect? = null
    private var lastX = 0f
    private var lastY = 0f

    init {
        require(width > 0 && height > 0) { "Kích thước mask không hợp lệ" }
        require(initial.size == width * height) { "Mask không khớp kích thước ảnh" }
    }

    val canUndo: Boolean get() = strokeMode == null && undoStack.isNotEmpty()
    val canRedo: Boolean get() = strokeMode == null && redoStack.isNotEmpty()
    val strokeActive: Boolean get() = strokeMode != null

    /** True when the mask differs from the one the session started with. */
    val hasEdits: Boolean get() = undoStack.isNotEmpty() || trimmedHistory

    fun level(x: Int, y: Int): Int = mask[y * width + x].toInt() and 0xFF

    /** Copies the levels of [rect] into [out], row-major. */
    fun readLevels(rect: MaskRect, out: IntArray) {
        var index = 0
        for (y in rect.top until rect.bottom) {
            val row = y * width
            for (x in rect.left until rect.right) out[index++] = mask[row + x].toInt() and 0xFF
        }
    }

    fun snapshot(): ByteArray = mask.copyOf()

    fun toConfidence(): FloatArray = FloatArray(mask.size) { (mask[it].toInt() and 0xFF) / 255f }

    /** Mirrors the renderer: is any pixel still visible enough to define the sticker bounds? */
    fun hasVisibleSubject(threshold: Float): Boolean {
        val levelAlpha = IntArray(256) { StickerRenderingPolicy.alphaFor(it / 255f, threshold) }
        return mask.any { levelAlpha[it.toInt() and 0xFF] > StickerRenderer.MIN_VISIBLE_ALPHA }
    }

    fun beginStroke(mode: BrushMode, x: Float, y: Float, radius: Float): MaskRect? {
        if (strokeMode != null) cancelStroke()
        strokeMode = mode
        strokeRect = null
        lastX = x
        lastY = y
        return dab(mode, x, y, radius)?.also { strokeRect = it }
    }

    /** Extends the stroke to (x, y), spacing dabs so fast swipes leave no gaps. */
    fun strokeTo(x: Float, y: Float, radius: Float): MaskRect? {
        val mode = strokeMode ?: return null
        val distance = hypot(x - lastX, y - lastY)
        val spacing = max(1f, radius * DAB_SPACING)
        val steps = ceil(distance / spacing).toInt()
        var dirty: MaskRect? = null
        for (step in 1..steps) {
            val t = step / steps.toFloat()
            dab(mode, lastX + (x - lastX) * t, lastY + (y - lastY) * t, radius)?.let { dirty = it.union(dirty) }
        }
        lastX = x
        lastY = y
        dirty?.let { strokeRect = it.union(strokeRect) }
        return dirty
    }

    /** Commits the stroke as one undo step; returns false when it changed nothing. */
    fun endStroke(): Boolean {
        val rect = strokeRect
        strokeMode = null
        strokeRect = null
        if (rect == null) return false
        val before = extract(committed, rect)
        val after = extract(mask, rect)
        if (before.contentEquals(after)) return false
        write(committed, rect, after)
        redoStack.forEach { historyBytes -= it.bytes }
        redoStack.clear()
        push(undoStack, Patch(rect, before, after))
        return true
    }

    /** Reverts the stroke in progress (a second finger landed); returns the area to repaint. */
    fun cancelStroke(): MaskRect? {
        val rect = strokeRect
        strokeMode = null
        strokeRect = null
        if (rect != null) write(mask, rect, extract(committed, rect))
        return rect
    }

    fun undo(): MaskRect? {
        if (!canUndo) return null
        val patch = undoStack.removeLast()
        historyBytes -= patch.bytes
        write(mask, patch.rect, patch.before)
        write(committed, patch.rect, patch.before)
        push(redoStack, patch)
        return patch.rect
    }

    fun redo(): MaskRect? {
        if (!canRedo) return null
        val patch = redoStack.removeLast()
        historyBytes -= patch.bytes
        write(mask, patch.rect, patch.after)
        write(committed, patch.rect, patch.after)
        push(undoStack, patch)
        return patch.rect
    }

    private fun push(stack: ArrayDeque<Patch>, patch: Patch) {
        stack.addLast(patch)
        historyBytes += patch.bytes
        // Drop the oldest undo steps first; the newest step is always kept.
        while (historyBytes > historyBudgetBytes && undoStack.size + redoStack.size > 1) {
            val dropped = if (undoStack.size > (if (stack === undoStack) 1 else 0)) {
                trimmedHistory = true
                undoStack.removeFirst()
            } else {
                redoStack.removeFirst()
            }
            historyBytes -= dropped.bytes
        }
    }

    private fun dab(mode: BrushMode, cx: Float, cy: Float, radius: Float): MaskRect? {
        val r = max(radius, MIN_RADIUS)
        val left = max(0, floor(cx - r).toInt())
        val top = max(0, floor(cy - r).toInt())
        val right = min(width, ceil(cx + r).toInt())
        val bottom = min(height, ceil(cy + r).toInt())
        if (left >= right || top >= bottom) return null
        val hard = r * HARDNESS
        val soft = r - hard
        for (y in top until bottom) {
            val dy = y + 0.5f - cy
            val row = y * width
            for (x in left until right) {
                val dx = x + 0.5f - cx
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= r) continue
                val strength = if (distance <= hard || soft <= 0f) 1f else (r - distance) / soft
                val amount = (strength * 255f + 0.5f).toInt().coerceIn(0, 255)
                val index = row + x
                val current = mask[index].toInt() and 0xFF
                val next = if (mode == BrushMode.KEEP) max(current, amount) else min(current, 255 - amount)
                if (next != current) mask[index] = next.toByte()
            }
        }
        return MaskRect(left, top, right, bottom)
    }

    private fun extract(source: ByteArray, rect: MaskRect): ByteArray {
        val out = ByteArray(rect.width * rect.height)
        for (row in 0 until rect.height) {
            System.arraycopy(source, (rect.top + row) * width + rect.left, out, row * rect.width, rect.width)
        }
        return out
    }

    private fun write(target: ByteArray, rect: MaskRect, values: ByteArray) {
        for (row in 0 until rect.height) {
            System.arraycopy(values, row * rect.width, target, (rect.top + row) * width + rect.left, rect.width)
        }
    }

    private class Patch(val rect: MaskRect, val before: ByteArray, val after: ByteArray) {
        val bytes: Long get() = before.size.toLong() + after.size
    }

    companion object {
        /** 24 MB of undo patches: three full-frame strokes at 2048px, or hundreds of touch-ups. */
        const val DEFAULT_HISTORY_BUDGET_BYTES = 24L * 1024L * 1024L

        /** Inner fraction of the radius painted at full strength; the rest fades out. */
        private const val HARDNESS = 0.6f
        private const val DAB_SPACING = 0.25f
        private const val MIN_RADIUS = 0.5f

        /** Same quantisation as [MaskCodec.write], so a session round-trips to identical PNG levels. */
        fun quantize(confidence: FloatArray): ByteArray = ByteArray(confidence.size) { index ->
            val value = confidence[index].takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            (value * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }
}
