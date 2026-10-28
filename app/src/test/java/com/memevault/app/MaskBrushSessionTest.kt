package com.memevault.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskBrushSessionTest {
    private fun session(level: Int = 128, size: Int = 40, budget: Long = MaskBrushSession.DEFAULT_HISTORY_BUDGET_BYTES) =
        MaskBrushSession(size, size, ByteArray(size * size) { level.toByte() }, budget)

    @Test
    fun keepPaintsTheSubjectInAndLeavesTheRestAlone() {
        val brush = session(level = 0)
        brush.beginStroke(BrushMode.KEEP, 20f, 20f, 6f)
        assertTrue(brush.endStroke())
        assertEquals(255, brush.level(20, 20))
        assertEquals(0, brush.level(2, 2))
        // The soft rim fades between the hard core and the radius.
        val rim = brush.level(25, 20)
        assertTrue("rim $rim", rim in 1..254)
    }

    @Test
    fun eraseOnlyLowersAndKeepOnlyRaises() {
        val brush = session(level = 200)
        brush.beginStroke(BrushMode.ERASE, 20f, 20f, 6f)
        brush.endStroke()
        assertEquals(0, brush.level(20, 20))
        // The rim of a Keep dab is weaker than 200 here; Keep never lowers a pixel.
        brush.beginStroke(BrushMode.KEEP, 2f, 2f, 1f)
        brush.endStroke()
        assertEquals(200, brush.level(1, 1))
        assertEquals(200, brush.level(35, 35))
    }

    @Test
    fun undoAndRedoAreExact() {
        val brush = session()
        val start = brush.snapshot()
        brush.beginStroke(BrushMode.ERASE, 10f, 10f, 5f)
        brush.strokeTo(30f, 30f, 5f)
        brush.endStroke()
        val afterStroke = brush.snapshot()
        assertNotNull(brush.undo())
        assertArrayEquals(start, brush.snapshot())
        assertFalse(brush.hasEdits)
        assertNotNull(brush.redo())
        assertArrayEquals(afterStroke, brush.snapshot())
        assertTrue(brush.hasEdits)
    }

    @Test
    fun aSwipeLeavesNoGapsBetweenEvents() {
        val brush = session(level = 0)
        brush.beginStroke(BrushMode.KEEP, 2f, 20f, 3f)
        brush.strokeTo(38f, 20f, 3f)
        brush.endStroke()
        for (x in 2..37) assertEquals("x=$x", 255, brush.level(x, 20))
    }

    @Test
    fun cancelRevertsTheStrokeInProgress() {
        val brush = session()
        val start = brush.snapshot()
        brush.beginStroke(BrushMode.KEEP, 20f, 20f, 8f)
        brush.strokeTo(30f, 20f, 8f)
        assertNotNull(brush.cancelStroke())
        assertArrayEquals(start, brush.snapshot())
        assertFalse(brush.canUndo)
    }

    @Test
    fun aNewStrokeClearsRedo() {
        val brush = session()
        brush.beginStroke(BrushMode.KEEP, 10f, 10f, 4f)
        brush.endStroke()
        brush.undo()
        assertTrue(brush.canRedo)
        brush.beginStroke(BrushMode.ERASE, 30f, 30f, 4f)
        brush.endStroke()
        assertFalse(brush.canRedo)
        assertNull(brush.redo())
    }

    @Test
    fun aStrokeThatChangesNothingIsNotAnUndoStep() {
        val brush = session(level = 255)
        brush.beginStroke(BrushMode.KEEP, 20f, 20f, 5f)
        assertFalse(brush.endStroke())
        assertFalse(brush.canUndo)
    }

    @Test
    fun historyIsCappedByBytesAndKeepsTheNewestStep() {
        // Each full-frame stroke costs 2 * 40 * 40 = 3200 bytes; the budget holds one.
        val brush = session(budget = 4000)
        brush.beginStroke(BrushMode.ERASE, 20f, 20f, 40f)
        brush.endStroke()
        brush.beginStroke(BrushMode.KEEP, 20f, 20f, 40f)
        brush.endStroke()
        assertNotNull(brush.undo())
        assertFalse(brush.canUndo)
        // The trimmed first stroke still differs from the starting mask.
        assertTrue(brush.hasEdits)
        assertEquals(0, brush.level(20, 20))
    }

    @Test
    fun visibleSubjectMatchesTheRendererCutoff() {
        val brush = session(level = 255)
        assertTrue(brush.hasVisibleSubject(0.5f))
        brush.beginStroke(BrushMode.ERASE, 20f, 20f, 60f)
        brush.endStroke()
        assertFalse(brush.hasVisibleSubject(0.5f))
        brush.beginStroke(BrushMode.KEEP, 20f, 20f, 2f)
        brush.endStroke()
        assertTrue(brush.hasVisibleSubject(0.5f))
    }

    @Test
    fun quantizeMatchesTheMaskCodecLevels() {
        val levels = MaskBrushSession.quantize(floatArrayOf(0f, 0.5f, 1f, 1.5f, -1f, Float.NaN))
        assertArrayEquals(byteArrayOf(0, 127, 255.toByte(), 255.toByte(), 0, 0), levels)
    }
}
