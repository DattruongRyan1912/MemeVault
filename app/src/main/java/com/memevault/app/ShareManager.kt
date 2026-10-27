package com.memevault.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import java.util.concurrent.Executor

object ShareManager {
    fun share(
        activity: Activity,
        repository: AssetRepository,
        asset: Asset,
        destination: Destination,
        backgroundExecutor: Executor,
        onError: (String) -> Unit,
    ) {
        share(
            activity,
            repository,
            asset,
            destination,
            backgroundExecutor,
            onError,
            StickerShareProfile.OPAQUE_512,
        )
    }

    fun share(
        activity: Activity,
        repository: AssetRepository,
        asset: Asset,
        destination: Destination,
        backgroundExecutor: Executor,
        onError: (String) -> Unit,
        stickerProfile: StickerShareProfile,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val needsPreparation = destination == Destination.TIKTOK && asset.type == AssetType.GIF ||
            MediaPolicy.needsStickerCompatibilityProfile(asset, destination)
        val preparationError = if (destination == Destination.TIKTOK && asset.type == AssetType.GIF) {
            "Không thể tạo video TikTok"
        } else {
            "Không thể tạo ảnh tương thích cho ${if (destination == Destination.ZALO) "Zalo" else "TikTok"}"
        }
        // sharedAsset() waits for storage reconciliation and may render a variant, so it never
        // runs on the UI thread, even for the plain original.
        val failureMessage = if (needsPreparation) preparationError else "Không thể chia sẻ media"
        backgroundExecutor.execute {
            runCatching { repository.sharedAsset(asset, destination, stickerProfile) }
                .onSuccess { shared ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        launchChooser(activity, shared, destination)
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        onError(error.message ?: failureMessage)
                    }
                }
        }
    }

    fun shareAnimatedSticker(
        activity: Activity,
        repository: AssetRepository,
        asset: Asset,
        backgroundExecutor: Executor,
        onError: (String) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (asset.type != AssetType.GIF) {
            onError(activity.getString(R.string.share_animated_sticker_unsupported))
            return
        }
        Toast.makeText(activity, R.string.share_animated_sticker_preparing, Toast.LENGTH_SHORT).show()
        backgroundExecutor.execute {
            var convertError: String? = null
            val result = runCatching { repository.sharedAnimatedSticker(asset) }
                .recoverCatching { error ->
                    convertError = error.message
                    repository.sharedAsset(asset, Destination.SYSTEM)
                }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { shared ->
                    if (shared.mimeType != MediaPolicy.WEBP_MIME) {
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.share_animated_sticker_fallback,
                                convertError ?: activity.getString(R.string.share_animated_sticker_failed),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    runCatching { launchChooser(activity, shared, Destination.SYSTEM) }
                        .onFailure { error ->
                            onError(error.message ?: activity.getString(R.string.share_animated_sticker_failed))
                        }
                }.onFailure { error ->
                    onError(error.message ?: activity.getString(R.string.share_animated_sticker_failed))
                }
            }
        }
    }

    /**
     * Copies the original through a content URI. The clip is built with the known MIME type:
     * ClipData.newUri() would ask this app's own provider for the type on the calling thread,
     * which touches Room on the main thread and can silently drop the image MIME.
     */
    fun copy(
        activity: Activity,
        repository: AssetRepository,
        asset: Asset,
        backgroundExecutor: Executor,
        onError: (String) -> Unit,
        onCopied: (String) -> Unit = { message ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        },
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        backgroundExecutor.execute {
            val result = runCatching { repository.sharedAsset(asset, Destination.SYSTEM) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.mapCatching { shared ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        ?: error("Clipboard không khả dụng")
                    clipboard.setPrimaryClip(clipFor(shared))
                }.onSuccess {
                    onCopied("Đã copy ${asset.displayName}")
                }.onFailure { error -> onError(error.message ?: "Không thể copy media") }
            }
        }
    }

    internal fun clipFor(shared: SharedAsset): ClipData = ClipData(
        ClipDescription(shared.displayName, arrayOf(shared.mimeType)),
        ClipData.Item(shared.uri),
    )

    private fun launchChooser(
        activity: Activity,
        shared: SharedAsset,
        destination: Destination,
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MediaPolicy.intentMimeType(shared.mimeType, destination)
            putExtra(Intent.EXTRA_STREAM, shared.uri)
            putExtra(Intent.EXTRA_TITLE, shared.displayName)
            clipData = ClipData.newRawUri(shared.displayName, shared.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserTitle = when (destination) {
            Destination.TIKTOK -> if (shared.mimeType == MediaPolicy.PNG_MIME) {
                "Gửi ảnh tương thích cho TikTok"
            } else {
                "Xuất bản video cho TikTok"
            }
            Destination.ZALO -> "Gửi ảnh tương thích cho Zalo"
            Destination.SYSTEM -> if (shared.mimeType == MediaPolicy.WEBP_MIME) {
                activity.getString(R.string.share_animated_sticker_chooser)
            } else {
                "Chia sẻ ${shared.displayName}"
            }
        }
        activity.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
