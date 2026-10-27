package com.memevault.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Hand-written vector path data only fails when inflated, so inflate every icon. */
    @Test
    fun everyVectorIconInflates() {
        val icons = R.drawable::class.java.fields.filter { it.name.startsWith("ic_") }
        assertTrue("expected the icon set to be present", icons.size >= 15)
        icons.forEach { field ->
            val drawable = context.getDrawable(field.getInt(null))
            assertNotNull("icon ${field.name} did not inflate", drawable)
            assertTrue("icon ${field.name} has no size", drawable!!.intrinsicWidth > 0)
        }
    }

    @Test
    fun copyClipCarriesTheMediaMimeType() {
        val shared = SharedAsset(
            uri = android.net.Uri.parse("content://${context.packageName}.fileprovider/assets/x"),
            mimeType = "image/png",
            displayName = "x.png",
        )
        val clip = ShareManager.clipFor(shared)
        assertTrue(clip.description.hasMimeType("image/png"))
        assertTrue(clip.description.hasMimeType("image/*"))
        assertFalse(clip.description.hasMimeType("text/uri-list"))
        assertEquals(shared.uri, clip.getItemAt(0).uri)
    }

    @Test
    fun renameBulkTagFavoriteAndDeleteRoundTrip() {
        val repository = AssetRepository(context)
        val ids = mutableListOf<String>()
        try {
            repeat(2) { index ->
                val token = UUID.randomUUID().toString()
                val source = File(context.cacheDir, "library-ui-$token.png")
                val bitmap = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(10 + index, token.hashCode() and 0xFF, 99))
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val outcome = repository.importGeneratedFile(source, "seed-$token.png", "image/png", null)
                source.delete()
                ids += requireNotNull(outcome.asset).id
            }

            val renamed = repository.rename(ids[0], "  ../tên mới  ")
            assertEquals(".._tên mới.png", renamed?.displayName)
            assertNull(repository.rename(ids[0], "   "))

            assertEquals(2, repository.addTags(ids, listOf("Bộ sưu tập", "work")))
            ids.forEach { id ->
                val tags = repository.findById(id)!!.tags.mapNotNull(LibraryView::tagKey)
                assertTrue(tags.containsAll(listOfNotNull(LibraryView.tagKey("bộ sưu tập"), LibraryView.tagKey("work"))))
            }

            assertEquals(2, repository.setFavorite(ids, true))
            assertTrue(ids.all { repository.findById(it)!!.favorite })

            val files = ids.map { File(repository.findById(it)!!.originalPath) }
            val result = repository.delete(ids)
            assertEquals(2, result.succeeded)
            assertTrue(result.failedIds.isEmpty())
            assertTrue(files.none(File::exists))
            ids.clear()
        } finally {
            ids.forEach { repository.delete(it) }
            repository.close()
        }
    }
}
