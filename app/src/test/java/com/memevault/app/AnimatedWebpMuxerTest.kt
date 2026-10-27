package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebpMuxerTest {
    @Test
    fun muxesFullCanvasFramesWithAnimationFlagAndInfiniteLoop() {
        val frameOne = AnimatedWebpMuxer.framePayloadFromStillWebp(stillWebp("VP8L", byteArrayOf(1, 2, 3)))
        val frameTwo = AnimatedWebpMuxer.framePayloadFromStillWebp(stillWebp("VP8L", byteArrayOf(4, 5, 6, 7)))

        val bytes = AnimatedWebpMuxer.mux(
            canvasWidth = 512,
            canvasHeight = 512,
            frames = listOf(
                AnimatedWebpFrame(frameOne, durationMs = 80, width = 512, height = 512),
                AnimatedWebpFrame(frameTwo, durationMs = 120, width = 512, height = 512),
            ),
        )

        assertEquals("RIFF", fourcc(bytes, 0))
        assertEquals("WEBP", fourcc(bytes, 8))
        val chunks = topLevelChunks(bytes)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), chunks.map { it.first })

        val vp8x = chunks[0].second
        assertEquals(10, vp8x.size)
        assertEquals(0x12, vp8x[0].toInt() and 0xFF) // alpha + animation
        assertEquals(511, readUint24(vp8x, 4))
        assertEquals(511, readUint24(vp8x, 7))

        val anim = chunks[1].second
        assertEquals(6, anim.size)
        assertEquals(0, anim[4].toInt() and 0xFF)
        assertEquals(0, anim[5].toInt() and 0xFF)

        val firstFrame = chunks[2].second
        assertEquals(511, readUint24(firstFrame, 6))
        assertEquals(511, readUint24(firstFrame, 9))
        assertEquals(80, readUint24(firstFrame, 12))
        assertEquals(2, firstFrame[15].toInt() and 0x02) // do not blend; keep independent frames
    }

    @Test
    fun extractsVp8lAndOptionalAlphFromStillWebp() {
        val lossless = stillWebp("VP8L", byteArrayOf(9, 8, 7))
        val payload = AnimatedWebpMuxer.framePayloadFromStillWebp(lossless)
        assertEquals("VP8L", fourcc(payload, 0))
        assertEquals(3, readUint32(payload, 4))
        assertEquals(9, payload[8].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyFrameList() {
        AnimatedWebpMuxer.mux(16, 16, emptyList())
    }

    private fun stillWebp(codec: String, payload: ByteArray): ByteArray {
        val chunkSize = payload.size
        val padded = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
        val bytes = ByteArray(12 + 8 + padded)
        writeFourcc(bytes, 0, "RIFF")
        writeUint32(bytes, 4, bytes.size - 8)
        writeFourcc(bytes, 8, "WEBP")
        writeFourcc(bytes, 12, codec)
        writeUint32(bytes, 16, chunkSize)
        payload.copyInto(bytes, 20)
        return bytes
    }

    private fun topLevelChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        var offset = 12
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        while (offset + 8 <= bytes.size) {
            val name = fourcc(bytes, offset)
            val size = readUint32(bytes, offset + 4)
            val start = offset + 8
            val end = start + size
            chunks += name to bytes.copyOfRange(start, end)
            offset = end + if (size % 2 == 1) 1 else 0
        }
        return chunks
    }

    private fun fourcc(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)

    private fun writeFourcc(bytes: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
    }

    private fun writeUint32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readUint32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readUint24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
