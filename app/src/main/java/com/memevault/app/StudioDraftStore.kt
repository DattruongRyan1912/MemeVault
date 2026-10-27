package com.memevault.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private, short-lived mask checkpoint used to restore Sticker Studio after process death. */
internal class StudioDraftStore(cacheDirectory: File, val id: String) {
    init {
        require(SAFE_ID.matches(id)) { "Draft id không hợp lệ" }
    }

    private val root = File(cacheDirectory, ROOT_DIRECTORY)
    private val directory = File(root, id)
    private val maskFile = File(directory, MASK_FILE)

    fun saveMask(mask: FloatArray, width: Int, height: Int) {
        check(directory.mkdirs() || directory.isDirectory) { "Không tạo được vùng lưu draft" }
        val staged = File(directory, "$MASK_FILE.part")
        try {
            MaskCodec.write(staged, mask, width, height)
            try {
                Files.move(
                    staged.toPath(),
                    maskFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    staged.toPath(),
                    maskFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            directory.setLastModified(System.currentTimeMillis())
        } finally {
            staged.delete()
        }
    }

    fun loadMask(expectedWidth: Int, expectedHeight: Int): FloatArray? = runCatching {
        MaskCodec.read(maskFile, expectedWidth, expectedHeight)
    }.getOrNull()

    fun clear() {
        if (directory.isDirectory && directory.canonicalFile.parentFile == root.canonicalFile) {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val ROOT_DIRECTORY = "sticker-studio-drafts"
        private const val MASK_FILE = "mask.png"
        private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private val SAFE_ID = Regex("[0-9a-fA-F-]{36}")

        fun cleanupStale(cacheDirectory: File, now: Long = System.currentTimeMillis()) {
            val root = File(cacheDirectory, ROOT_DIRECTORY)
            root.listFiles().orEmpty()
                .filter { it.isDirectory && SAFE_ID.matches(it.name) }
                .filter { now - it.lastModified() > MAX_AGE_MS }
                .forEach { candidate ->
                    if (candidate.canonicalFile.parentFile == root.canonicalFile) {
                        candidate.deleteRecursively()
                    }
                }
        }
    }
}
