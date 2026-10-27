package com.memevault.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GifToAnimatedWebpConverterInstrumentedTest {
    @Test
    fun androidStillWebpContainsVp8BitstreamTheMuxerCanRead() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            setPixel(2, 3, Color.argb(255, 20, 180, 90))
        }
        val encoded = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        assertTrue(bitmap.compress(format, 100, encoded))
        bitmap.recycle()
        val webp = encoded.toByteArray()
        val header = webp.copyOfRange(0, minOf(64, webp.size)).joinToString(" ") { byte ->
            "%02x".format(byte)
        }
        try {
            val payload = AnimatedWebpMuxer.framePayloadFromStillWebp(webp)
            assertTrue("payload empty, header=$header", payload.isNotEmpty())
        } catch (error: Exception) {
            fail("parser rejected Android WebP (${webp.size} bytes, header=$header): ${error.message}")
        }
    }

    @Test
    fun convertsTenorGifIntoAnimatedWebp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "dog-dance.gif")
        instrumentation.context.assets.open("dog-dance.gif").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val output = File(context.cacheDir, "dog-dance.webp")
        output.delete()
        try {
            GifToAnimatedWebpConverter.convert(source, output)
            assertTrue("webp missing or empty (${output.length()})", output.isFile && output.length() > 0L)
            val header = output.readBytes().copyOfRange(0, 12).toString(Charsets.US_ASCII)
            assertTrue(header.contains("WEBP") || header.startsWith("RIFF"))
        } catch (error: Exception) {
            fail("convert failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            source.delete()
            output.delete()
        }
    }
}
