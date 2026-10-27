package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerRenderingPolicyTest {
    @Test
    fun confidenceAtOrBelowThresholdBecomesTransparent() {
        assertEquals(0, StickerRenderingPolicy.alphaFor(0f, 0.5f))
        assertEquals(0, StickerRenderingPolicy.alphaFor(0.5f, 0.5f))
        assertEquals(0, StickerRenderingPolicy.alphaFor(Float.NaN, 0.5f))
    }

    @Test
    fun confidenceAboveThresholdMapsToSoftAlpha() {
        assertEquals(128, StickerRenderingPolicy.alphaFor(0.75f, 0.5f))
        assertEquals(255, StickerRenderingPolicy.alphaFor(1f, 0.5f))
    }

    @Test
    fun thresholdIsClampedToSafeRange() {
        assertEquals(255, StickerRenderingPolicy.alphaFor(1f, -1f))
        assertEquals(0, StickerRenderingPolicy.alphaFor(0.99f, 1f))
    }

    @Test
    fun borderThicknessMapsToResolutionAwareRadius() {
        assertEquals(0, StickerBorderPolicy.radiusPx(1024, StickerBorderPolicy.MIN_THICKNESS))
        assertEquals(6, StickerBorderPolicy.radiusPx(1024, 4))
        assertEquals(37, StickerBorderPolicy.radiusPx(2048, StickerBorderPolicy.MAX_THICKNESS))
    }

    @Test
    fun borderThicknessIsClampedToSupportedSteps() {
        assertEquals(0, StickerBorderPolicy.clampThickness(-4))
        assertEquals(StickerBorderPolicy.MAX_THICKNESS, StickerBorderPolicy.clampThickness(99))
    }
}
