package com.memevault.app

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetFileProviderInstrumentedTest {
    @Test
    fun importedPngIsReadableThroughContentUriWithExactMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "provider-$token.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 17, 113, 211))
            token.forEachIndexed { index, character ->
                setPixel(
                    index % width,
                    index / width,
                    Color.argb(255, character.code, index * 7, index * 13),
                )
            }
        }
        source.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        val sourceBytes = source.readBytes()

        try {
            AssetRepository(context).use { repository ->
                val outcome = repository.importGeneratedFile(
                    source = source,
                    displayName = "provider-test.png",
                )
                val asset = requireNotNull(outcome.asset) { outcome.error ?: "Import failed" }
                assertFalse(outcome.duplicate)

                try {
                    val uri = AssetFileProvider.uriFor(context, asset.id)
                    assertEquals(MediaPolicy.PNG_MIME, context.contentResolver.getType(uri))
                    val sharedBytes = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        it.readBytes()
                    }
                    assertArrayEquals(sourceBytes, sharedBytes)

                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        requireNotNull(cursor)
                        assertTrue(cursor.moveToFirst())
                        assertEquals("provider-test.png", cursor.getString(0))
                        assertEquals(sharedBytes.size.toLong(), cursor.getLong(1))
                    }
                } finally {
                    assertTrue(repository.delete(asset.id))
                }
            }
        } finally {
            source.delete()
        }
    }
}
