package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDimensionsTest {
    @Test
    fun swapsDimensionsForQuarterTurns() {
        assertEquals(1920 to 1080, VideoDimensions.oriented(1920, 1080, 0))
        assertEquals(1080 to 1920, VideoDimensions.oriented(1920, 1080, 90))
        assertEquals(1080 to 1920, VideoDimensions.oriented(1920, 1080, 270))
        assertEquals(1920 to 1080, VideoDimensions.oriented(1920, 1080, 360))
    }
}
