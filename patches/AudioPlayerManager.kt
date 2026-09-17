package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

class AudioPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000 // PCM16 mono
        private const val DYNAMIC_FOCUS_RELEASE_MS = 450L
    }

    private data class AudioChunk(
        val data: ByteArray,
        val enqueuedAtMs: Long
    )

    private val queue = LinkedBlockingDeque<AudioChunk>()
    private val queuedBytes = AtomicLong(0)
    private val running = AtomicBoolean(false)
    private val trackLock = Any()

    @Volatile private var dubMode = "auto_duck"
    @Volatile private var aiVolume = 1.0f
    @Volatile private var manualSyncMs = 0
    @Volatile private var autoSync = true
    @Volatile private var catchUp = true
    @Volatile private var lowLatency = true
    @Volatile private var maxCatchUpSpeed = 1.15f

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentFocusGain: Int? = null
    private var workerThread: Thread? = null
    private var lastSpeechWriteMs = 0L
    private var currentPlaybackSpeed = 1.0f

    fun start() {
        if (running.getAndSet(true)) return

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val desiredBuffer = if (lowLatency) minBuffer else minBuffer * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(desiredBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also {
                it.setVolume(aiVolume.coerceIn(0f, 1f))
                it.play()
            }

        if (dubMode == "voice_over") {
            ensureFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        workerThread = Thread({ playbackLoop() }, "ALAD-AudioPlayer").apply {
            isDaemon = true
            start()
        }
    }

    fun configure(
        mode: String,
        volume: Float,
        syncMs: Int,
        autoSyncEnabled: Boolean,
        catchUpEnabled: Boolean,
        lowLatencyEnabled: Boolean,
        maxSpeed: Float
    ) {
        val modeChanged = dubMode != mode
        dubMode = mode
        aiVolume = volume.coerceIn(0f, 1f)
        manualSyncMs = syncMs.coerceIn(-2000, 5000)
        autoSync = autoSyncEnabled
        catchUp = catchUpEnabled
        lowLatency = lowLatencyEnabled
        maxCatchUpSpeed = maxSpeed.coerceIn(1.0f, 1.30f)

        synchronized(trackLock) {
            audioTrack?.setVolume(aiVolume)
        }

        if (modeChanged) {
            releaseFocus()
            if (running.get() && dubMode == "voice_over") {
                ensureFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    fun playAudioData(data: ByteArray) {
        if (!running.get() || data.isEmpty()) return

        val chunk = AudioChunk(data.copyOf(), SystemClock.elapsedRealtime())
        queue.offerLast(chunk)
        queuedBytes.addAndGet(chunk.data.size.toLong())

        // Low latency mode never allows an unbounded translated-audio backlog.
        if (lowLatency && queuedDurationMs() > 2600) {
            dropOldestUntil(1000)
        }
    }

    fun setVolume(volume: Float) {
        aiVolume = volume.coerceIn(0f, 1f)
        synchronized(trackLock) {
            audioTrack?.setVolume(aiVolume)
        }
    }

    fun setManualSyncMs(value: Int) {
        manualSyncMs = value.coerceIn(-2000, 5000)
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        autoSync = enabled
    }

    /**
     * Used by the overlay +/- buttons. Negative nudges try to advance translated
     * speech immediately by discarding stale queued audio and flushing the device buffer.
     */
    fun nudgeSync(deltaMs: Int): Int {
        manualSyncMs = (manualSyncMs + deltaMs).coerceIn(-2000, 5000)
        if (deltaMs < 0) {
            dropAudioMs(abs(deltaMs))
            synchronized(trackLock) {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.play()
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not flush AudioTrack for negative sync nudge", t)
                }
            }
        }
        return manualSyncMs
    }

    private fun playbackLoop() {
        while (running.get()) {
            try {
                if (autoSync) rebalanceBacklog()

                val chunk = queue.poll(180, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    onPlaybackIdle()
                    continue
                }
                queuedBytes.addAndGet(-chunk.data.size.toLong())

                val positiveDelay = manualSyncMs.coerceAtLeast(0)
                if (positiveDelay > 0) {
                    val dueAt = chunk.enqueuedAtMs + positiveDelay
                    val waitMs = dueAt - SystemClock.elapsedRealtime()
                    if (waitMs > 0) Thread.sleep(waitMs.coerceAtMost(positiveDelay.toLong()))
                }

                updateCatchUpSpeed()
                acquireFocusForSpeech()

                synchronized(trackLock) {
                    val track = audioTrack
                    if (track != null && running.get()) {
                        var offset = 0
                        while (offset < chunk.data.size && running.get()) {
                            val wrote = track.write(
                                chunk.data,
                                offset,
                                chunk.data.size - offset,
                                AudioTrack.WRITE_BLOCKING
                            )
                            if (wrote <= 0) break
                            offset += wrote
                        }
                    }
                }
                lastSpeechWriteMs = SystemClock.elapsedRealtime()
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                Log.e(TAG, "Playback worker error", t)
            }
        }
    }

    private fun rebalanceBacklog() {
        val qMs = queuedDurationMs()
        val hardLimit = if (lowLatency) 1500 else 2400
        val restoreTarget = if (lowLatency) 650 else 1100
        if (qMs > hardLimit) {
            dropOldestUntil(restoreTarget)
        }
    }

    private fun updateCatchUpSpeed() {
        if (!catchUp) {
            applyPlaybackSpeed(1.0f)
            return
        }

        val qMs = queuedDurationMs()
        val advancePressure = (-manualSyncMs).coerceAtLeast(0)
        val threshold = if (lowLatency) 220 else 420
        val excess = (qMs + advancePressure - threshold).coerceAtLeast(0)

        val targetSpeed = if (excess <= 0) {
            1.0f
        } else {
            min(maxCatchUpSpeed, 1.0f + (excess / 3500f))
        }
        applyPlaybackSpeed(targetSpeed)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(1.0f, maxCatchUpSpeed.coerceAtLeast(1.0f))
        if (kotlin.math.abs(safe - currentPlaybackSpeed) < 0.015f) return

        synchronized(trackLock) {
            try {
                audioTrack?.playbackParams = PlaybackParams()
                    .setSpeed(safe)
                    .setPitch(1.0f)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
                currentPlaybackSpeed = safe
            } catch (t: Throwable) {
                Log.w(TAG, "Playback speed change not supported", t)
                currentPlaybackSpeed = 1.0f
            }
        }
    }

    private fun acquireFocusForSpeech() {
        when (dubMode) {
            "parallel" -> Unit
            "voice_over" -> ensureFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            "full_dub" -> ensureFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            else -> ensureFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun onPlaybackIdle() {
        applyPlaybackSpeed(1.0f)
        if (dubMode == "auto_duck" || dubMode == "full_dub") {
            if (SystemClock.elapsedRealtime() - lastSpeechWriteMs >= DYNAMIC_FOCUS_RELEASE_MS) {
                releaseFocus()
            }
        }
    }

    @Synchronized
    private fun ensureFocus(gainType: Int) {
        if (currentFocusGain == gainType && focusRequest != null) return
        releaseFocus()

        val manager = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(gainType)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        try {
            manager.requestAudioFocus(request)
            focusRequest = request
            currentFocusGain = gainType
        } catch (t: Throwable) {
            Log.w(TAG, "Audio focus request failed", t)
        }
    }

    @Synchronized
    private fun releaseFocus() {
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) {
            try {
                manager.abandonAudioFocusRequest(request)
            } catch (_: Throwable) {
            }
        }
        focusRequest = null
        currentFocusGain = null
    }

    private fun dropAudioMs(ms: Int) {
        if (ms <= 0) return
        var remainingBytes = ms.toLong() * BYTES_PER_MS
        while (remainingBytes > 0) {
            val removed = queue.pollFirst() ?: break
            queuedBytes.addAndGet(-removed.data.size.toLong())
            remainingBytes -= removed.data.size
        }
    }

    private fun dropOldestUntil(targetMs: Int) {
        while (queuedDurationMs() > targetMs) {
            val removed = queue.pollFirst() ?: break
            queuedBytes.addAndGet(-removed.data.size.toLong())
        }
    }

    fun queuedDurationMs(): Int =
        (queuedBytes.get().coerceAtLeast(0L) / BYTES_PER_MS).toInt()

    fun stop() {
        if (!running.getAndSet(false)) return

        workerThread?.interrupt()
        workerThread = null
        queue.clear()
        queuedBytes.set(0)
        applyPlaybackSpeed(1.0f)
        releaseFocus()

        synchronized(trackLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
            } catch (_: Throwable) {
            }
            audioTrack?.release()
            audioTrack = null
        }
        audioManager = null
    }
}
