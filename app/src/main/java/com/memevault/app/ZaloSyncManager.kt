package com.memevault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale

/**
 * User-granted, URI-based bridge for a Zalo photo_sticker/recent_sticker directory.
 *
 * This deliberately uses Storage Access Framework instead of guessing a private Zalo path. The
 * selected directory is persisted only as a document-tree URI. Whether Zalo indexes copied files
 * as native stickers remains a host-app behavior and is not asserted here.
 */
class ZaloSyncManager(
    context: Context,
    private val repository: AssetRepository,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun savedTreeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)
        ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
        ?.takeIf { DocumentsContract.isTreeUri(it) }

    fun rememberTreeUri(uri: Uri, grantedFlags: Int): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        val persistableFlags = grantedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if ((persistableFlags and requiredFlags) != requiredFlags) return false
        return runCatching {
            resolver.takePersistableUriPermission(uri, persistableFlags)
            preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            true
        }.getOrDefault(false)
    }

    fun forgetTreeUri() {
        savedTreeUri()?.let { uri ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    fun displayName(uri: Uri): String {
        return runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            ?: "Thư mục đã chọn"
    }

    /**
     * Pulls PNGs from the selected directory into the vault and pushes the stickers that existed
     * before the pull back to that directory. Existing files are never overwritten or deleted.
     */
    fun sync(): SyncSummary {
        val treeUri = savedTreeUri() ?: throw SyncException("Chưa chọn thư mục photo_sticker của Zalo")
        val target = resolveSyncFolder(treeUri)
        cleanupRecordedStagingDocuments()
        val localBeforePull = repository.list(AssetFilter.STICKERS)
        val remoteBeforePull = listPngDocuments(target)
        pruneRemoteMappings(remoteBeforePull)
        var pulled = 0
        var alreadyInVault = 0
        var pushed = 0
        var alreadyOnDevice = 0
        val remoteAssetIds = mutableSetOf<String>()
        val errors = mutableListOf<String>()

        remoteBeforePull.forEach { document ->
            val remoteHash = runCatching { hashRemoteDocument(document.uri) }.getOrElse { error ->
                addError(
                    errors,
                    "Không đọc được ${shortName(document.displayName)}: ${error.message ?: "lỗi không xác định"}",
                )
                return@forEach
            }
            val mapping = remoteMapping(document.uri)
            if (mapping != null) {
                val mappedAsset = repository.findById(mapping.assetId)
                // The remote file is the destination-specific 320x320 variant, so its hash is not
                // expected to equal the source asset hash stored in Room. The persisted mapping
                // owns the remote hash and is the identity check for this exact published URI.
                if (mappedAsset?.type == AssetType.STICKER && mapping.hash == remoteHash) {
                    remoteAssetIds += mappedAsset.id
                    alreadyInVault++
                    return@forEach
                }
                forgetRemoteMapping(document.uri)
            }
            val managedAssetId = managedAssetId(document.displayName)
            val managedAsset = managedAssetId?.let(repository::findById)
            if (managedAsset?.type == AssetType.STICKER) {
                val localPublishedHash = runCatching {
                    val localVariant = repository.sharedAsset(
                        managedAsset,
                        Destination.ZALO,
                        StickerShareProfile.TRANSPARENT_320,
                    )
                    hashRemoteDocument(localVariant.uri)
                }.getOrNull()
                if (localPublishedHash == remoteHash) {
                    remoteAssetIds += managedAsset.id
                    rememberRemoteMapping(document.uri, managedAsset.id, remoteHash)
                    alreadyInVault++
                    return@forEach
                }
            }
            val outcome = repository.importUriAsSticker(document.uri)
            when {
                outcome.asset != null && outcome.duplicate -> {
                    remoteAssetIds += outcome.asset.id
                    rememberRemoteMapping(document.uri, outcome.asset.id, remoteHash)
                    alreadyInVault++
                }
                outcome.asset != null -> {
                    remoteAssetIds += outcome.asset.id
                    rememberRemoteMapping(document.uri, outcome.asset.id, remoteHash)
                    pulled++
                }
                else -> addError(
                    errors,
                    "Không nhập được ${shortName(document.displayName)}: ${outcome.error ?: "lỗi không xác định"}",
                )
            }
        }

        // Re-query after the pull so we do not create a second copy when a managed file already
        // exists under the same stable filename.
        val remoteAfterPull = listPngDocuments(target).associateBy { it.displayName }
        localBeforePull.forEach { asset ->
            val fileName = exportFileName(asset.id)
            if (asset.id in remoteAssetIds || remoteAfterPull.containsKey(fileName)) {
                alreadyOnDevice++
                return@forEach
            }
            try {
                val stagingName = ".memevault-${asset.id}.part"
                val parentUri = parentDocumentUri(target)
                val stagingUri = DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    MediaPolicy.PNG_MIME,
                    stagingName,
                )
                    ?: throw IOException("Document provider không tạo được file tạm")
                rememberStagingUri(stagingUri, asset.id)
                var publishedUri: Uri? = null
                try {
                    copyStickerToDocument(asset, stagingUri)
                    publishedUri = DocumentsContract.renameDocument(resolver, stagingUri, fileName)
                        ?: throw IOException("Document provider không publish được file sticker")
                    // Hash the bytes at the final URI rather than asset.hash: Zalo receives the
                    // transparent 320x320 variant, not the original asset bytes.
                    val publishedHash = hashRemoteDocument(publishedUri)
                    rememberRemoteMapping(publishedUri, asset.id, publishedHash)
                    forgetStagingUri(stagingUri)
                    pushed++
                } catch (error: Exception) {
                    runCatching {
                        DocumentsContract.deleteDocument(resolver, publishedUri ?: stagingUri)
                    }
                    forgetStagingUri(stagingUri)
                    throw error
                }
            } catch (error: Exception) {
                addError(
                    errors,
                    "Không đẩy được ${shortName(asset.displayName)}: ${error.message ?: "lỗi không xác định"}",
                )
            }
        }

        return SyncSummary(
            pulled = pulled,
            alreadyInVault = alreadyInVault,
            pushed = pushed,
            alreadyOnDevice = alreadyOnDevice,
            errors = errors,
        )
    }

    private fun copyStickerToDocument(asset: Asset, destination: Uri) {
        val shared = repository.sharedAsset(
            asset,
            Destination.ZALO,
            StickerShareProfile.TRANSPARENT_320,
        )
        val input = resolver.openInputStream(shared.uri)
            ?: throw IOException("Không đọc được PNG sticker")
        val output = resolver.openOutputStream(destination, "w")
            ?: throw IOException("Không mở được file đích")
        input.use { source ->
            output.use { target ->
                val bytes = source.copyTo(target)
                target.flush()
                if (bytes <= 0L) throw IOException("PNG sticker rỗng")
            }
        }
    }

    private fun resolveSyncFolder(treeUri: Uri): SyncFolder {
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw SyncException("URI thư mục không hợp lệ; hãy chọn lại thư mục Zalo") }
        val rootName = displayName(treeUri)
        if (rootName.equals(RECENT_STICKER_DIRECTORY, ignoreCase = true)) {
            return SyncFolder(treeUri, rootDocumentId)
        }
        val recent = listChildDocuments(treeUri, rootDocumentId)
            .firstOrNull {
                it.displayName.equals(RECENT_STICKER_DIRECTORY, ignoreCase = true) &&
                    (it.isDirectory || !isPngDocument(it.displayName, it.mimeType))
            }
        return recent?.let { SyncFolder(treeUri, it.documentId) }
            ?: SyncFolder(treeUri, rootDocumentId)
    }

    private fun listPngDocuments(folder: SyncFolder): List<RemoteDocument> =
        listChildDocuments(folder.treeUri, folder.documentId)
            .filter { isPngDocument(it.displayName, it.mimeType) }
            .map { child ->
                RemoteDocument(
                    uri = child.uri,
                    displayName = child.displayName,
                )
            }

    private fun listChildDocuments(treeUri: Uri, parentDocumentId: String): List<ChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        return try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0) {
                    throw SyncException("Document provider không trả về danh sách file")
                }
                buildList {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idIndex).orEmpty()
                        val name = cursor.getString(nameIndex).orEmpty()
                        val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                        if (documentId.isNotBlank()) {
                            add(
                                ChildDocument(
                                    documentId = documentId,
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                    displayName = name,
                                    mimeType = mime,
                                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                ),
                            )
                        }
                    }
                }
            } ?: throw SyncException("Không đọc được thư mục đã chọn; hãy cấp lại quyền")
        } catch (error: SecurityException) {
            throw SyncException("Android đã từ chối quyền đọc thư mục; hãy chọn lại thư mục Zalo", error)
        } catch (error: IllegalArgumentException) {
            throw SyncException("Thư mục không còn tồn tại; hãy chọn lại thư mục Zalo", error)
        }
    }

    private fun parentDocumentUri(folder: SyncFolder): Uri = DocumentsContract.buildDocumentUriUsingTree(
        folder.treeUri,
        folder.documentId,
    )

    private fun hashRemoteDocument(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(PNG_SIGNATURE.size)
            var headerBytes = 0
            while (headerBytes < header.size) {
                val read = input.read(header, headerBytes, header.size - headerBytes)
                if (read <= 0) break
                headerBytes += read
            }
            if (headerBytes != header.size || !header.contentEquals(PNG_SIGNATURE)) {
                throw IOException("File không có chữ ký PNG hợp lệ")
            }
            digest.update(header)
            total = headerBytes.toLong()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_REMOTE_BYTES) {
                    throw IOException("File PNG vượt quá giới hạn ${MAX_REMOTE_BYTES / (1024 * 1024)} MB")
                }
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("Document provider không mở được file")
        if (total == 0L) throw IOException("File PNG rỗng")
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun remoteMapping(uri: Uri): RemoteMapping? {
        val raw = preferences.getString(mappingKey(uri), null) ?: return null
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].length != 64) return null
        return RemoteMapping(parts[0], parts[1])
    }

    private fun rememberRemoteMapping(uri: Uri, assetId: String, hash: String) {
        preferences.edit().putString(mappingKey(uri), "$assetId|$hash").commit()
    }

    private fun forgetRemoteMapping(uri: Uri) {
        preferences.edit().remove(mappingKey(uri)).apply()
    }

    /** Persist a marker before writing so a process death cannot leave an untracked .part file. */
    private fun rememberStagingUri(uri: Uri, assetId: String) {
        preferences.edit().putString(stagingMappingKey(uri), assetId).commit()
    }

    private fun forgetStagingUri(uri: Uri) {
        preferences.edit().remove(stagingMappingKey(uri)).commit()
    }

    /**
     * Removes only exact staging URIs previously recorded by MemeVault. We query the current
     * display name first: some providers keep the same document URI after rename, and deleting it
     * in that state would remove the successfully published sticker instead of a stale .part file.
     */
    private fun cleanupRecordedStagingDocuments() {
        val stagingKeys = preferences.all.keys
            .filter { it.startsWith(STAGING_MAPPING_PREFIX) }
        stagingKeys.forEach { key ->
            val encodedUri = key.removePrefix(STAGING_MAPPING_PREFIX)
            val uri = runCatching { Uri.parse(Uri.decode(encodedUri)) }.getOrNull()
            if (uri == null) {
                preferences.edit().remove(key).commit()
                return@forEach
            }
            try {
                val cursor = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                ) ?: return@forEach
                val displayName = cursor.use { result ->
                    if (result.moveToFirst()) result.getString(0).orEmpty() else null
                }
                if (displayName == null) {
                    // A valid provider cursor with no row means the document is already gone;
                    // a null cursor means the provider could not answer, so retain the marker.
                    preferences.edit().remove(key).commit()
                    return@forEach
                }
                if (!isStagingFileName(displayName)) {
                    // Missing documents and already-renamed documents are both safe to forget;
                    // the latter remains discoverable through the normal remote scan/mapping.
                    preferences.edit().remove(key).commit()
                    return@forEach
                }
                if (DocumentsContract.deleteDocument(resolver, uri)) {
                    preferences.edit().remove(key).commit()
                }
            } catch (_: FileNotFoundException) {
                preferences.edit().remove(key).commit()
            } catch (_: IllegalArgumentException) {
                preferences.edit().remove(key).commit()
            } catch (_: SecurityException) {
                // Keep the marker so a later sync can retry after the user restores the grant.
            }
        }
    }

    private fun pruneRemoteMappings(documents: List<RemoteDocument>) {
        val currentKeys = documents.mapTo(mutableSetOf()) { mappingKey(it.uri) }
        val staleKeys = preferences.all.keys
            .filter { it.startsWith(REMOTE_MAPPING_PREFIX) && it !in currentKeys }
        if (staleKeys.isNotEmpty()) {
            preferences.edit().apply {
                staleKeys.forEach(::remove)
            }.apply()
        }
    }

    private fun mappingKey(uri: Uri): String = REMOTE_MAPPING_PREFIX + Uri.encode(uri.toString())

    private fun stagingMappingKey(uri: Uri): String =
        STAGING_MAPPING_PREFIX + Uri.encode(uri.toString())

    private fun addError(errors: MutableList<String>, message: String) {
        if (errors.size < MAX_REPORTED_ERRORS) {
            errors += message.trim().ifBlank { "Lỗi không xác định" }
        }
    }

    data class RemoteDocument(
        val uri: Uri,
        val displayName: String,
    )

    private data class ChildDocument(
        val documentId: String,
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        val isDirectory: Boolean,
    )

    private data class SyncFolder(
        val treeUri: Uri,
        val documentId: String,
    )

    data class SyncSummary(
        val pulled: Int,
        val alreadyInVault: Int,
        val pushed: Int,
        val alreadyOnDevice: Int,
        val errors: List<String>,
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
    }

    class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

    override fun close() {
        repository.close()
    }

    companion object {
        private const val PREFERENCES = "zalo_sync"
        private const val KEY_TREE_URI = "tree_uri"
        private const val REMOTE_MAPPING_PREFIX = "remote_mapping_"
        private const val STAGING_MAPPING_PREFIX = "staging_mapping_"
        private const val MAX_REPORTED_ERRORS = 6
        private const val MAX_REMOTE_BYTES = 50L * 1024L * 1024L
        private const val RECENT_STICKER_DIRECTORY = "recent_sticker"
        private val MANAGED_NAME = Regex("^memevault-([0-9a-fA-F-]{36})\\.png$", RegexOption.IGNORE_CASE)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        internal fun isPngDocument(displayName: String, mimeType: String?): Boolean {
            if (isStagingFileName(displayName)) {
                return false
            }
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                mime == MediaPolicy.PNG_MIME -> true
                mime.isNotBlank() && mime != "application/octet-stream" -> false
                else -> displayName.lowercase(Locale.ROOT).endsWith(".png")
            }
        }

        internal fun exportFileName(assetId: String): String {
            val safeId = assetId.takeIf { UUID_PATTERN.matches(it) }
                ?: assetId.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifBlank { "sticker" }
            return "memevault-$safeId.png"
        }

        internal fun managedAssetId(displayName: String): String? =
            MANAGED_NAME.matchEntire(displayName)?.groupValues?.getOrNull(1)

        private fun isStagingFileName(displayName: String): Boolean =
            displayName.startsWith(".memevault-", ignoreCase = true) &&
                displayName.endsWith(".part", ignoreCase = true)

        private fun shortName(value: String): String = value
            .replace(Regex("[\\r\\n\\t]"), " ")
            .take(80)
            .ifBlank { "file PNG" }

        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }

    private data class RemoteMapping(
        val assetId: String,
        val hash: String,
    )
}
