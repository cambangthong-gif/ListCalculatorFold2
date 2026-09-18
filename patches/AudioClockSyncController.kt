package com.alad.app.core.sync

import android.os.SystemClock
import kotlin.math.max

/**
 * Lightweight media clock derived from captured PCM rather than another app's player state.
 * 16 kHz mono PCM16 = 32 bytes per millisecond.
 */
class AudioClockSyncController {
    data class CaptureUpdate(
        val sourceClockMs: Long,
        val discontinuity: Boolean,
        val wallGapMs: Long
    )

    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val BYTES_PER_SAMPLE = 2L
        private const val GAP_DISCONTINUITY_MS = 950L
        private const val INPUT_ENDPOINT_COMPENSATION_MS = 180L
    }

    private var sourceClockUs = 0L
    private var lastCaptureWallMs = 0L
    private var lastSpeechSourceMs = -1L
    private var lastSpeechWallMs = 0L
    private var playing = true
    private var generation = 0L

    @Synchronized
    fun reset() {
        sourceClockUs = 0L
        lastCaptureWallMs = 0L
        lastSpeechSourceMs = -1L
        lastSpeechWallMs = 0L
        playing = true
        generation++
    }

    @Synchronized
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        // A deliberate pause/resume must not look like a seek/network discontinuity.
        lastCaptureWallMs = 0L
    }

    @Synchronized
    fun onCaptured(byteCount: Int): CaptureUpdate {
        val now = SystemClock.elapsedRealtime()
        val previousWall = lastCaptureWallMs
        val wallGap = if (previousWall > 0L) now - previousWall else 0L
        lastCaptureWallMs = now

        val discontinuity = playing &&
            previousWall > 0L &&
            wallGap >= GAP_DISCONTINUITY_MS

        if (playing && byteCount > 0) {
            val durationUs = byteCount.toLong() * 1_000_000L /
                (SAMPLE_RATE * BYTES_PER_SAMPLE)
            sourceClockUs += durationUs
        }

        if (discontinuity) generation++
        return CaptureUpdate(
            sourceClockMs = sourceClockUs / 1000L,
            discontinuity = discontinuity,
            wallGapMs = wallGap
        )
    }

    @Synchronized
    fun markSpeechSent() {
        lastSpeechSourceMs = sourceClockUs / 1000L
        lastSpeechWallMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun markInputFinal(): Long {
        val current = sourceClockUs / 1000L
        val recentSpeech = if (
            lastSpeechSourceMs >= 0L &&
            SystemClock.elapsedRealtime() - lastSpeechWallMs <= 3_500L
        ) {
            lastSpeechSourceMs
        } else {
            current
        }
        return max(0L, recentSpeech - INPUT_ENDPOINT_COMPENSATION_MS)
    }

    @Synchronized
    fun bestLiveAnchor(): Long {
        val current = sourceClockUs / 1000L
        return if (lastSpeechSourceMs >= 0L) {
            max(0L, lastSpeechSourceMs - INPUT_ENDPOINT_COMPENSATION_MS)
        } else {
            current
        }
    }

    @Synchronized
    fun currentClockMs(): Long = sourceClockUs / 1000L

    @Synchronized
    fun lagFrom(sourceAnchorMs: Long): Long {
        if (sourceAnchorMs < 0L) return 0L
        return (sourceClockUs / 1000L - sourceAnchorMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun hasRecentSpeech(windowMs: Long): Boolean =
        lastSpeechWallMs > 0L &&
            SystemClock.elapsedRealtime() - lastSpeechWallMs <= windowMs

    @Synchronized
    fun generation(): Long = generation
}
