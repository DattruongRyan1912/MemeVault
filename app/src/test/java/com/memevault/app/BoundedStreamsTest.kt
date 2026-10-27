package com.memevault.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedStreamsTest {
    @Test
    fun copiesExactlyUpToLimit() {
        val bytes = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = BoundedStreams.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong())

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun rejectsBeforeWritingPastLimit() {
        val output = ByteArrayOutputStream()

        assertThrows(FileSizeLimitExceededException::class.java) {
            BoundedStreams.copy(ByteArrayInputStream(ByteArray(33)), output, 32L)
        }
        assertEquals(0, output.size())
    }
}
