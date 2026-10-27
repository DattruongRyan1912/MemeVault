package com.memevault.app

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class AnimatedWebpFrame(
    val payload: ByteArray,
    val durationMs: Int,
    val width: Int,
    val height: Int,
)

/**
 * Builds an animated WebP container from still-frame VP8/VP8L bitstreams. Encoding of the
 * frames themselves stays on the Android Bitmap compressor; this type only muxes the RIFF.
 */
object AnimatedWebpMuxer {
    private const val MAX_CANVAS = 16_384
    private const val VP8X_ALPHA = 0x10
    private const val VP8X_ANIMATION = 0x02
    private const val ANMF_DO_NOT_BLEND = 0x02

    fun mux(
        canvasWidth: Int,
        canvasHeight: Int,
        frames: List<AnimatedWebpFrame>,
        loopCount: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty()) { "Animated WebP needs at least one frame" }
        require(canvasWidth in 1..MAX_CANVAS && canvasHeight in 1..MAX_CANVAS) {
            "Canvas WebP không hợp lệ"
        }
        val body = ByteArrayOutputStream()
        body.writeFourcc("WEBP")
        body.writeChunk("VP8X", vp8xPayload(canvasWidth, canvasHeight))
        body.writeChunk("ANIM", animPayload(loopCount))
        frames.forEach { frame ->
            require(frame.payload.isNotEmpty()) { "Frame WebP rỗng" }
            require(frame.durationMs in 1..0xFFFFFF) { "Thời lượng frame WebP không hợp lệ" }
            require(frame.width in 1..canvasWidth && frame.height in 1..canvasHeight) {
                "Kích thước frame WebP vượt canvas"
            }
            body.writeChunk("ANMF", anmfPayload(frame))
        }
        val bodyBytes = body.toByteArray()
        val file = ByteArrayOutputStream(bodyBytes.size + 8)
        file.writeFourcc("RIFF")
        file.writeUint32(bodyBytes.size)
        file.write(bodyBytes)
        return file.toByteArray()
    }

    fun framePayloadFromStillWebp(webp: ByteArray): ByteArray {
        require(webp.size >= 12 && fourcc(webp, 0) == "RIFF" && fourcc(webp, 8) == "WEBP") {
            "Không phải file WebP"
        }
        val output = ByteArrayOutputStream()
        var offset = 12
        while (offset + 8 <= webp.size) {
            val name = fourcc(webp, offset)
            val size = readUint32(webp, offset + 4)
            require(size >= 0 && offset + 8 + size <= webp.size) { "Chunk WebP bị cắt" }
            if (name == "VP8 " || name == "VP8L" || name == "ALPH") {
                output.write(webp, offset, 8 + size)
                if (size % 2 == 1) {
                    val padIndex = offset + 8 + size
                    output.write(if (padIndex < webp.size) webp[padIndex].toInt() and 0xFF else 0)
                }
            }
            offset += 8 + size + if (size % 2 == 1) 1 else 0
        }
        val payload = output.toByteArray()
        require(payload.isNotEmpty()) { "Still WebP không có bitstream VP8" }
        return payload
    }

    private fun vp8xPayload(width: Int, height: Int): ByteArray {
        val payload = ByteArray(10)
        payload[0] = (VP8X_ALPHA or VP8X_ANIMATION).toByte()
        writeUint24(payload, 4, width - 1)
        writeUint24(payload, 7, height - 1)
        return payload
    }

    private fun animPayload(loopCount: Int): ByteArray {
        val payload = ByteArray(6)
        writeUint16(payload, 4, loopCount.coerceIn(0, 0xFFFF))
        return payload
    }

    private fun anmfPayload(frame: AnimatedWebpFrame): ByteArray {
        val header = ByteArray(16)
        writeUint24(header, 6, frame.width - 1)
        writeUint24(header, 9, frame.height - 1)
        writeUint24(header, 12, frame.durationMs)
        header[15] = ANMF_DO_NOT_BLEND.toByte()
        val output = ByteArrayOutputStream(header.size + frame.payload.size)
        output.write(header)
        output.write(frame.payload)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(fourcc: String, payload: ByteArray) {
        writeFourcc(fourcc)
        writeUint32(payload.size)
        write(payload)
        if (payload.size % 2 == 1) write(0)
    }

    private fun ByteArrayOutputStream.writeFourcc(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeUint32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun fourcc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun readUint32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeUint16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUint24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}
