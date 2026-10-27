package com.memevault.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Validates the small set of public Tenor and Giphy hosts that MemeVault needs for URL import.
 * Arbitrary URLs are intentionally rejected so the importer cannot become a generic
 * server-side request proxy.
 */
object TenorUrlPolicy {
    private val pageHosts = setOf(
        "tenor.com",
        "www.tenor.com",
        "giphy.com",
        "www.giphy.com",
    )
    private val mediaHosts = setOf(
        "media.tenor.com",
        "media1.tenor.com",
        "c.tenor.com",
        "media.giphy.com",
        "media0.giphy.com",
        "media1.giphy.com",
        "media2.giphy.com",
        "media3.giphy.com",
        "media4.giphy.com",
        "i.giphy.com",
    )

    data class ValidatedUrl(
        val uri: URI,
        val isDirectMedia: Boolean,
    )

    fun parse(raw: String): Result<ValidatedUrl> = runCatching {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Hãy dán một link Tenor hoặc Giphy" }
        require(value.length <= MAX_URL_LENGTH) { "Link media quá dài" }
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Chỉ hỗ trợ link Tenor/Giphy HTTPS"
        }
        require(uri.userInfo == null) { "Link không được chứa thông tin đăng nhập" }
        require(uri.port == -1 || uri.port == 443) { "Link dùng cổng không hợp lệ" }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host in pageHosts || host in mediaHosts) {
            "Chỉ hỗ trợ tenor.com, giphy.com và CDN media của chúng"
        }
        require(!uri.path.isNullOrBlank() && uri.path != "/") {
            "Link không có nội dung media"
        }
        ValidatedUrl(uri, host in mediaHosts)
    }

    internal fun isAllowedHost(host: String?): Boolean =
        host?.lowercase(Locale.ROOT) in pageHosts || host?.lowercase(Locale.ROOT) in mediaHosts

    internal fun isMediaHost(host: String?): Boolean = host?.lowercase(Locale.ROOT) in mediaHosts

    internal fun isGiphyHost(host: String?): Boolean {
        val value = host?.lowercase(Locale.ROOT).orEmpty()
        return value == "giphy.com" || value.endsWith(".giphy.com")
    }

    internal fun refererFor(host: String?): String =
        if (isGiphyHost(host)) "https://giphy.com/" else "https://tenor.com/"

    private const val MAX_URL_LENGTH = 2_048
}

data class DownloadedTenorMedia(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val typeOverride: AssetType? = null,
)

data class UrlImportClassification(
    val displayName: String,
    val mimeType: String,
    val typeOverride: AssetType? = null,
)

/**
 * Downloads one public Tenor item into a caller-provided temporary directory. The caller owns
 * the returned file and must delete it after passing it to AssetRepository.
 */
class TenorMediaDownloader {
    fun download(rawUrl: String, temporaryDirectory: File): DownloadedTenorMedia {
        val validated = TenorUrlPolicy.parse(canonicalizeMediaUrl(rawUrl)).getOrThrow()
        require(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
            "Không tạo được vùng tạm để tải Tenor"
        }
        val mediaUris = if (validated.isDirectMedia) {
            listOf(validated.uri)
        } else {
            resolvePage(validated.uri)
        }
        var lastError: Exception? = null
        for (mediaUri in mediaUris) {
            try {
                val downloaded = downloadMedia(mediaUri, temporaryDirectory)
                val classified = classifyImportedMedia(
                    pageUri = validated.uri,
                    mimeType = downloaded.mimeType,
                    displayName = downloaded.displayName,
                )
                return downloaded.copy(
                    displayName = classified.displayName,
                    mimeType = classified.mimeType,
                    typeOverride = classified.typeOverride,
                )
            } catch (error: Exception) {
                lastError = if (error is IOException) {
                    error
                } else {
                    IOException(error.message ?: "Không tải được media", error)
                }
            }
        }
        throw lastError ?: IOException("Không tìm thấy link media trực tiếp trong trang")
    }

    private fun resolvePage(pageUri: URI): List<URI> {
        val connection = openResponse(pageUri, forHtmlPage = true)
        return try {
            val contentType = MediaPolicy.normalizeMime(connection.contentType)
            require(contentType.isBlank() || contentType == "text/html") {
                "Link không trả về trang HTML hợp lệ"
            }
            val html = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                BoundedStreams.copy(input, output, MAX_PAGE_BYTES)
                output.toString(StandardCharsets.UTF_8.name())
            }
            val urls = selectDownloadUrls(html)
            if (urls.isEmpty()) {
                throw IOException("Không tìm thấy link media trực tiếp trong trang")
            }
            urls.map { candidate -> TenorUrlPolicy.parse(candidate).getOrThrow().uri }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseMediaCandidates(html: String): List<String> {
        val candidates = linkedSetOf<String>()
        META_TAG_PATTERN.findAll(html).forEach { match ->
            val tag = match.value
            val itemprop = attribute(tag, "itemprop")?.lowercase(Locale.ROOT)
            val property = attribute(tag, "property")
                ?: attribute(tag, "name")
            val normalizedProperty = property?.lowercase(Locale.ROOT)
            if (normalizedProperty !in MEDIA_META_PROPERTIES && itemprop != "contenturl") {
                return@forEach
            }
            attribute(tag, "content")
                ?.let(::decodeHtml)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(candidates::add)
        }
        return candidates.toList()
    }

    internal fun selectDownloadUrls(html: String): List<String> {
        val extracted = linkedSetOf<String>()
        parseMediaCandidates(html).forEach(extracted::add)
        parseMediaSrcUrls(html).forEach(extracted::add)
        val validated = extracted.mapNotNull { raw ->
            val canonical = canonicalizeMediaUrl(raw)
            canonical.takeIf { TenorUrlPolicy.parse(it).getOrNull()?.isDirectMedia == true }
        }
        val family = validated.firstNotNullOfOrNull(::mediaFamily)
        val scoped = if (family == null) {
            validated
        } else {
            validated.filter { mediaFamily(it) == family }
        }
        return scoped
            .filter { mediaPreference(it) != Int.MAX_VALUE }
            .distinctBy(::normalizeCandidate)
            .sortedWith(compareBy<String> { mediaPreference(it) }.thenBy { scoped.indexOf(it) })
    }

    private fun downloadMedia(mediaUri: URI, temporaryDirectory: File): DownloadedTenorMedia {
        val connection = openResponse(mediaUri, forHtmlPage = false)
        var temporaryFile: File? = null
        try {
            val responseUrl = runCatching { connection.url.toURI() }.getOrElse { mediaUri }
            require(TenorUrlPolicy.isAllowedHost(responseUrl.host)) {
                "Link chuyển hướng tới host không được hỗ trợ"
            }
            val declaredMime = MediaPolicy.normalizeMime(connection.contentType)
            require(declaredMime != "text/html") { "Link không trả về file media" }
            if (connection.contentLengthLong > MAX_MEDIA_BYTES) {
                throw FileSizeLimitExceededException(MAX_MEDIA_BYTES)
            }

            temporaryFile = File.createTempFile("tenor-", ".part", temporaryDirectory)
            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output ->
                    BoundedStreams.copy(input, output, MAX_MEDIA_BYTES)
                }
            }
            require(temporaryFile.length() > 0L) { "Link trả về file rỗng" }
            val fileName = safeDisplayName(responseUrl, declaredMime)
            val mimeType = detectMime(temporaryFile, declaredMime, fileName)
            require(MediaPolicy.isSupportedImport(mimeType)) {
                "Định dạng không được hỗ trợ: $mimeType"
            }
            val result = DownloadedTenorMedia(temporaryFile, fileName, mimeType)
            temporaryFile = null
            return result
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException(error.message ?: "Không tải được media Tenor", error)
        } finally {
            connection.disconnect()
            temporaryFile?.delete()
        }
    }

    private fun openResponse(initialUri: URI, forHtmlPage: Boolean): HttpURLConnection {
        var current = initialUri
        for (attempt in 0..MAX_REDIRECTS) {
            require(current.scheme.equals("https", ignoreCase = true)) {
                "Link chuyển hướng sang giao thức không an toàn"
            }
            require(TenorUrlPolicy.isAllowedHost(current.host)) {
                "Link chuyển hướng tới host không được hỗ trợ"
            }
            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", acceptHeader(forHtmlPage, current.host))
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", TenorUrlPolicy.refererFor(current.host))
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Tenor trả về chuyển hướng không hợp lệ" }
                current = current.resolve(location)
                continue
            }
            if (status !in 200..299) {
                connection.disconnect()
                throw IOException("Tenor trả về HTTP $status")
            }
            return connection
        }
        throw IOException("Tenor chuyển hướng quá nhiều lần")
    }

    private fun detectMime(file: File, declaredMime: String, displayName: String): String {
        val sniffed = sniffMime(file)
        if (sniffed != null) return sniffed
        if (declaredMime.startsWith("image/") || declaredMime == MediaPolicy.MP4_MIME) {
            return declaredMime
        }
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "gif" -> MediaPolicy.GIF_MIME
            "png" -> MediaPolicy.PNG_MIME
            "jpg", "jpeg" -> MediaPolicy.JPEG_MIME
            "webp" -> "image/webp"
            "mp4" -> MediaPolicy.MP4_MIME
            else -> ""
        }
    }

    private fun sniffMime(file: File): String? {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        if (read >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return MediaPolicy.PNG_MIME
        if (read >= 6 && String(header, 0, 6, StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a")) {
            return MediaPolicy.GIF_MIME
        }
        if (read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return MediaPolicy.JPEG_MIME
        }
        if (read >= 12 &&
            String(header, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }
        if (read >= 8 && String(header, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
            return MediaPolicy.MP4_MIME
        }
        return null
    }

    private fun safeDisplayName(uri: URI, declaredMime: String): String {
        val rawName = uri.path.substringAfterLast('/').takeIf(String::isNotBlank)
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it) }
            ?.replace('/', '_')
            ?.replace('\\', '_')
            ?.replace('\u0000', '_')
            ?.trim()
            .orEmpty()
        if (rawName.isNotEmpty()) return rawName.take(MAX_DISPLAY_NAME_LENGTH)
        val extension = MediaPolicy.extensionFor(declaredMime).ifBlank { "bin" }
        return "tenor-${System.currentTimeMillis()}.$extension"
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(1)

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/", ignoreCase = true)

    private fun parseMediaSrcUrls(html: String): List<String> =
        MEDIA_SRC_PATTERN.findAll(html).mapNotNull { match ->
            match.groupValues.getOrNull(1)
                ?.let(::decodeHtml)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.toList()

    private fun mediaFamily(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        val tenor = segments.firstOrNull { it.contains("AAAA", ignoreCase = true) }
        if (tenor != null) {
            val marker = tenor.indexOf("AAAA", ignoreCase = true)
            return if (marker > 0) tenor.substring(0, marker) else tenor
        }
        return segments
            .map { it.substringBefore('.') }
            .filter { segment ->
                val normalized = segment.lowercase(Locale.ROOT)
                normalized.isNotEmpty() &&
                    normalized !in GENERIC_PATH_SEGMENTS &&
                    normalized != "v1"
            }
            .maxByOrNull { it.length }
    }

    private fun mediaPreference(url: String): Int {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        val giphy = TenorUrlPolicy.isGiphyHost(runCatching { URI(url).host }.getOrNull())
        val preview = path.contains("_s.") || path.contains("-preview.") || path.contains("_d.")
        return when {
            giphy && path.endsWith("giphy.webp") -> 0
            giphy && path.endsWith(".webp") && !preview -> 1
            path.endsWith(".gif") && !preview -> if (giphy) 2 else 0
            path.endsWith(".webp") -> 1
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") -> 3
            path.endsWith(".mp4") -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun normalizeCandidate(url: String): String =
        url.substringBefore('?').lowercase(Locale.ROOT)

    companion object {
        const val MAX_MEDIA_BYTES = 50L * 1024L * 1024L
        private const val MAX_PAGE_BYTES = 1L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_DISPLAY_NAME_LENGTH = 160
        private const val USER_AGENT = "MemeVault/0.2 (personal media importer)"

        internal fun classifyImportedMedia(
            pageUri: URI,
            mimeType: String,
            displayName: String,
        ): UrlImportClassification {
            val path = pageUri.path.orEmpty().lowercase(Locale.ROOT)
            val stickerPage = path.contains("/stickers/") || path.contains("/sticker/")
            val name = if (stickerPage && !displayName.contains("sticker", ignoreCase = true)) {
                "sticker-$displayName"
            } else {
                displayName
            }
            // URL imports must not use typeOverride=STICKER. That flag is reserved for Studio/Zalo
            // PNG stickers and rejects WebP/GIF with "Sticker phải là PNG hợp lệ".
            return UrlImportClassification(
                displayName = name,
                mimeType = mimeType,
                typeOverride = null,
            )
        }

        internal fun canonicalizeMediaUrl(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return raw
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path.orEmpty()
            if (host.endsWith("tenor.com")) {
                val match = TENOR_MEDIA_M_PATH.find(path)
                if (match != null) {
                    return URI(
                        "https",
                        "media.tenor.com",
                        "/${match.groupValues[1]}",
                        uri.query,
                        uri.fragment,
                    ).toString()
                }
            }
            return raw
        }

        internal fun acceptHeader(forHtmlPage: Boolean, host: String? = null): String =
            if (forHtmlPage) {
                "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
            } else if (TenorUrlPolicy.isGiphyHost(host)) {
                "image/webp,image/gif,image/*,video/mp4;q=0.9,*/*;q=0.8"
            } else {
                "image/gif,image/webp,image/*,video/mp4;q=0.9,*/*;q=0.8"
            }
        private val TENOR_MEDIA_M_PATH = Regex("^/m/([^/]+/.+)$")
        private val GENERIC_PATH_SEGMENTS = setOf(
            "m",
            "media",
            "gifs",
            "gif",
            "stickers",
            "sticker",
            "giphy",
        )
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val META_TAG_PATTERN = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val MEDIA_SRC_PATTERN = Regex(
            "<(?:img|source)\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )
        private val MEDIA_META_PROPERTIES = setOf(
            "og:video:secure_url",
            "og:video:url",
            "og:video",
            "og:image",
            "og:image:url",
            "og:image:secure_url",
            "twitter:image",
            "twitter:player:stream",
        )
    }
}
