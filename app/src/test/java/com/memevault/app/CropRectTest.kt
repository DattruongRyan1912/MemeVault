package com.memevault.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRectTest {
    @Test
    fun fullImageIsValidAndDetectable() {
        val rect = CropRect.full(320, 240)

        assertTrue(rect.isValidFor(320, 240))
        assertTrue(rect.isFull(320, 240))
        assertTrue(rect.width == 320)
        assertTrue(rect.height == 240)
    }

    @Test
    fun cropUsesExclusiveRightAndBottomEdges() {
        val rect = CropRect(12, 20, 112, 180)

        assertTrue(rect.isValidFor(320, 240))
        assertTrue(rect.width == 100)
        assertTrue(rect.height == 160)
        assertFalse(rect.isFull(320, 240))
    }

    @Test
    fun cropRejectsOutOfBoundsOrEmptySelection() {
        assertFalse(CropRect(-1, 0, 20, 20).isValidFor(320, 240))
        assertFalse(CropRect(10, 10, 10, 20).isValidFor(320, 240))
        assertFalse(CropRect(0, 0, 321, 240).isValidFor(320, 240))
    }

    @Test
    fun normalizedSelectionRoundTripsPixelEdges() {
        val rect = CropRect(113, 77, 511, 692)

        assertTrue(rect == rect.normalized(720, 960).toPixelRect(720, 960))
    }
}
