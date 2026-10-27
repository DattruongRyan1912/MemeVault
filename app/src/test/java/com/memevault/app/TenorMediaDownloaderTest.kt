package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenorMediaDownloaderTest {
    @Test
    fun acceptsTenorPageAndDirectMediaHosts() {
        val page = TenorUrlPolicy.parse(
            "https://tenor.com/view/dog-dance-memes-sticker-gif-12125836078756917104",
        ).getOrThrow()
        val direct = TenorUrlPolicy.parse("https://media.tenor.com/example.gif").getOrThrow()
        val cDomain = TenorUrlPolicy.parse("https://c.tenor.com/example.mp4?x=1").getOrThrow()
        val media1 = TenorUrlPolicy.parse(
            "https://media1.tenor.com/m/qEefqw7r73AAAAAC/dog-dance.gif",
        ).getOrThrow()

        assertTrue(!page.isDirectMedia)
        assertTrue(direct.isDirectMedia)
        assertTrue(cDomain.isDirectMedia)
        assertTrue(media1.isDirectMedia)
    }

    @Test
    fun rejectsNonTenorOrInsecureUrls() {
        assertTrue(TenorUrlPolicy.parse("http://media.tenor.com/example.gif").isFailure)
        assertTrue(TenorUrlPolicy.parse("https://evil.example/example.gif").isFailure)
        assertTrue(TenorUrlPolicy.parse("https://tenor.com/").isFailure)
        assertTrue(TenorUrlPolicy.parse("https://giphy.com/").isFailure)
        assertTrue(TenorUrlPolicy.parse("http://media.giphy.com/media/abc/giphy.webp").isFailure)
    }

    @Test
    fun acceptsGiphyPageAndDirectWebpHosts() {
        val page = TenorUrlPolicy.parse(
            "https://giphy.com/stickers/spiderweb-raisinggracie-raising-gracie-lNdz4GzldDzGMGXr2Y",
        ).getOrThrow()
        val media = TenorUrlPolicy.parse(
            "https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.webp",
        ).getOrThrow()
        val shortLink = TenorUrlPolicy.parse("https://i.giphy.com/lNdz4GzldDzGMGXr2Y.webp").getOrThrow()

        assertTrue(!page.isDirectMedia)
        assertTrue(media.isDirectMedia)
        assertTrue(shortLink.isDirectMedia)
    }

    @Test
    fun extractsOnlySupportedOpenGraphMediaCandidates() {
        val html = """
            <html><head>
              <meta property="og:video:secure_url" content="https://media.tenor.com/clip.mp4">
              <meta property="og:image" content="https://media.tenor.com/preview.gif?x=1&amp;y=2">
              <meta property="description" content="ignore me">
            </head></html>
        """.trimIndent()

        val candidates = TenorMediaDownloader().parseMediaCandidates(html)

        assertEquals(
            listOf(
                "https://media.tenor.com/clip.mp4",
                "https://media.tenor.com/preview.gif?x=1&y=2",
            ),
            candidates,
        )
    }

    @Test
    fun prefersGifAndImageOverVideoFromTenorViewPage() {
        val html = """
            <html><head>
              <meta property="og:video" content="https://media.tenor.com/qEefqw7r73AAAAPo/dog-dance.mp4">
              <meta property="og:video:secure_url" content="https://media.tenor.com/qEefqw7r73AAAAPo/dog-dance.mp4">
              <meta property="og:video" content="https://media.tenor.com/qEefqw7r73AAAAPs/dog-dance.webm">
              <meta name="twitter:image" content="https://media1.tenor.com/m/qEefqw7r73AAAAAC/dog-dance.gif">
              <meta property="og:image" content="https://media1.tenor.com/m/qEefqw7r73AAAAAC/dog-dance.gif">
              <meta name="twitter:player:stream" content="https://media.tenor.com/qEefqw7r73AAAAPo/dog-dance.mp4">
              <meta itemprop="contentUrl" content="https://media1.tenor.com/m/qEefqw7r73AAAAAC/dog-dance.gif">
            </head>
            <body>
              <div class="Sticker">
                <img src="https://media.tenor.com/qEefqw7r73AAAAAj/dog-dance.gif" alt="dog dance">
              </div>
              <img src="https://media.tenor.com/4tUqgtEeeKIAAAAM/grrr-angry.gif">
            </body></html>
        """.trimIndent()

        val candidates = TenorMediaDownloader().selectDownloadUrls(html)

        assertEquals(
            listOf(
                "https://media.tenor.com/qEefqw7r73AAAAAC/dog-dance.gif",
                "https://media.tenor.com/qEefqw7r73AAAAAj/dog-dance.gif",
                "https://media.tenor.com/qEefqw7r73AAAAPo/dog-dance.mp4",
            ),
            candidates,
        )
        assertFalse(candidates.any { it.endsWith(".webm") })
        assertFalse(candidates.any { it.contains("grrr-angry") })
    }

    @Test
    fun prefersGiphyWebpOverGifAndIgnoresRelatedStickers() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.webp">
              <meta property="og:image:type" content="image/webp">
              <meta property="og:image" content="https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.gif">
              <meta property="og:video" content="https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.mp4">
            </head>
            <body>
              <img src="https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/200.webp">
              <img src="https://media0.giphy.com/media/ziEECIVNe4NPDkQ7AR/giphy.webp">
            </body></html>
        """.trimIndent()

        val candidates = TenorMediaDownloader().selectDownloadUrls(html)

        assertEquals(
            listOf(
                "https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.webp",
                "https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/200.webp",
                "https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.gif",
                "https://media1.giphy.com/media/lNdz4GzldDzGMGXr2Y/giphy.mp4",
            ),
            candidates,
        )
        assertFalse(candidates.any { it.contains("ziEECIVNe4NPDkQ7AR") })
    }

    @Test
    fun mediaRequestsDoNotPreferHtmlSoGiphyCdnReturnsBytes() {
        val pageAccept = TenorMediaDownloader.acceptHeader(forHtmlPage = true)
        val mediaAccept = TenorMediaDownloader.acceptHeader(forHtmlPage = false)

        assertTrue(pageAccept.lowercase().contains("text/html"))
        assertTrue(mediaAccept.lowercase().contains("image/webp"))
        assertFalse(mediaAccept.lowercase().trim().startsWith("text/html"))
        val tenorMedia = TenorMediaDownloader.acceptHeader(
            forHtmlPage = false,
            host = "media.tenor.com",
        )
        assertTrue(tenorMedia.lowercase().indexOf("image/gif") < tenorMedia.lowercase().indexOf("image/webp"))
    }

    @Test
    fun rewritesTenorMediaOneSlashMToStableMediaHost() {
        assertEquals(
            "https://media.tenor.com/qEefqw7r73AAAAAC/dog-dance.gif",
            TenorMediaDownloader.canonicalizeMediaUrl(
                "https://media1.tenor.com/m/qEefqw7r73AAAAAC/dog-dance.gif",
            ),
        )
        assertEquals(
            "https://media.tenor.com/qEefqw7r73AAAAAj/dog-dance.gif",
            TenorMediaDownloader.canonicalizeMediaUrl(
                "https://media.tenor.com/qEefqw7r73AAAAAj/dog-dance.gif",
            ),
        )
    }

    @Test
    fun giphyStickerWebpIsNotForcedIntoPngOnlyStickerImport() {
        val classified = TenorMediaDownloader.classifyImportedMedia(
            pageUri = java.net.URI(
                "https://giphy.com/stickers/spiderweb-raisinggracie-raising-gracie-lNdz4GzldDzGMGXr2Y",
            ),
            mimeType = MediaPolicy.WEBP_MIME,
            displayName = "giphy.webp",
        )

        assertEquals(null, classified.typeOverride)
        assertTrue(classified.displayName.contains("sticker", ignoreCase = true))
        assertEquals(AssetType.STICKER, AssetType.fromMime(classified.mimeType, classified.displayName))
    }
}
