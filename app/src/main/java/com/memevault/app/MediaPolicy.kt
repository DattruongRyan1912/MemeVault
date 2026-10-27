package com.memevault.app

object MediaPolicy {
    const val GIF_MIME = "image/gif"
    const val PNG_MIME = "image/png"
    const val JPEG_MIME = "image/jpeg"
    const val WEBP_MIME = "image/webp"
    const val MP4_MIME = "video/mp4"

    fun isSupportedImport(mimeType: String): Boolean {
        val mime = mimeType.lowercase()
        return mime.startsWith("image/") || mime == MP4_MIME
    }

    fun normalizeMime(mimeType: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (mime) {
            "image/jpg" -> JPEG_MIME
            "image/pjpeg" -> JPEG_MIME
            "image/x-png" -> PNG_MIME
            else -> mime
        }
    }

    fun extensionFor(mimeType: String): String = when (normalizeMime(mimeType)) {
        PNG_MIME -> "png"
        JPEG_MIME -> "jpg"
        GIF_MIME -> "gif"
        MP4_MIME -> "mp4"
        WEBP_MIME -> "webp"
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/bmp" -> "bmp"
        "image/x-icon" -> "ico"
        else -> "bin"
    }

    fun canCommitContent(editorMimeTypes: Array<String>?, assetMimeType: String): Boolean {
        val normalizedAsset = normalizeMime(assetMimeType)
        return editorMimeTypes.orEmpty().any { editorMime ->
            val normalizedEditor = normalizeMime(editorMime)
            normalizedEditor == "*/*" ||
                normalizedEditor == normalizedAsset ||
                (normalizedEditor.endsWith("/*") &&
                    normalizedAsset.substringBefore('/') == normalizedEditor.substringBefore('/'))
        }
    }

    fun mimeForDestination(asset: Asset, destination: Destination): String = when {
        destination == Destination.TIKTOK && asset.type == AssetType.GIF -> MP4_MIME
        needsStickerCompatibilityProfile(asset, destination) -> PNG_MIME
        else -> normalizeMime(asset.mimeType)
    }

    /**
     * MIME advertised to the Android sharesheet. TikTok's generic image share path accepts an
     * image wildcard, while the provider and clipboard continue to expose the exact PNG type.
     */
    fun intentMimeType(mimeType: String, destination: Destination): String {
        val normalized = normalizeMime(mimeType)
        return if (
            (destination == Destination.TIKTOK && normalized.startsWith("image/")) ||
            normalized == WEBP_MIME
        ) {
            "image/*"
        } else {
            normalized
        }
    }

    fun needsStickerCompatibilityProfile(asset: Asset, destination: Destination): Boolean =
        (destination == Destination.TIKTOK || destination == Destination.ZALO) &&
            asset.type == AssetType.STICKER &&
            normalizeMime(asset.mimeType) in setOf(PNG_MIME, WEBP_MIME)

    fun canUseAnimatedStickerWebp(asset: Asset, editorMimeTypes: Array<String>?): Boolean {
        if (asset.type != AssetType.GIF) return false
        return editorMimeTypes.orEmpty().any { normalizeMime(it) == WEBP_MIME }
    }
}

enum class Destination {
    SYSTEM,
    TIKTOK,
    ZALO,
}
