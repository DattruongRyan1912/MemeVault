package com.memevault.app

import android.net.Uri
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

enum class AssetType(val label: String) {
    MEME("Meme"),
    STICKER("Sticker"),
    GIF("GIF"),
    VIDEO("Video"),
    UNKNOWN("Media");

    companion object {
        fun fromMime(mimeType: String, fileName: String = ""): AssetType {
            val mime = mimeType.lowercase()
            return when {
                mime == "image/gif" || fileName.lowercase().endsWith(".gif") -> GIF
                mime == "video/mp4" || mime.startsWith("video/") -> VIDEO
                mime.startsWith("image/") && fileName.lowercase().let {
                    it.contains("sticker") || it.contains("emoji")
                } -> STICKER
                mime.startsWith("image/") -> MEME
                else -> UNKNOWN
            }
        }
    }
}

enum class AssetFilter {
    ALL,
    FAVORITES,
    ANIMATED,
    GIFS,
    STICKERS,
    MEMES,
}

enum class TagSource {
    MANUAL,
    SYSTEM,
    AI,
}

data class TagAttribution(
    val name: String,
    val source: TagSource = TagSource.MANUAL,
    val confidence: Float? = null,
    val modelVersion: String? = null,
)

data class TagSuggestion(
    val name: String,
    val usageCount: Int,
)

/**
 * Pixel coordinates in the original source image. The right and bottom edges are exclusive,
 * matching [android.graphics.Bitmap.createBitmap]. A null crop in a recipe means the full image.
 */
data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isValidFor(sourceWidth: Int, sourceHeight: Int): Boolean =
        sourceWidth > 0 && sourceHeight > 0 &&
            left >= 0 && top >= 0 && right <= sourceWidth && bottom <= sourceHeight &&
            right > left && bottom > top

    fun isFull(sourceWidth: Int, sourceHeight: Int): Boolean =
        left == 0 && top == 0 && right == sourceWidth && bottom == sourceHeight

    fun normalized(sourceWidth: Int, sourceHeight: Int): NormalizedCrop {
        require(isValidFor(sourceWidth, sourceHeight)) { "Crop ngoài kích thước ảnh nguồn" }
        return NormalizedCrop(
            left = left / sourceWidth.toFloat(),
            top = top / sourceHeight.toFloat(),
            right = right / sourceWidth.toFloat(),
            bottom = bottom / sourceHeight.toFloat(),
        )
    }

    companion object {
        fun full(sourceWidth: Int, sourceHeight: Int): CropRect =
            CropRect(0, 0, sourceWidth, sourceHeight)
    }
}

data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun toPixelRect(sourceWidth: Int, sourceHeight: Int): CropRect {
        val leftPx = (left * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
        val topPx = (top * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
        val rightPx = (right * sourceWidth).roundToInt().coerceIn(leftPx + 1, sourceWidth)
        val bottomPx = (bottom * sourceHeight).roundToInt().coerceIn(topPx + 1, sourceHeight)
        return CropRect(leftPx, topPx, rightPx, bottomPx)
    }
}

data class StickerRecipe(
    val stickerAssetId: String,
    val sourceAssetId: String?,
    val rendererId: String,
    val rendererVersion: String,
    val exportPolicyVersion: String,
    val sourceHash: String,
    val maskRelativePath: String?,
    val maskFormat: String,
    val maskWidth: Int,
    val maskHeight: Int,
    val maskHash: String?,
    val workingWidth: Int,
    val workingHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val threshold: Float,
    val paddingFraction: Float,
    val borderColorArgb: Int?,
    val borderThicknessPx: Int,
    val cropRect: CropRect?,
    val cropSourceWidth: Int? = null,
    val cropSourceHeight: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun fingerprint(): String = buildString {
        append(rendererId).append('|')
        append(rendererVersion).append('|')
        append(exportPolicyVersion).append('|')
        append(sourceHash).append('|')
        append(maskHash.orEmpty()).append('|')
        append(maskWidth).append('x').append(maskHeight).append('|')
        append(workingWidth).append('x').append(workingHeight).append('|')
        append(outputWidth).append('x').append(outputHeight).append('|')
        append(threshold).append('|')
        append(paddingFraction).append('|')
        append(borderColorArgb ?: "none").append('|')
        append(borderThicknessPx).append('|')
        append(cropRect?.left ?: 0).append(',')
        append(cropRect?.top ?: 0).append(',')
        append(cropRect?.right ?: 0).append(',')
        append(cropRect?.bottom ?: 0).append('|')
        append(cropSourceWidth ?: 0).append('x').append(cropSourceHeight ?: 0)
    }

    companion object {
        const val CURRENT_EXPORT_POLICY_VERSION = "ios-sticker-canvas-v1"

        fun supports(recipe: StickerRecipe): Boolean =
            recipe.maskFormat == MaskCodec.FORMAT_GRAY8_PNG &&
                recipe.rendererId == MlKitBackgroundRemovalEngine.ENGINE_ID &&
                recipe.rendererVersion == MlKitBackgroundRemovalEngine.ENGINE_VERSION &&
                recipe.exportPolicyVersion == CURRENT_EXPORT_POLICY_VERSION
    }
}

data class StickerSaveRequest(
    val source: Asset,
    val renderedPng: File,
    val confidenceMask: FloatArray,
    val maskWidth: Int,
    val maskHeight: Int,
    val rendererId: String,
    val rendererVersion: String,
    val exportPolicyVersion: String = StickerRecipe.CURRENT_EXPORT_POLICY_VERSION,
    val threshold: Float,
    val paddingFraction: Float,
    val borderColorArgb: Int?,
    val borderThicknessPx: Int,
    val displayName: String,
    val tags: List<String> = emptyList(),
    val cropRect: CropRect? = null,
    /** Dimensions of the decoded source canvas on which [cropRect] and the mask are based. */
    val cropSourceWidth: Int? = null,
    val cropSourceHeight: Int? = null,
)

data class NormalizedTag(
    val key: String,
    val displayName: String,
)

object TagNormalizer {
    const val MAX_TAG_LENGTH = 32
    const val MAX_TAGS_PER_ASSET = 20

    fun normalize(raw: String): NormalizedTag? {
        val normalizedUnicode = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .removePrefix("#")
            .let(::collapseWhitespace)
        if (normalizedUnicode.isEmpty() || normalizedUnicode.codePointCount(0, normalizedUnicode.length) > MAX_TAG_LENGTH) {
            return null
        }
        return NormalizedTag(
            key = normalizedUnicode.lowercase(Locale.ROOT),
            displayName = normalizedUnicode,
        )
    }

    fun normalizeAll(rawTags: Iterable<String>): List<NormalizedTag> = rawTags
        .mapNotNull(::normalize)
        .distinctBy { it.key }
        .take(MAX_TAGS_PER_ASSET)

    private fun collapseWhitespace(value: String): String {
        val result = StringBuilder(value.length)
        var pendingSpace = false
        value.forEach { character ->
            if (character.isWhitespace()) {
                if (result.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) result.append(' ')
                result.append(character)
                pendingSpace = false
            }
        }
        return result.toString().trim()
    }
}

data class Asset(
    val id: String,
    val originalPath: String,
    val mimeType: String,
    val displayName: String,
    val type: AssetType,
    val hash: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val favorite: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
    val tags: List<String>,
    val sourceAssetId: String? = null,
    val dedupKey: String = hash,
)

/**
 * Media that has a timeline and can be played back by a compatible host. GIFs are kept in
 * their original container; imported MP4 files remain videos and are not transcoded here.
 *
 * Animated WebP is intentionally not inferred from the MIME type alone because a WebP file can
 * also be a normal still image. It remains importable as image media until a byte-level animation
 * detector is added.
 */
val Asset.isAnimatedMedia: Boolean
    get() = type == AssetType.GIF || type == AssetType.VIDEO

data class ImportOutcome(
    val asset: Asset? = null,
    val duplicate: Boolean = false,
    val error: String? = null,
)

data class SharedAsset(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
)
