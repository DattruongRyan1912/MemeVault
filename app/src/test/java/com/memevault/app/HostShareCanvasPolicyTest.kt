package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HostShareCanvasPolicyTest {
    @Test
    fun landscapeImageIsCenteredAndPadded() {
        val layout = HostShareCanvasPolicy.layout(1000, 500)

        assertEquals(HostShareCanvasPolicy.CANVAS_SIZE, layout.canvasSize)
        assertEquals(369, layout.contentWidth)
        assertEquals(185, layout.contentHeight)
        assertEquals(71, layout.left)
        assertEquals(163, layout.top)
    }

    @Test
    fun portraitImageKeepsAspectRatioAndDoesNotUpscaleSmallSource() {
        val portrait = HostShareCanvasPolicy.layout(500, 1000)
        val small = HostShareCanvasPolicy.layout(100, 50)

        assertEquals(185, portrait.contentWidth)
        assertEquals(369, portrait.contentHeight)
        assertEquals(163, portrait.left)
        assertEquals(71, portrait.top)
        assertEquals(100, small.contentWidth)
        assertEquals(50, small.contentHeight)
    }

    @Test
    fun iphoneRasterUsesTransparentStyleSquareWithSmallInset() {
        val layout = IosStickerCanvasPolicy.layout(1000, 500)

        assertEquals(320, layout.canvasSize)
        assertEquals(301, layout.contentWidth)
        assertEquals(151, layout.contentHeight)
        assertEquals(9, layout.left)
        assertEquals(84, layout.top)
    }
}
