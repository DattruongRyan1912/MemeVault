package com.memevault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.max

class AssetRepository(private val context: Context) : AutoCloseable {
    private val resolver = context.contentResolver
    private val database = AssetDatabase(context)
    private val mediaDirectory = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
    private val stagingDirectory = File(mediaDirectory, STAGING_DIRECTORY).apply { mkdirs() }
    private val assetsDirectory = File(mediaDirectory, ORIGINALS_DIRECTORY).apply { mkdirs() }
    private val variantsDirectory = File(mediaDirectory, VARIANTS_DIRECTORY).apply { mkdirs() }
    private val masksDirectory = File(mediaDirectory, MASKS_DIRECTORY).apply { mkdirs() }
    private val reconcileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reconcileTask = reconcileExecutor.submit {
        withStorageWriteLock { reconcileStorage() }
    }

    private fun awaitReconciliation() {
        try {
            reconcileTask.get(RECONCILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Storage reconciliation bị gián đoạn", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException("Storage reconciliation quá thời gian", error)
        } catch (error: ExecutionException) {
            throw IllegalStateException(
                "Storage reconciliation thất bại",
                error.cause ?: error,
            )
        }
    }

    fun list(filter: AssetFilter = AssetFilter.ALL, search: String = ""): List<Asset> {
        awaitReconciliation()
        return database.list(filter, search).filter { managedOriginal(it) }
    }

    fun findById(id: String): Asset? {
        awaitReconciliation()
        return database.findById(id)?.takeIf(::managedOriginal)
    }

    fun findStickerRecipe(id: String): StickerRecipe? {
        awaitReconciliation()
        return database.findStickerRecipe(id)
    }

    fun updateTags(id: String, tags: List<String>): Asset? {
        awaitReconciliation()
        return database.updateTags(id, tags)
    }

    fun suggestTags(prefix: String, limit: Int = 12): List<TagSuggestion> {
        awaitReconciliation()
        return database.suggestTags(prefix, limit)
    }

    fun countDerivedAssets(id: String): Int {
        awaitReconciliation()
        return database.countDerivedAssets(id)
    }

    internal fun findVariant(id: String, profile: String): StoredVariant? {
        awaitReconciliation()
        return database.findVariant(id, profile)
    }

    fun importUri(uri: Uri): ImportOutcome = importUriInternal(uri, null)

    /** Imports a PNG from a user-granted external tree as a sticker asset. */
    fun importUriAsSticker(uri: Uri): ImportOutcome = importUriInternal(uri, AssetType.STICKER)

    private fun importUriInternal(uri: Uri, typeOverride: AssetType?): ImportOutcome {
        awaitReconciliation()
        val displayName = DisplayNames.fromUri(resolver, uri)
        val reportedMime = MediaPolicy.normalizeMime(runCatching { resolver.getType(uri) }.getOrNull())
        val nameMime = MediaPolicy.normalizeMime(mimeFromName(displayName))
        val mimeType = if (reportedMime.isBlank() || reportedMime == "application/octet-stream") {
            nameMime.ifBlank { reportedMime }
        } else {
            reportedMime
        }
        if (!MediaPolicy.isSupportedImport(mimeType)) {
            return ImportOutcome(error = "Không hỗ trợ định dạng $mimeType")
        }

        val temporaryFile = runCatching { File.createTempFile("import-", ".part", stagingDirectory) }
            .getOrElse { return ImportOutcome(error = "Không tạo được file tạm") }
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporaryFile.outputStream().use { output ->
                    BoundedStreams.copy(input, output, MAX_IMPORT_BYTES)
                }
            } ?: return ImportOutcome(error = "Không đọc được nội dung media")
            return persistImportedFile(temporaryFile, displayName, mimeType, typeOverride)
        } catch (error: Exception) {
            return ImportOutcome(error = error.message ?: "Không thể lưu media")
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    fun importGeneratedFile(
        source: File,
        displayName: String,
        mimeType: String = MediaPolicy.PNG_MIME,
        typeOverride: AssetType? = AssetType.STICKER,
    ): ImportOutcome {
        awaitReconciliation()
        if (!source.isFile) return ImportOutcome(error = "Không tìm thấy sticker đã tạo")
        if (source.length() > MAX_IMPORT_BYTES) {
            return ImportOutcome(error = "File vượt quá giới hạn ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
        }
        val normalizedMime = MediaPolicy.normalizeMime(mimeType)
        if (!MediaPolicy.isSupportedImport(normalizedMime)) {
            return ImportOutcome(error = "Không hỗ trợ định dạng $normalizedMime")
        }
        val temporaryFile = runCatching { File.createTempFile("generated-", ".part", stagingDirectory) }
            .getOrElse { return ImportOutcome(error = "Không tạo được file tạm") }
        return try {
            source.inputStream().use { input ->
                temporaryFile.outputStream().use { output ->
                    BoundedStreams.copy(input, output, MAX_IMPORT_BYTES)
                }
            }
            persistImportedFile(temporaryFile, displayName, normalizedMime, typeOverride)
        } catch (error: Exception) {
            ImportOutcome(error = error.message ?: "Không thể lưu sticker")
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    /**
     * Persists a rendered sticker together with the exact mask and recipe used to render it.
     * The generated asset is immutable; a later edit creates another asset with a new dedup key.
     */
    fun createSticker(request: StickerSaveRequest): ImportOutcome {
        awaitReconciliation()
        return withStorageWriteLock { createStickerLocked(request) }
    }

    private fun createStickerLocked(request: StickerSaveRequest): ImportOutcome {
        val source = request.source
        if (!source.mimeType.startsWith("image/") || source.type == AssetType.GIF) {
            return ImportOutcome(error = "Sticker chỉ hỗ trợ ảnh tĩnh")
        }
        if (database.findById(source.id) == null || !managedOriginal(source)) {
            return ImportOutcome(error = "Không tìm thấy ảnh nguồn")
        }
        val sourceHash = runCatching { Hashing.sha256(File(source.originalPath)) }
            .getOrElse { return ImportOutcome(error = "Không đọc được ảnh nguồn") }
        if (sourceHash != source.hash) {
            return ImportOutcome(error = "Ảnh nguồn đã thay đổi; hãy nhập lại ảnh này")
        }
        val fileDimensions = readDimensions(File(source.originalPath))
        if (fileDimensions.first <= 0 || fileDimensions.second <= 0) {
            return ImportOutcome(error = "Không đọc được kích thước ảnh nguồn")
        }
        // Sticker Studio may downsample large images before segmentation. Crop coordinates are
        // intentionally tied to that decoded canvas, not the encoded file bounds.
        val sourceWidth = request.cropSourceWidth ?: fileDimensions.first
        val sourceHeight = request.cropSourceHeight ?: fileDimensions.second
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            sourceWidth > MaskCodec.MAX_DIMENSION || sourceHeight > MaskCodec.MAX_DIMENSION
        ) {
            return ImportOutcome(error = "Kích thước ảnh làm việc không hợp lệ")
        }
        val cropRect = request.cropRect
        if (cropRect != null && !cropRect.isValidFor(sourceWidth, sourceHeight)) {
            return ImportOutcome(error = "Vùng crop ảnh nguồn không hợp lệ")
        }
        val expectedWorkingWidth = cropRect?.width ?: sourceWidth
        val expectedWorkingHeight = cropRect?.height ?: sourceHeight
        if (request.maskWidth != expectedWorkingWidth || request.maskHeight != expectedWorkingHeight) {
            return ImportOutcome(error = "Kích thước mask không khớp vùng crop")
        }
        if (!request.renderedPng.isFile || request.renderedPng.length() == 0L) {
            return ImportOutcome(error = "Không tìm thấy sticker đã tạo")
        }
        if (request.renderedPng.length() > MAX_IMPORT_BYTES) {
            return ImportOutcome(error = "Sticker vượt quá giới hạn dung lượng")
        }
        if (request.maskWidth <= 0 || request.maskHeight <= 0 ||
            request.maskWidth > MaskCodec.MAX_DIMENSION || request.maskHeight > MaskCodec.MAX_DIMENSION ||
            request.confidenceMask.size != request.maskWidth * request.maskHeight
        ) {
            return ImportOutcome(error = "Mask sticker không hợp lệ")
        }
        if (!request.threshold.isFinite() || !request.paddingFraction.isFinite()) {
            return ImportOutcome(error = "Thông số recipe sticker không hợp lệ")
        }
        val safeThreshold = request.threshold.coerceIn(0f, 0.99f)
        val safePaddingFraction = request.paddingFraction.coerceIn(0f, 0.2f)
        val safeBorderThickness = StickerBorderPolicy.clampThickness(request.borderThicknessPx)

        val renderHash = runCatching { Hashing.sha256(request.renderedPng) }
            .getOrElse { return ImportOutcome(error = "Không đọc được sticker đã tạo") }
        val renderDimensions = readValidatedPngDimensions(request.renderedPng)
            ?: return ImportOutcome(error = "Sticker phải là PNG hợp lệ")
        val maskHash = runCatching {
            val probe = File.createTempFile("mask-probe-", ".png", stagingDirectory)
            try {
                MaskCodec.write(probe, request.confidenceMask, request.maskWidth, request.maskHeight)
            } finally {
                probe.delete()
            }
        }.getOrElse { return ImportOutcome(error = it.message ?: "Không mã hóa được mask") }
        val recipeFingerprint = Hashing.sha256Text(
            listOf(
                request.rendererId,
                request.rendererVersion,
                request.exportPolicyVersion,
                sourceHash,
                maskHash,
                request.maskWidth,
                request.maskHeight,
                safeThreshold,
                safePaddingFraction,
                request.borderColorArgb ?: "none",
                safeBorderThickness,
                cropRect?.left ?: 0,
                cropRect?.top ?: 0,
                cropRect?.right ?: 0,
                cropRect?.bottom ?: 0,
                sourceWidth,
                sourceHeight,
            ).joinToString("|"),
        )
        val dedupKey = "sticker|${source.id}|$recipeFingerprint|$renderHash"
        database.findByDedupKey(dedupKey)?.let { existing ->
            val merged = database.mergeTags(existing.id, request.tags) ?: existing
            return ImportOutcome(asset = merged, duplicate = true)
        }

        val id = UUID.randomUUID().toString()
        val target = File(assetsDirectory, "$id.png")
        val maskRelativePath = "$MASKS_DIRECTORY/$id.png"
        val maskTarget = File(mediaDirectory, maskRelativePath)
        val now = System.currentTimeMillis()
        val recipe = StickerRecipe(
            stickerAssetId = id,
            sourceAssetId = source.id,
            rendererId = request.rendererId,
            rendererVersion = request.rendererVersion,
            exportPolicyVersion = request.exportPolicyVersion,
            sourceHash = sourceHash,
            maskRelativePath = maskRelativePath,
            maskFormat = MaskCodec.FORMAT_GRAY8_PNG,
            maskWidth = request.maskWidth,
            maskHeight = request.maskHeight,
            maskHash = maskHash,
            workingWidth = request.maskWidth,
            workingHeight = request.maskHeight,
            outputWidth = renderDimensions.first,
            outputHeight = renderDimensions.second,
            threshold = safeThreshold,
            paddingFraction = safePaddingFraction,
            borderColorArgb = request.borderColorArgb,
            borderThicknessPx = safeBorderThickness,
            cropRect = cropRect,
            cropSourceWidth = sourceWidth,
            cropSourceHeight = sourceHeight,
            createdAt = now,
            updatedAt = now,
        )
        val asset = Asset(
            id = id,
            originalPath = target.absolutePath,
            mimeType = MediaPolicy.PNG_MIME,
            displayName = request.displayName,
            type = AssetType.STICKER,
            hash = renderHash,
            dedupKey = dedupKey,
            width = renderDimensions.first,
            height = renderDimensions.second,
            durationMs = 0L,
            favorite = false,
            createdAt = now,
            lastUsedAt = null,
            useCount = 0,
            tags = request.tags,
            sourceAssetId = source.id,
        )
        val stageDirectory = File(stagingDirectory, "sticker-$id")
        val stagedPng = File(stageDirectory, "sticker.png")
        val stagedMask = File(stageDirectory, "mask.png")
        try {
            check(stageDirectory.mkdirs() || stageDirectory.isDirectory) { "Không tạo được vùng staging" }
            copyToFinal(request.renderedPng, stagedPng)
            check(Hashing.sha256(stagedPng) == renderHash) { "Hash sticker thay đổi trong lúc staging" }
            check(readValidatedPngDimensions(stagedPng) == renderDimensions) {
                "Sticker staging không phải PNG hợp lệ"
            }
            val stagedMaskHash = MaskCodec.write(
                stagedMask,
                request.confidenceMask,
                request.maskWidth,
                request.maskHeight,
            )
            check(stagedMaskHash == maskHash) { "Mask hash thay đổi trong lúc staging" }
            target.parentFile?.mkdirs()
            maskTarget.parentFile?.mkdirs()
            promote(stagedPng, target)
            promote(stagedMask, maskTarget)
            if (!database.insertSticker(asset, recipe)) {
                target.delete()
                maskTarget.delete()
                database.findByDedupKey(dedupKey)?.let { existing ->
                    val merged = database.mergeTags(existing.id, request.tags) ?: existing
                    return ImportOutcome(asset = merged, duplicate = true)
                }
                return ImportOutcome(error = "Không lưu được metadata sticker")
            }
            return ImportOutcome(asset = asset)
        } catch (error: Exception) {
            target.delete()
            maskTarget.delete()
            return ImportOutcome(error = error.message ?: "Không thể lưu sticker")
        } finally {
            stageDirectory.deleteRecursively()
        }
    }

    fun importUris(uris: List<Uri>): List<ImportOutcome> = uris.mapIndexed { index, uri ->
        if (index < MAX_IMPORT_ITEMS) {
            importUri(uri)
        } else {
            ImportOutcome(error = "Mỗi lần chỉ nhập tối đa $MAX_IMPORT_ITEMS file")
        }
    }

    fun setFavorite(id: String, favorite: Boolean): Asset? {
        awaitReconciliation()
        database.setFavorite(id, favorite)
        return findById(id)
    }

    fun touchUsed(id: String) {
        awaitReconciliation()
        database.touchUsed(id)
    }

    fun delete(id: String): Boolean {
        awaitReconciliation()
        return withStorageWriteLock {
            val deletion = database.delete(id) ?: return@withStorageWriteLock false
            deleteManagedOriginal(deletion.asset.originalPath)
            deletion.variants.forEach { variant ->
                val file = File(variant.path)
                if (isManagedFile(file, variantsDirectory)) file.delete()
            }
            deletion.recipe?.maskRelativePath?.let { path ->
                runCatching { resolveMaskPath(path).delete() }
            }
            variantDirectory(id).deleteRecursively()
            true
        }
    }

    /** Renames an asset while keeping its extension; returns the updated asset or null. */
    fun rename(id: String, requestedBaseName: String): Asset? {
        awaitReconciliation()
        val current = findById(id) ?: return null
        val next = DisplayNamePolicy.rename(current.displayName, requestedBaseName) ?: return null
        if (next == current.displayName) return current
        return if (database.rename(id, next)) findById(id) else null
    }

    /** Bulk favorite toggle; returns how many rows were updated. */
    fun setFavorite(ids: Collection<String>, favorite: Boolean): Int {
        awaitReconciliation()
        return ids.count { database.setFavorite(it, favorite) }
    }

    /** Adds tags without removing existing ones; returns how many assets were updated. */
    fun addTags(ids: Collection<String>, tags: List<String>): Int {
        awaitReconciliation()
        if (TagNormalizer.normalizeAll(tags).isEmpty()) return 0
        return ids.count { database.mergeTags(it, tags) != null }
    }

    /** Deletes each asset independently so one failure does not hide the others. */
    fun delete(ids: Collection<String>): BulkResult {
        val failed = ids.filterNot { id -> runCatching { delete(id) }.getOrDefault(false) }
        return BulkResult(succeeded = ids.size - failed.size, failedIds = failed)
    }

    fun originalSize(asset: Asset): Long = runCatching {
        File(asset.originalPath).takeIf { managedOriginal(asset) }?.length() ?: 0L
    }.getOrDefault(0L)

    /** Counts and on-disk bytes per type; variants are reported separately as cache. */
    fun stats(): LibraryStats {
        val assets = list()
        val byType = assets.groupBy { it.type }.mapValues { (_, items) ->
            TypeStats(count = items.size, bytes = items.sumOf(::originalSize))
        }
        val variantBytes = withStorageReadLock {
            listFilesRecursively(variantsDirectory).sumOf { it.length() }
        }
        val maskBytes = withStorageReadLock {
            listFilesRecursively(masksDirectory).sumOf { it.length() }
        }
        return LibraryStats(
            totalCount = assets.size,
            originalBytes = byType.values.sumOf { it.bytes },
            variantBytes = variantBytes,
            maskBytes = maskBytes,
            byType = byType,
            favoriteCount = assets.count { it.favorite },
            neverUsedCount = assets.count { it.useCount == 0 },
            taggedCount = assets.count { it.tags.isNotEmpty() },
        )
    }

    fun sharedAsset(
        asset: Asset,
        destination: Destination,
        stickerProfile: StickerShareProfile = StickerShareProfile.OPAQUE_512,
    ): SharedAsset {
        awaitReconciliation()
        if (destination == Destination.TIKTOK && asset.type == AssetType.GIF) {
            val variant = ensureTikTokVideo(asset)
            return SharedAsset(
                uri = AssetFileProvider.uriFor(context, asset.id, VARIANT_TIKTOK),
                mimeType = MediaPolicy.MP4_MIME,
                displayName = "${asset.displayName.substringBeforeLast('.')}.mp4",
            ).also {
                check(variant.isFile) { "TikTok variant was not created" }
            }
        }
        if (MediaPolicy.needsStickerCompatibilityProfile(asset, destination)) {
            return when (stickerProfile) {
                StickerShareProfile.OPAQUE_512 -> {
                    val variant = ensureOpaqueImage(asset)
                    SharedAsset(
                        uri = AssetFileProvider.uriFor(context, asset.id, VARIANT_OPAQUE_IMAGE),
                        mimeType = MediaPolicy.PNG_MIME,
                        displayName = "${asset.displayName.substringBeforeLast('.', asset.displayName)}_share.png",
                    ).also {
                        check(variant.isFile) { "Opaque share variant was not created" }
                    }
                }

                StickerShareProfile.TRANSPARENT_320 -> {
                    val variant = ensureTransparentStickerImage(asset)
                    SharedAsset(
                        uri = AssetFileProvider.uriFor(context, asset.id, VARIANT_TRANSPARENT_STICKER_320),
                        mimeType = MediaPolicy.PNG_MIME,
                        displayName = "${asset.displayName.substringBeforeLast('.', asset.displayName)}_iphone.png",
                    ).also {
                        check(variant.isFile) { "Transparent 320 sticker variant was not created" }
                    }
                }
            }
        }
        return withStorageReadLock {
            SharedAsset(
                uri = AssetFileProvider.uriFor(context, asset.id),
                mimeType = MediaPolicy.normalizeMime(asset.mimeType),
                displayName = asset.displayName,
            )
        }
    }

    fun sharedAnimatedSticker(asset: Asset): SharedAsset {
        require(asset.type == AssetType.GIF) { "Chỉ GIF mới có bản sticker động thử nghiệm" }
        val variant = ensureAnimatedStickerWebp(asset)
        return SharedAsset(
            uri = AssetFileProvider.uriFor(context, asset.id, VARIANT_ANIMATED_STICKER_WEBP),
            mimeType = MediaPolicy.WEBP_MIME,
            displayName = "${asset.displayName.substringBeforeLast('.', asset.displayName)}_sticker.webp",
        ).also {
            check(variant.isFile) { "Animated WebP variant was not created" }
        }
    }

    fun ensureAnimatedStickerWebp(asset: Asset): File {
        require(asset.type == AssetType.GIF) { "Only GIF assets can build an animated WebP sticker variant" }
        awaitReconciliation()
        withStorageReadLock {
            database.findVariant(asset.id, VARIANT_ANIMATED_STICKER_WEBP)?.let { stored ->
                val existing = File(stored.path)
                if (existing.isFile && existing.length() > 0L && isManagedFile(existing, variantsDirectory)) {
                    return existing
                }
            }
        }
        val staged = runCatching {
            File.createTempFile("sticker-webp-", ".webp", stagingDirectory).also { output ->
                try {
                    GifToAnimatedWebpConverter.convert(File(asset.originalPath), output)
                    check(output.isFile && output.length() > 0L) { "WebP staging không hợp lệ" }
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            }
        }.getOrElse { error ->
            throw IOException("Không thể tạo bản sticker WebP: ${error.message ?: "lỗi chuyển đổi"}", error)
        }
        return try {
            commitStagedVariant(asset.id, VARIANT_ANIMATED_STICKER_WEBP, staged, MediaPolicy.WEBP_MIME)
        } finally {
            staged.delete()
        }
    }

    fun ensureTikTokVideo(asset: Asset): File {
        require(asset.type == AssetType.GIF) { "Only GIF assets need a TikTok video variant" }
        awaitReconciliation()
        withStorageReadLock {
            database.findVariant(asset.id, VARIANT_TIKTOK)?.let { stored ->
                val existing = File(stored.path)
                if (existing.isFile && existing.length() > 0L && isManagedFile(existing, variantsDirectory)) {
                    return existing
                }
            }
        }

        // GIF decoding/encoding can take longer than the reconciliation timeout. Do all CPU and
        // file work in a unique staging file, then take the shared write lock only for the final
        // recheck, atomic promote, and Room metadata write.
        val staged = runCatching {
            File.createTempFile("tiktok-", ".mp4", stagingDirectory).also { output ->
                try {
                    GifToMp4Converter.convert(File(asset.originalPath), output)
                    check(output.isFile && output.length() > 0L) { "Video staging không hợp lệ" }
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            }
        }.getOrElse { error ->
            throw IOException("Không thể tạo video TikTok: ${error.message ?: "lỗi chuyển đổi"}", error)
        }
        return try {
            commitStagedVariant(asset.id, VARIANT_TIKTOK, staged, MediaPolicy.MP4_MIME)
        } finally {
            staged.delete()
        }
    }

    internal fun variantFile(id: String, profile: String): File? {
        if (!SAFE_PROFILE.matches(profile)) return null
        awaitReconciliation()
        return withStorageReadLock {
            database.findById(id) ?: return@withStorageReadLock null
            val stored = database.findVariant(id, profile)
            val file = stored?.path?.let(::File) ?: variantDestination(id, profile)
            file.takeIf { it.isFile && it.canonicalFile.parentFile == variantDirectory(id).canonicalFile }
        }
    }

    internal fun originalFile(id: String): File? {
        awaitReconciliation()
        return withStorageReadLock {
            database.findById(id)?.let(::fileFor)?.takeIf { managedOriginalPath(it) }
        }
    }

    internal fun maskFile(relativePath: String): File? {
        awaitReconciliation()
        return withStorageReadLock {
            runCatching { resolveMaskPath(relativePath).takeIf(File::isFile) }.getOrNull()
        }
    }

    private fun persistImportedFile(
        temporaryFile: File,
        displayName: String,
        mimeType: String,
        typeOverride: AssetType? = null,
    ): ImportOutcome = withStorageWriteLock {
        persistImportedFileLocked(temporaryFile, displayName, mimeType, typeOverride)
    }

    private fun persistImportedFileLocked(
        temporaryFile: File,
        displayName: String,
        mimeType: String,
        typeOverride: AssetType? = null,
    ): ImportOutcome {
        if (temporaryFile.length() == 0L) return ImportOutcome(error = "File media rỗng")
        if (temporaryFile.length() > MAX_IMPORT_BYTES) {
            return ImportOutcome(error = "File vượt quá giới hạn ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
        }

        val resolvedType = typeOverride ?: AssetType.fromMime(mimeType, displayName)
        // Only explicit sticker flows (Zalo pull or Sticker Studio output) promise PNG. A regular
        // library import may still be inferred as STICKER from its name and can legitimately be a
        // WebP/other supported image, so do not reject it based on that heuristic alone.
        val requiresPngSticker = typeOverride == AssetType.STICKER
        val stickerDimensions = if (requiresPngSticker) {
            readValidatedPngDimensions(temporaryFile)
                ?: return ImportOutcome(error = "Sticker phải là PNG hợp lệ")
        } else {
            null
        }
        // Explicit sticker imports are persisted as PNG, even when a document provider reports a
        // stale or generic MIME type. The byte-level validation above is the source of truth.
        val persistedMimeType = if (requiresPngSticker) {
            MediaPolicy.PNG_MIME
        } else {
            mimeType
        }
        val hash = Hashing.sha256(temporaryFile)
        // A PNG pulled from a Zalo sticker folder must not collapse into a regular MEME that
        // happens to contain identical bytes. Sticker imports have their own dedup namespace;
        // repeated imports of the same sticker still resolve to one asset.
        val dedupKey = if (resolvedType == AssetType.STICKER) {
            "sticker-import|$hash"
        } else {
            hash
        }
        val existingDuplicate = database.findByDedupKey(dedupKey)
            ?: if (resolvedType == AssetType.STICKER) {
                // Before the storage-first schema, sticker imports used the raw content hash as
                // their dedup key. Fall back by hash and type so an old sticker is not imported a
                // second time, while a regular MEME with the same bytes remains independent.
                database.findByContentHashAndType(hash, AssetType.STICKER)
            } else {
                null
            }
        existingDuplicate?.let { existing ->
            return ImportOutcome(asset = existing, duplicate = true)
        }

        val id = UUID.randomUUID().toString()
        val extension = MediaPolicy.extensionFor(persistedMimeType)
        val target = File(assetsDirectory, "$id.$extension")
        try {
            if (!temporaryFile.renameTo(target)) {
                temporaryFile.copyTo(target, overwrite = false)
                temporaryFile.delete()
            }
            val dimensions = stickerDimensions ?: readDimensions(target, persistedMimeType)
            if (dimensions.first <= 0 || dimensions.second <= 0) {
                throw IOException("Không đọc được kích thước media")
            }
            val duration = readDuration(target, persistedMimeType)
            val asset = Asset(
                id = id,
                originalPath = target.absolutePath,
                mimeType = persistedMimeType,
                displayName = displayName,
                type = resolvedType,
                hash = hash,
                dedupKey = dedupKey,
                width = dimensions.first,
                height = dimensions.second,
                durationMs = duration,
                favorite = false,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = null,
                useCount = 0,
                tags = emptyList(),
            )
            if (!database.insert(asset)) {
                target.delete()
                return ImportOutcome(error = "Không lưu được metadata")
            }
            return ImportOutcome(asset = asset)
        } catch (error: Exception) {
            target.delete()
            return ImportOutcome(error = error.message ?: "Không thể lưu media")
        }
    }

    private fun fileFor(asset: Asset): File = File(asset.originalPath)

    private fun managedOriginal(asset: Asset): Boolean = managedOriginalPath(File(asset.originalPath))

    private fun managedOriginalPath(file: File): Boolean =
        isManagedFile(file, assetsDirectory)

    private fun deleteManagedOriginal(path: String) {
        val file = File(path)
        if (managedOriginalPath(file)) file.delete()
    }

    private fun isManagedFile(file: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val path = file.canonicalPath
        path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)

    private fun copyToFinal(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        check(target.isFile && target.length() > 0L) { "File media rỗng" }
    }

    /**
     * Promote a staged file on the same app-private filesystem. renameTo is atomic on the
     * normal path; the copy fallback keeps the flow usable on OEM filesystems that reject a
     * rename while still leaving a fully written file before the Room transaction starts.
     */
    private fun promote(staged: File, target: File) {
        require(staged.isFile && staged.length() > 0L) { "File staging không hợp lệ" }
        target.parentFile?.mkdirs()
        if (!staged.renameTo(target)) {
            copyToFinal(staged, target)
            staged.delete()
        }
        check(target.isFile && target.length() > 0L) { "File promote không hợp lệ" }
    }

    private fun isPng(file: File): Boolean {
        if (!file.isFile || file.length() < PNG_SIGNATURE.size) return false
        return runCatching {
            file.inputStream().use { input ->
                PNG_SIGNATURE.indices.all { index ->
                    input.read() == (PNG_SIGNATURE[index].toInt() and 0xff)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Verifies both the PNG signature and that Android can decode the image. Bounds are read first
     * to reject malformed headers and cap resource use; a sampled decode then catches truncated or
     * otherwise corrupt PNG payloads before a sticker is promoted into app storage.
     */
    private fun readValidatedPngDimensions(file: File): Pair<Int, Int>? {
        if (!isPng(file)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }.getOrNull()
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 ||
            width > MAX_VALIDATED_IMAGE_DIMENSION ||
            height > MAX_VALIDATED_IMAGE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_VALIDATED_IMAGE_PIXELS
        ) {
            return null
        }

        var sample = 1
        while (max(width, height) / sample > MAX_VALIDATED_DECODE_DIMENSION) {
            sample = sample shl 1
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        return try {
            if (bitmap.width <= 0 || bitmap.height <= 0) null else width to height
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun resolveMaskPath(relativePath: String): File {
        require(relativePath.startsWith("$MASKS_DIRECTORY/")) { "Mask path không hợp lệ" }
        val file = File(mediaDirectory, relativePath)
        check(file.canonicalPath.startsWith(masksDirectory.canonicalPath + File.separator)) {
            "Mask path nằm ngoài vùng lưu trữ"
        }
        return file
    }

    private fun variantDirectory(id: String): File = File(variantsDirectory, id)

    private fun reconcileStorage() {
        // This method runs under STORAGE_LOCK. Delete rows for originals removed by the
        // OS/file manager first; the DAO unlinks derived stickers, matching the user-facing
        // "source missing" policy. A failed reconcile is propagated to awaitReconciliation so
        // callers never continue as if the storage snapshot were safe.
        database.removeMissingOriginalRows(assetsDirectory.canonicalPath)
        val referencedOriginals = database.referencedOriginalPaths().map { File(it).canonicalPath }.toSet()
        val referencedVariants = database.referencedVariantPaths().map { File(it).canonicalPath }.toSet()
        listFilesRecursively(assetsDirectory)
            .filter { it.canonicalPath !in referencedOriginals }
            .forEach(File::delete)
        listFilesRecursively(variantsDirectory)
            .filter { it.canonicalPath !in referencedVariants }
            .forEach(File::delete)
        val referencedMasks = database.referencedMaskPaths()
            .mapNotNull { path -> runCatching { resolveMaskPath(path).canonicalPath }.getOrNull() }
            .toSet()
        listFilesRecursively(masksDirectory)
            .filter { it.canonicalPath !in referencedMasks }
            .forEach(File::delete)
        stagingDirectory.listFiles().orEmpty()
            .filter { System.currentTimeMillis() - it.lastModified() > STAGING_TTL_MS }
            .forEach { it.deleteRecursively() }
    }

    private fun listFilesRecursively(directory: File): List<File> =
        directory.walkTopDown().filter(File::isFile).toList()

    private fun readDimensions(file: File, mimeType: String = ""): Pair<Int, Int> {
        if (MediaPolicy.normalizeMime(mimeType).startsWith("video/")) {
            return readVideoDimensions(file)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    private fun readVideoDimensions(file: File): Pair<Int, Int> = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            VideoDimensions.oriented(width, height, rotation)
        } finally {
            retriever.release()
        }
    }.getOrDefault(0 to 0)

    private fun readDuration(file: File, mimeType: String): Long {
        if (!mimeType.startsWith("video/")) return 0L
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun mimeFromName(name: String): String = when {
        name.lowercase().endsWith(".png") -> MediaPolicy.PNG_MIME
        name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") -> MediaPolicy.JPEG_MIME
        name.lowercase().endsWith(".gif") -> MediaPolicy.GIF_MIME
        name.lowercase().endsWith(".mp4") -> MediaPolicy.MP4_MIME
        name.lowercase().endsWith(".webp") -> "image/webp"
        name.lowercase().endsWith(".avif") -> "image/avif"
        name.lowercase().endsWith(".heic") -> "image/heic"
        name.lowercase().endsWith(".heif") -> "image/heif"
        name.lowercase().endsWith(".bmp") -> "image/bmp"
        else -> ""
    }

    override fun close() {
        runCatching { reconcileTask.get(RECONCILE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        reconcileExecutor.shutdownNow()
        database.close()
    }

    @Suppress("UseKtx")
    private fun ensureOpaqueImage(asset: Asset): File {
        val existing = withStorageReadLock {
            database.findVariant(asset.id, VARIANT_OPAQUE_IMAGE)?.let { stored ->
                File(stored.path).takeIf {
                    it.isFile && it.length() > 0L && isManagedFile(it, variantsDirectory)
                }
            }
        }
        if (existing != null) return existing

        // Render outside the shared write lock. Only the final publish and DB row need to be
        // serialized with reconcile/delete; this keeps large images from blocking new screens.
        val staged = File.createTempFile("opaque-", ".png", stagingDirectory)
        val source = StickerImageLoader.decode(File(asset.originalPath), MAX_SHARE_DIMENSION)
            ?: run {
                staged.delete()
                throw IOException("Không thể đọc ảnh để tạo bản tương thích")
            }
        var output: Bitmap? = null
        return try {
            val layout = HostShareCanvasPolicy.layout(source.width, source.height)
            // Use an opaque square canvas without carrying over source alpha or EXIF. The padding
            // keeps Zalo/TikTok from treating a tightly cropped subject as a full-width sticker.
            output = Bitmap.createBitmap(
                layout.canvasSize,
                layout.canvasSize,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(output!!).apply {
                drawColor(Color.WHITE)
                val left = layout.left.toFloat()
                val top = layout.top.toFloat()
                drawBitmap(
                    source,
                    null,
                    RectF(
                        left,
                        top,
                        left + layout.contentWidth,
                        top + layout.contentHeight,
                    ),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
                )
            }
            FileOutputStream(staged).use { stream ->
                check(output!!.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Không mã hóa được ảnh chia sẻ"
                }
            }
            check(staged.isFile && staged.length() > 0L) { "Ảnh staging không hợp lệ" }
            commitStagedVariant(asset.id, VARIANT_OPAQUE_IMAGE, staged, MediaPolicy.PNG_MIME)
        } catch (error: Exception) {
            throw IOException(error.message ?: "Không thể tạo ảnh chia sẻ", error)
        } finally {
            staged.delete()
            if (!source.isRecycled) source.recycle()
            output?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Suppress("UseKtx")
    private fun ensureTransparentStickerImage(asset: Asset): File {
        val existing = withStorageReadLock {
            database.findVariant(asset.id, VARIANT_TRANSPARENT_STICKER_320)?.let { stored ->
                File(stored.path).takeIf {
                    it.isFile && it.length() > 0L && isManagedFile(it, variantsDirectory)
                }
            }
        }
        if (existing != null) return existing

        val staged = File.createTempFile("transparent-", ".png", stagingDirectory)
        val source = StickerImageLoader.decode(File(asset.originalPath), MAX_IOS_STICKER_DIMENSION)
            ?: run {
                staged.delete()
                throw IOException("Không thể đọc ảnh để tạo sticker 320")
            }
        var output: Bitmap? = null
        return try {
            val layout = IosStickerCanvasPolicy.layout(source.width, source.height)
            // Keep the alpha canvas and the user's existing outline. Do not copy iDOT/EXIF from the
            // source: those are encoder/cache metadata, not the iPhone sticker registration contract.
            output = Bitmap.createBitmap(
                layout.canvasSize,
                layout.canvasSize,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(output!!).apply {
                val left = layout.left.toFloat()
                val top = layout.top.toFloat()
                drawBitmap(
                    source,
                    null,
                    RectF(
                        left,
                        top,
                        left + layout.contentWidth,
                        top + layout.contentHeight,
                    ),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
                )
            }
            FileOutputStream(staged).use { stream ->
                check(output!!.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Không mã hóa được sticker PNG 320"
                }
            }
            check(staged.isFile && staged.length() > 0L) { "Sticker staging không hợp lệ" }
            commitStagedVariant(asset.id, VARIANT_TRANSPARENT_STICKER_320, staged, MediaPolicy.PNG_MIME)
        } catch (error: Exception) {
            throw IOException(error.message ?: "Không thể tạo sticker PNG 320", error)
        } finally {
            staged.delete()
            if (!source.isRecycled) source.recycle()
            output?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun variantDestination(id: String, profile: String): File {
        val extension = when (profile) {
            VARIANT_TIKTOK -> "mp4"
            VARIANT_ANIMATED_STICKER_WEBP -> "webp"
            else -> "png"
        }
        return File(File(variantsDirectory, id), "$profile.$extension")
    }

    /** Atomically publishes a fully-written staged variant and its DB row under one lock. */
    private fun commitStagedVariant(
        assetId: String,
        profile: String,
        staged: File,
        mimeType: String,
    ): File = withStorageWriteLock {
        database.findVariant(assetId, profile)?.let { stored ->
            val existing = File(stored.path)
            if (existing.isFile && existing.length() > 0L && isManagedFile(existing, variantsDirectory)) {
                return@withStorageWriteLock existing
            }
        }
        val destination = variantDestination(assetId, profile)
        if (destination.isFile && destination.length() > 0L) {
            database.saveVariant(
                assetId = assetId,
                profile = profile,
                path = destination.absolutePath,
                mimeType = mimeType,
                fileSize = destination.length(),
            )
            return@withStorageWriteLock destination
        }
        try {
            promote(staged, destination)
            database.saveVariant(
                assetId = assetId,
                profile = profile,
                path = destination.absolutePath,
                mimeType = mimeType,
                fileSize = destination.length(),
            )
            destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private inline fun <T> withStorageWriteLock(block: () -> T): T {
        STORAGE_LOCK.writeLock().lock()
        return try {
            block()
        } finally {
            STORAGE_LOCK.writeLock().unlock()
        }
    }

    private inline fun <T> withStorageReadLock(block: () -> T): T {
        STORAGE_LOCK.readLock().lock()
        return try {
            block()
        } finally {
            STORAGE_LOCK.readLock().unlock()
        }
    }

    companion object {
        const val MEDIA_DIRECTORY = "media"
        const val ORIGINALS_DIRECTORY = "originals"
        const val STAGING_DIRECTORY = "staging"
        const val VARIANTS_DIRECTORY = "variants"
        const val MASKS_DIRECTORY = "masks"
        const val VARIANT_TIKTOK = "tiktok"
        const val VARIANT_OPAQUE_IMAGE = "opaque_share_v3"
        const val VARIANT_TRANSPARENT_STICKER_320 = "transparent_sticker_320_v1"
        const val VARIANT_ANIMATED_STICKER_WEBP = "animated_sticker_webp_512_v1"
        private const val VARIANT_OPAQUE_IMAGE_PREVIOUS = "opaque_share_v2"
        private const val VARIANT_OPAQUE_IMAGE_LEGACY = "opaque_share"
        private const val MAX_SHARE_DIMENSION = 2048
        private const val MAX_IOS_STICKER_DIMENSION = 1024
        private const val MAX_IMPORT_BYTES = 50L * 1024L * 1024L
        private const val MAX_IMPORT_ITEMS = 50
        private const val MAX_VALIDATED_IMAGE_DIMENSION = 16_384
        private const val MAX_VALIDATED_IMAGE_PIXELS = 64L * 1024L * 1024L
        private const val MAX_VALIDATED_DECODE_DIMENSION = 1_024
        private const val STAGING_TTL_MS = 24L * 60L * 60L * 1000L
        private const val RECONCILE_TIMEOUT_SECONDS = 15L
        private val STORAGE_LOCK = ReentrantReadWriteLock(true)
        private val SAFE_PROFILE = Regex("[A-Za-z0-9_.-]{1,64}")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
