package com.memevault.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class AssetFileProvider : ContentProvider() {
    private lateinit var repository: AssetRepository

    override fun onCreate(): Boolean {
        repository = AssetRepository(requireNotNull(context))
        return true
    }

    override fun getType(uri: Uri): String? = resolve(uri)?.second

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "MemeVault provider is read-only" }
        val file = resolve(uri)?.first ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val resolved = resolve(uri) ?: return null
        val file = resolved.first
        val asset = repository.findById(uri.pathSegments.getOrNull(1).orEmpty())
        val profile = uri.pathSegments.getOrNull(2)
        val originalName = asset?.displayName ?: file.name
        val variant = profile?.let { repository.findVariant(uri.pathSegments[1], it) }
        val displayName = when (profile) {
            AssetRepository.VARIANT_TIKTOK -> "${originalName.substringBeforeLast('.', originalName)}.mp4"
            AssetRepository.VARIANT_OPAQUE_IMAGE -> "${originalName.substringBeforeLast('.', originalName)}_share.png"
            AssetRepository.VARIANT_TRANSPARENT_STICKER_320 -> "${originalName.substringBeforeLast('.', originalName)}_iphone.png"
            AssetRepository.VARIANT_ANIMATED_STICKER_WEBP -> "${originalName.substringBeforeLast('.', originalName)}_sticker.webp"
            else -> variant?.let {
                "${originalName.substringBeforeLast('.', originalName)}_${it.profile}.${it.mimeType.substringAfter('/', "bin")}"
            } ?: originalName
        }
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = MatrixCursor(columns)
        val values: Array<Any?> = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> displayName
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray()
        row.addRow(values)
        return row
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = unsupported()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MemeVault provider is read-only")

    private fun resolve(uri: Uri): Pair<File, String>? {
        if (uri.authority != authorityFor(context)) return null
        val segments = uri.pathSegments
        if (segments.size !in 2..3 || segments[0] != "assets") return null
        val id = segments[1]
        if (!UUID_PATTERN.matches(id)) return null
        val profile = segments.getOrNull(2)
        val file = if (profile == null) {
            repository.originalFile(id)
        } else {
            repository.variantFile(id, profile)
        } ?: return null
        val mime = profile?.let { repository.findVariant(id, it)?.mimeType }
            ?: repository.findById(id)?.mimeType
            ?: return null
        return file.takeIf(File::isFile)?.let { it to mime }
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")

        fun authorityFor(context: android.content.Context?): String =
            "${requireNotNull(context).packageName}.fileprovider"

        fun uriFor(context: android.content.Context, id: String, profile: String? = null): Uri {
            val builder = Uri.Builder()
                .scheme("content")
                .authority(authorityFor(context))
                .appendPath("assets")
                .appendPath(id)
            if (profile != null) builder.appendPath(profile)
            return builder.build()
        }
    }
}
