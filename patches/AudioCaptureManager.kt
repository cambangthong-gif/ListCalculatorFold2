package com.alad.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

class AudioCaptureManager {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var lastReadBlockMs = 0L
    @Volatile private var lastReadBytes = 0

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val READ_CHUNK_MS = 30
        const val READ_CHUNK_BYTES = SAMPLE_RATE * 2 * READ_CHUNK_MS / 1000
        val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
    }

    fun lastReadDurationMs(): Long = lastReadBlockMs
    fun lastReadSizeBytes(): Int = lastReadBytes

    @SuppressLint("MissingPermission")
    fun startCapture(
        mediaProjection: MediaProjection,
        appUid: Int,
        onAudioData: (ByteArray) -> Unit
    ) {
        if (isRecording) return

        try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

            if (appUid > 0) configBuilder.excludeUid(appUid)

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val internalBufferBytes = max(MIN_BUFFER_SIZE, READ_CHUNK_BYTES * 6)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(configBuilder.build())
                .setBufferSizeInBytes(internalBufferBytes)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                } catch (_: Throwable) {
                }

                val readBuffer = ByteArray(READ_CHUNK_BYTES)
                var reads = 0L
                while (isRecording) {
                    val started = SystemClock.elapsedRealtime()
                    val read = audioRecord?.read(
                        readBuffer,
                        0,
                        READ_CHUNK_BYTES,
                        AudioRecord.READ_BLOCKING
                    ) ?: 0
                    lastReadBlockMs =
                        (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    lastReadBytes = read

                    if (read > 0) {
                        reads++
                        onAudioData(readBuffer.copyOf(read))
                        if (reads % 200L == 0L) {
                            Log.d(
                                TAG,
                                "true-30ms capture: read=" + read +
                                    " block=" + lastReadBlockMs +
                                    "ms internal=" + internalBufferBytes
                            )
                        }
                    }
                }
            }, "ALAD-AudioCapture").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            isRecording = false
        }
    }

    fun stopCapture() {
        isRecording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        lastReadBlockMs = 0L
        lastReadBytes = 0
    }
}
