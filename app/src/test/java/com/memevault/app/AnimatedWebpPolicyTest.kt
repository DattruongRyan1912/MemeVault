package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebpPolicyTest {
    @Test
    fun samplesBoundedGifTimelineWithoutExceedingMaxFrames() {
        val timestamps = AnimatedWebpPolicy.sampleTimestamps(durationMs = 2_000)

        assertTrue(timestamps.size in 2..AnimatedWebpPolicy.MAX_FRAMES)
        assertEquals(0, timestamps.first())
        assertTrue(timestamps.last() < 2_000)
        assertEquals(timestamps, timestamps.distinct().sorted())
    }

    @Test
    fun zeroDurationStillProducesOneFrame() {
        assertEquals(listOf(0), AnimatedWebpPolicy.sampleTimestamps(0))
    }

    @Test
    fun onlyExactWebpAdvertisementSelectsAnimatedStickerVariant() {
        val gif = asset(AssetType.GIF, MediaPolicy.GIF_MIME)
        val png = asset(AssetType.STICKER, MediaPolicy.PNG_MIME)

        assertTrue(MediaPolicy.canUseAnimatedStickerWebp(gif, arrayOf("image/webp")))
        assertFalse(MediaPolicy.canUseAnimatedStickerWebp(gif, arrayOf("image/*")))
        assertFalse(MediaPolicy.canUseAnimatedStickerWebp(gif, arrayOf("image/gif")))
        assertFalse(MediaPolicy.canUseAnimatedStickerWebp(png, arrayOf("image/webp")))
        assertFalse(MediaPolicy.canUseAnimatedStickerWebp(gif, null))
    }

    private fun asset(type: AssetType, mime: String) = Asset(
        id = "00000000-0000-0000-0000-000000000001",
        originalPath = "/tmp/asset",
        mimeType = mime,
        displayName = "asset.gif",
        type = type,
        hash = "hash",
        width = 1,
        height = 1,
        durationMs = 0L,
        favorite = false,
        createdAt = 0L,
        lastUsedAt = null,
        useCount = 0,
        tags = emptyList(),
    )
}
