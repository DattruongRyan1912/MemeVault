package com.memevault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Stores the ML confidence mask in a compact, deterministic format that can be regenerated into
 * FloatArray values by Sticker Studio. The mask is intentionally app-private and never exposed
 * through AssetFileProvider.
 */
object MaskCodec {
    const val FORMAT_GRAY8_PNG = "gray8-png-v1"
    const val MAX_DIMENSION = 2048
    const val MAX_BYTES = 16L * 1024L * 1024L

    fun write(file: File, mask: FloatArray, width: Int, height: Int): String {
        require(width > 0 && height > 0) { "Kích thước mask không hợp lệ" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) { "Mask vượt giới hạn kích thước" }
        require(mask.size == width * height) { "Mask không khớp kích thước ảnh" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(mask.size) { index ->
                val confidence = mask[index].takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
                val value = (confidence * 255f).toInt().coerceIn(0, 255)
                Color.rgb(value, value, value)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Không mã hóa được mask"
                }
            }
            check(file.length() in 1..MAX_BYTES) { "Mask vượt giới hạn dung lượng" }
            return Hashing.sha256(file)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun read(file: File, expectedWidth: Int, expectedHeight: Int): FloatArray {
        require(file.isFile && file.length() in 1..MAX_BYTES) { "Không tìm thấy mask" }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IOException("Không đọc được mask")
        try {
            require(bitmap.width == expectedWidth && bitmap.height == expectedHeight) {
                "Kích thước mask không khớp ảnh"
            }
            val pixels = IntArray(expectedWidth * expectedHeight)
            bitmap.getPixels(pixels, 0, expectedWidth, 0, 0, expectedWidth, expectedHeight)
            return FloatArray(pixels.size) { index -> Color.red(pixels[index]) / 255f }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
