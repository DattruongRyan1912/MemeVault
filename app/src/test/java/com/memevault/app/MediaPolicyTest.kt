package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyTest {
    @Test
    fun normalizesCommonProviderMimeAliases() {
        assertEquals(MediaPolicy.JPEG_MIME, MediaPolicy.normalizeMime("IMAGE/JPG; charset=binary"))
        assertEquals(MediaPolicy.PNG_MIME, MediaPolicy.normalizeMime("image/x-png"))
        assertEquals("", MediaPolicy.normalizeMime(null))
    }

    @Test
    fun acceptsImagesAndMp4OnlyForMvpImport() {
        assertTrue(MediaPolicy.isSupportedImport("image/png"))
        assertTrue(MediaPolicy.isSupportedImport("image/gif"))
        assertTrue(MediaPolicy.isSupportedImport("video/mp4"))
        assertFalse(MediaPolicy.isSupportedImport("video/webm"))
        assertFalse(MediaPolicy.isSupportedImport("application/pdf"))
    }

    @Test
    fun editorMimeMatchingSupportsExactWildcardAndAny() {
        assertTrue(MediaPolicy.canCommitContent(arrayOf("image/*"), MediaPolicy.GIF_MIME))
        assertTrue(MediaPolicy.canCommitContent(arrayOf("*/*"), MediaPolicy.MP4_MIME))
        assertTrue(MediaPolicy.canCommitContent(arrayOf("video/mp4"), MediaPolicy.MP4_MIME))
        assertFalse(MediaPolicy.canCommitContent(arrayOf("image/png"), MediaPolicy.GIF_MIME))
        assertFalse(MediaPolicy.canCommitContent(null, MediaPolicy.GIF_MIME))
    }

    @Test
    fun tiktokDestinationUsesVideoForGif() {
        val gif = asset(AssetType.GIF, MediaPolicy.GIF_MIME)
        val png = asset(AssetType.MEME, MediaPolicy.PNG_MIME)

        assertEquals(MediaPolicy.MP4_MIME, MediaPolicy.mimeForDestination(gif, Destination.TIKTOK))
        assertEquals(MediaPolicy.PNG_MIME, MediaPolicy.mimeForDestination(png, Destination.TIKTOK))
    }

    @Test
    fun stickerPlatformsUseCompatibilityProfileButMessengerKeepsAlpha() {
        val sticker = asset(AssetType.STICKER, MediaPolicy.PNG_MIME)
        val jpeg = asset(AssetType.STICKER, MediaPolicy.JPEG_MIME)
        val meme = asset(AssetType.MEME, MediaPolicy.PNG_MIME)

        assertTrue(MediaPolicy.needsStickerCompatibilityProfile(sticker, Destination.TIKTOK))
        assertTrue(MediaPolicy.needsStickerCompatibilityProfile(sticker, Destination.ZALO))
        assertFalse(MediaPolicy.needsStickerCompatibilityProfile(sticker, Destination.SYSTEM))
        assertFalse(MediaPolicy.needsStickerCompatibilityProfile(jpeg, Destination.TIKTOK))
        assertFalse(MediaPolicy.needsStickerCompatibilityProfile(meme, Destination.TIKTOK))
        assertEquals(MediaPolicy.PNG_MIME, MediaPolicy.mimeForDestination(sticker, Destination.ZALO))
    }

    @Test
    fun intentMimeUsesWildcardForTikTokImagesAndWebp() {
        assertEquals("image/*", MediaPolicy.intentMimeType(MediaPolicy.PNG_MIME, Destination.TIKTOK))
        assertEquals(MediaPolicy.PNG_MIME, MediaPolicy.intentMimeType(MediaPolicy.PNG_MIME, Destination.ZALO))
        assertEquals(MediaPolicy.MP4_MIME, MediaPolicy.intentMimeType(MediaPolicy.MP4_MIME, Destination.TIKTOK))
        assertEquals(MediaPolicy.PNG_MIME, MediaPolicy.intentMimeType(MediaPolicy.PNG_MIME, Destination.SYSTEM))
        assertEquals("image/*", MediaPolicy.intentMimeType(MediaPolicy.WEBP_MIME, Destination.SYSTEM))
        assertEquals("image/*", MediaPolicy.intentMimeType(MediaPolicy.WEBP_MIME, Destination.ZALO))
    }

    private fun asset(type: AssetType, mime: String) = Asset(
        id = "00000000-0000-0000-0000-000000000001",
        originalPath = "/tmp/asset",
        mimeType = mime,
        displayName = "asset",
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
