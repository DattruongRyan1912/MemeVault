package com.memevault.app

import kotlin.math.max

/**
 * Bounds for the experimental GIF → animated WebP sticker-like export. This does not change
 * host-app sticker catalogues; it only limits local encoding cost.
 */
object AnimatedWebpPolicy {
    const val CANVAS_SIZE = 512
    const val FALLBACK_CANVAS_SIZE = 320
    const val CONTENT_FRACTION = 0.94f
    const val MAX_FRAMES = 24
    const val FALLBACK_MAX_FRAMES = 16
    const val MAX_DURATION_MS = 6_000
    const val MIN_STEP_MS = 40
    const val MAX_OUTPUT_BYTES = 900L * 1024L
    const val ENCODE_DEADLINE_MS = 45_000L

    fun sampleTimestamps(durationMs: Int, maxFrames: Int = MAX_FRAMES): List<Int> {
        if (durationMs <= 0) return listOf(0)
        val duration = durationMs.coerceAtMost(MAX_DURATION_MS)
        val frameBudget = maxFrames.coerceAtLeast(1)
        val step = max(MIN_STEP_MS, (duration + frameBudget - 1) / frameBudget)
        val timestamps = ArrayList<Int>(frameBudget)
        var time = 0
        while (time < duration && timestamps.size < frameBudget) {
            timestamps += time
            time += step
        }
        return timestamps.ifEmpty { listOf(0) }
    }

    fun frameDurationMs(timestamps: List<Int>, index: Int, durationMs: Int): Int {
        val current = timestamps[index]
        val next = timestamps.getOrNull(index + 1)
            ?: durationMs.coerceAtLeast(current + MIN_STEP_MS)
        return (next - current).coerceAtLeast(MIN_STEP_MS).coerceAtMost(0xFFFFFF)
    }
}
