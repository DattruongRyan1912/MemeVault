package com.memevault.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only rows created here are touched, so the device's real library is left alone. */
@RunWith(AndroidJUnit4::class)
class StickerSourceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: AssetRepository
    private val created = mutableListOf<String>()
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        repository = AssetRepository(context)
    }

    @After
    fun tearDown() {
        created.forEach { runCatching { repository.delete(it) } }
        scratch.forEach(File::delete)
        repository.close()
    }

    @Test
    fun archivedSourceLeavesTheLibraryUntilARegularImportBringsItBack() {
        val file = png("archive")
        val id = importFile(file)

        val archived = repository.setArchived(id, archived = true)
        assertTrue(archived!!.archived)
        val all = repository.list()
        assertTrue(all.single { it.id == id }.archived)
        assertTrue(repository.list(AssetFilter.SOURCES).any { it.id == id })
        assertFalse(LibraryView.apply(all, AssetFilter.ALL, TagScope.Any, LibrarySort.ADDED).any { it.id == id })
        // The keyboard loads list() and drops archived rows the same way.
        assertFalse(all.filterNot(Asset::archived).any { it.id == id })

        // Studio and its picker only need a source: the duplicate stays under "Ảnh nguồn".
        val forStudio = repository.importUri(Uri.fromFile(file), keepArchived = true)
        assertTrue(forStudio.duplicate)
        assertFalse(forStudio.unarchived)
        assertTrue(repository.findById(id)!!.archived)

        // A regular import must not answer "already there" for an item the grid hides.
        val regular = repository.importUri(Uri.fromFile(file))
        assertTrue(regular.duplicate)
        assertTrue(regular.unarchived)
        assertEquals(id, regular.asset!!.id)
        assertFalse(repository.findById(id)!!.archived)
    }

    @Test
    fun stickerStaysShareableAfterItsFreshSourceIsDeleted() {
        val sourceId = importFile(png("source"))
        val source = repository.findById(sourceId)!!
        val rendered = png("rendered", transparent = true)
        val outcome = repository.createSticker(
            StickerSaveRequest(
                source = source,
                renderedPng = rendered,
                confidenceMask = FloatArray(SIZE * SIZE) { 1f },
                maskWidth = SIZE,
                maskHeight = SIZE,
                rendererId = "instrumented-test",
                rendererVersion = "1",
                threshold = 0.5f,
                paddingFraction = 0.05f,
                borderColorArgb = null,
                borderThicknessPx = 0,
                displayName = "source-sticker.png",
            ),
        )
        val sticker = requireNotNull(outcome.asset) { outcome.error ?: "sticker failed" }
        created += sticker.id

        // Studio's post-save check: the new sticker is the only derived asset.
        assertEquals(1, repository.countDerivedAssets(sourceId))
        assertTrue(StickerSourcePolicy.canDeleteAfterSave(importedNow = true, derivedAfterSave = 1))
        assertTrue(repository.delete(sourceId))
        created -= sourceId

        val kept = repository.findById(sticker.id)
        assertNotNull(kept)
        assertNull(kept!!.sourceAssetId)
        assertTrue(File(kept.originalPath).isFile)
        // The recipe survives without a source: the app shows "Ảnh gốc đã xoá" and blocks re-edit.
        assertNotNull(repository.findStickerRecipe(sticker.id))
        assertNull(repository.findById(sourceId))
    }

    private fun importFile(file: File): String {
        val outcome = repository.importGeneratedFile(file, file.name, "image/png", null)
        val asset = requireNotNull(outcome.asset) { outcome.error ?: "import failed" }
        assertFalse(outcome.duplicate)
        created += asset.id
        return asset.id
    }

    private fun png(prefix: String, transparent: Boolean = false): File {
        val token = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "$prefix-$token.png")
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (transparent) Color.argb(200, 30, 60, 90) else Color.rgb(token.hashCode() and 0xFF, 90, 17))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        scratch += file
        return file
    }

    private companion object {
        const val SIZE = 8
    }
}
