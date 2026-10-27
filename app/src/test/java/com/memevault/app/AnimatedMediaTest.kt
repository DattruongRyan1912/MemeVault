package com.memevault.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedMediaTest {
    @Test
    fun gifAndVideoAreIncludedInAnimatedSurfaces() {
        assertTrue(asset(AssetType.GIF).isAnimatedMedia)
        assertTrue(asset(AssetType.VIDEO).isAnimatedMedia)
    }

    @Test
    fun stillImageTypesAreNotAssumedToBeAnimated() {
        assertFalse(asset(AssetType.MEME).isAnimatedMedia)
        assertFalse(asset(AssetType.STICKER).isAnimatedMedia)
        assertFalse(asset(AssetType.UNKNOWN).isAnimatedMedia)
    }

    @Test
    fun gifAndWebpUseInlineAnimatedPreviewInDetail() {
        assertTrue(MediaPreviewLoader.shouldPlayInline(MediaPolicy.GIF_MIME))
        assertTrue(MediaPreviewLoader.shouldPlayInline(MediaPolicy.WEBP_MIME))
        assertFalse(MediaPreviewLoader.shouldPlayInline(MediaPolicy.PNG_MIME))
        assertFalse(MediaPreviewLoader.shouldPlayInline(MediaPolicy.JPEG_MIME))
        assertFalse(MediaPreviewLoader.shouldPlayInline(MediaPolicy.MP4_MIME))
    }

    private fun asset(type: AssetType) = Asset(
        id = type.name,
        originalPath = "/tmp/${type.name.lowercase()}",
        mimeType = when (type) {
            AssetType.GIF -> MediaPolicy.GIF_MIME
            AssetType.VIDEO -> MediaPolicy.MP4_MIME
            else -> MediaPolicy.PNG_MIME
        },
        displayName = type.label,
        type = type,
        hash = type.name,
        width = 1,
        height = 1,
        durationMs = if (type == AssetType.GIF || type == AssetType.VIDEO) 1_000L else 0L,
        favorite = false,
        createdAt = 0L,
        lastUsedAt = null,
        useCount = 0,
        tags = emptyList(),
    )
}
