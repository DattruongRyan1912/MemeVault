package com.memevault.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        if (needsPreparation) {
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
                            onError(error.message ?: preparationError)
                        }
                    }
            }
        } else {
            runCatching { repository.sharedAsset(asset, destination, stickerProfile) }
                .onSuccess { shared ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        launchChooser(activity, shared, destination)
                    }
                }
                .onFailure { error ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onError(error.message ?: "Không thể chia sẻ media")
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

    fun copy(context: Context, repository: AssetRepository, asset: Asset, onError: (String) -> Unit) {
        runCatching {
            val shared = repository.sharedAsset(asset, Destination.SYSTEM)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: error("Clipboard không khả dụng")
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, shared.displayName, shared.uri))
        }.onSuccess {
            Toast.makeText(context, "Đã copy ${asset.displayName}", Toast.LENGTH_SHORT).show()
        }.onFailure { error -> onError(error.message ?: "Không thể copy media") }
    }

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
