package com.memevault.app

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageReconcileInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Audit H1: a restore into another Android user keeps the files but leaves the old absolute
     * prefix in Room. Reconcile must rebase those rows, not delete them and then sweep the files.
     * Only rows created here are drifted, so the device's real library is never touched.
     */
    @Test
    fun driftedOriginalPathsAreRebasedInsteadOfWiped() {
        val ids = mutableListOf<String>()
        var repository = AssetRepository(context)
        try {
            repeat(2) { index -> ids += importPng(repository, index) }
            val files = ids.map { File(repository.findById(it)!!.originalPath) }
            repository.close()

            val oldPrefix = "/data/user/10/${context.packageName}/files/" +
                "${AssetRepository.MEDIA_DIRECTORY}/${AssetRepository.ORIGINALS_DIRECTORY}/"
            SQLiteDatabase.openDatabase(
                context.getDatabasePath("memevault.db").path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                ids.forEachIndexed { index, id ->
                    db.execSQL(
                        "UPDATE assets SET original_path = ? WHERE id = ?",
                        arrayOf(oldPrefix + files[index].name, id),
                    )
                }
            }

            repository = AssetRepository(context)
            ids.forEachIndexed { index, id ->
                val asset = repository.findById(id)
                assertNotNull("drifted row $id was dropped by reconcile", asset)
                assertEquals(files[index].absolutePath, asset!!.originalPath)
                assertTrue("original ${files[index].name} was swept", files[index].isFile)
            }
        } finally {
            ids.forEach { repository.delete(it) }
            repository.close()
        }
    }

    private fun importPng(repository: AssetRepository, index: Int): String {
        val token = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "reconcile-$token.png")
        val bitmap = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40 + index, token.hashCode() and 0xFF, 7))
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val outcome = repository.importGeneratedFile(source, "reconcile-$token.png", "image/png", null)
        source.delete()
        return requireNotNull(outcome.asset) { outcome.error ?: "import failed" }.id
    }
}
