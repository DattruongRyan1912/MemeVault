package com.memevault.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Small, local-only GIF adapter for the MVP TikTok action.
 *
 * This deliberately keeps the converter behind one seam. Movie is deprecated on newer Android
 * releases and the output must be verified on a real device before the app claims broad GIF
 * compatibility. If encoding fails, the caller keeps the original GIF and surfaces an error.
 */
object GifToMp4Converter {
    private const val MAX_DIMENSION = 720
    private const val FRAME_STEP_MS = 33
    private const val MIN_DURATION_MS = 1_000
    private const val MAX_DURATION_MS = 60_000

    fun convert(input: File, output: File) {
        require(input.isFile) { "GIF source does not exist" }
        val movie = Movie.decodeFile(input.absolutePath)
            ?: throw IOException("GIF decoder returned no frames")
        val sourceWidth = movie.width()
        val sourceHeight = movie.height()
        val duration = movie.duration().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("GIF dimensions are invalid")

        val scale = min(
            1f,
            min(MAX_DIMENSION.toFloat() / sourceWidth, MAX_DIMENSION.toFloat() / sourceHeight),
        )
        val width = evenDimension(max(2, (sourceWidth * scale).toInt()))
        val height = evenDimension(max(2, (sourceHeight * scale).toInt()))
        output.parentFile?.mkdirs()
        output.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        var inputSurface: Surface? = null
        var completed = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            var timeMs = 0
            while (timeMs < duration) {
                drawFrame(inputSurface, movie, timeMs, width, height)
                drainEncoder(
                    encoder,
                    muxer,
                    bufferInfo,
                    muxerStarted,
                    trackIndex,
                    System.nanoTime() + DRAIN_TIMEOUT_NANOS,
                ).also { result ->
                    muxerStarted = result.muxerStarted
                    trackIndex = result.trackIndex
                }
                timeMs += FRAME_STEP_MS
                // Canvas-backed input surfaces timestamp frames from the posting clock. Keep the
                // clock close to the requested cadence instead of queuing every frame instantly.
                Thread.sleep(FRAME_STEP_MS.toLong())
            }
            encoder.signalEndOfInputStream()
            var endOfStream = false
            val endOfStreamDeadline = System.nanoTime() + EOS_TIMEOUT_NANOS
            while (!endOfStream) {
                val result = drainEncoder(
                    encoder,
                    muxer,
                    bufferInfo,
                    muxerStarted,
                    trackIndex,
                    endOfStreamDeadline,
                )
                muxerStarted = result.muxerStarted
                trackIndex = result.trackIndex
                endOfStream = result.endOfStream
            }
            completed = muxerStarted && output.isFile && output.length() > 0L
        } finally {
            inputSurface?.release()
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            if (!completed) output.delete()
        }
        if (!completed) throw IOException("Video encoder did not produce an MP4")
    }

    private fun drawFrame(surface: Surface?, movie: Movie, timeMs: Int, width: Int, height: Int) {
        val target = surface ?: throw IOException("Video input surface is unavailable")
        val canvas: Canvas = target.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            val scale = min(width.toFloat() / movie.width(), height.toFloat() / movie.height())
            val left = (width - movie.width() * scale) / 2f
            val top = (height - movie.height() * scale) / 2f
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            movie.setTime(timeMs)
            movie.draw(canvas, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        trackIndex: Int,
        deadlineNanos: Long,
    ): DrainResult {
        var started = muxerStarted
        var track = trackIndex
        while (true) {
            ensureDrainActive(deadlineNanos)
            when (val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(started, track, false)
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (started) throw IOException("Encoder changed output format twice")
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                else -> if (index >= 0) {
                    val encoded = encoder.getOutputBuffer(index)
                    if (encoded != null && bufferInfo.size > 0) {
                        if (!started) throw IOException("Encoder emitted data before output format")
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, encoded, bufferInfo)
                    }
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (endOfStream) return DrainResult(started, track, true)
                }
            }
        }
    }

    private fun evenDimension(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun ensureDrainActive(deadlineNanos: Long) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("GIF conversion was canceled")
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw IOException("Video encoder quá thời gian chờ dữ liệu đầu ra")
        }
    }

    private data class DrainResult(
        val muxerStarted: Boolean,
        val trackIndex: Int,
        val endOfStream: Boolean,
    )

    private val DRAIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
    private val EOS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15)
}
