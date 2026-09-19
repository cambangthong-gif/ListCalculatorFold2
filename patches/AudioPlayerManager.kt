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
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000
        private const val DYNAMIC_FOCUS_RELEASE_MS = 450L
        private const val ULTRA_PREBUFFER_MS = 25
        private const val ULTRA_PREBUFFER_MAX_WAIT_MS = 15L
        private const val FAST_PREBUFFER_MS = 45
        private const val FAST_PREBUFFER_MAX_WAIT_MS = 35L
        private const val STABLE_PREBUFFER_MS = 110
        private const val STABLE_PREBUFFER_MAX_WAIT_MS = 90L
    }

    private data class AudioChunk(
        val data: ByteArray,
        val enqueuedAtMs: Long,
        val sourceClockMs: Long
    )
    private val queue = LinkedBlockingDeque<AudioChunk>()
    private val queuedBytes = AtomicLong(0)
    private val running = AtomicBoolean(false)
    private val trackLock = Any()

    @Volatile private var dubMode = "auto_duck"
    @Volatile private var aiVolume = 1.0f
    @Volatile private var originalVolume = 0.35f
    @Volatile private var manualSyncMs = 0
    @Volatile private var autoSync = true
    @Volatile private var catchUp = true
    @Volatile private var lowLatency = true
    @Volatile private var maxCatchUpSpeed = 1.15f
    @Volatile private var externallyPaused = false
    @Volatile private var sourceClockProvider: (() -> Long)? = null
    @Volatile private var stableLiveMode = false
    @Volatile private var stableProfile = "balanced"
    @Volatile private var balancedMicroCatchUp = false

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentFocusGain: Int? = null
    private var workerThread: Thread? = null
    private var lastSpeechWriteMs = 0L
    private var currentPlaybackSpeed = 1.0f
    private var needsStablePrebuffer = true

    fun start() {
        if (running.getAndSet(true)) return
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val desiredBuffer = if (stableLiveMode) {
            when (stableProfile) {
                "stable" -> maxOf(minBuffer * 2, BYTES_PER_MS * 140)
                "ultra_fast" -> maxOf(minBuffer, BYTES_PER_MS * 50)
                else -> maxOf(minBuffer, BYTES_PER_MS * 70)
            }
        } else if (lowLatency) {
            minBuffer
        } else {
            minBuffer * 2
        }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
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
            .also { it.setVolume(aiVolume); it.play() }

        if (dubMode == "voice_over") desiredFocusGain()?.let(::ensureFocus)
        workerThread = Thread({ playbackLoop() }, "ALAD-AudioPlayer").apply { isDaemon = true; start() }
    }

    fun configure(
        mode: String,
        volume: Float,
        originalLevel: Float,
        syncMs: Int,
        autoSyncEnabled: Boolean,
        catchUpEnabled: Boolean,
        lowLatencyEnabled: Boolean,
        maxSpeed: Float
    ) {
        val focusChanged = dubMode != mode || kotlin.math.abs(originalVolume - originalLevel) > 0.01f
        dubMode = mode
        aiVolume = volume.coerceIn(0f, 1f)
        originalVolume = originalLevel.coerceIn(0f, 1f)
        manualSyncMs = syncMs.coerceIn(-2000, 5000)
        autoSync = autoSyncEnabled
        catchUp = catchUpEnabled
        lowLatency = lowLatencyEnabled
        maxCatchUpSpeed = maxSpeed.coerceIn(1.0f, 1.30f)
        synchronized(trackLock) { audioTrack?.setVolume(aiVolume) }
        if (focusChanged) {
            releaseFocus()
            if (running.get() && dubMode == "voice_over") desiredFocusGain()?.let(::ensureFocus)
        }
    }

    fun setSourceClockProvider(provider: (() -> Long)?) {
        sourceClockProvider = provider
    }

    fun setStableLiveMode(
        enabled: Boolean,
        profile: String = "balanced",
        microCatchUp: Boolean = false
    ) {
        stableLiveMode = enabled
        stableProfile = profile
        balancedMicroCatchUp = enabled && profile == "balanced" && microCatchUp
        needsStablePrebuffer = true
        if (enabled) {
            autoSync = false
            catchUp = microCatchUp
            if (microCatchUp) {
                maxCatchUpSpeed = minOf(maxCatchUpSpeed.coerceAtLeast(1.03f), 1.08f)
            }
            sourceClockProvider = null
            applyPlaybackSpeed(1.0f)
        }
    }

    fun setBalancedMicroCatchUp(enabled: Boolean) {
        balancedMicroCatchUp = stableLiveMode && enabled
        catchUp = balancedMicroCatchUp
        if (!balancedMicroCatchUp) applyPlaybackSpeed(1.0f)
    }

    fun playAudioData(data: ByteArray, sourceClockMs: Long = -1L) {
        if (!running.get() || data.isEmpty()) return
        val chunk = AudioChunk(data.copyOf(), SystemClock.elapsedRealtime(), sourceClockMs)
        queue.offerLast(chunk)
        queuedBytes.addAndGet(chunk.data.size.toLong())
    }

    fun setVolume(volume: Float) {
        aiVolume = volume.coerceIn(0f, 1f)
        synchronized(trackLock) { audioTrack?.setVolume(aiVolume) }
    }
    fun setManualSyncMs(value: Int) { manualSyncMs = value.coerceIn(-2000, 5000) }
    fun setAutoSyncEnabled(enabled: Boolean) { autoSync = enabled }

    fun setExternalPaused(paused: Boolean) {
        externallyPaused = paused
        synchronized(trackLock) {
            try {
                val track = audioTrack ?: return@synchronized
                if (paused) {
                    track.pause()
                } else {
                    track.play()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not apply external pause state", t)
            }
        }
    }

    fun clearForExternalSeek() {
        queue.clear()
        queuedBytes.set(0)
        synchronized(trackLock) {
            try {
                val track = audioTrack ?: return@synchronized
                track.pause()
                track.flush()
                if (!externallyPaused && running.get()) track.play()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not flush after external seek", t)
            }
        }
    }

    fun nudgeSync(deltaMs: Int): Int {
        manualSyncMs = (manualSyncMs + deltaMs).coerceIn(-1200, 2500)

        if (deltaMs < 0 && !stableLiveMode) {
            // Legacy/manual mode may still choose a destructive nudge.
            dropAudioMs(abs(deltaMs))
            synchronized(trackLock) {
                try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play() }
                catch (t: Throwable) { Log.w(TAG, "Could not flush AudioTrack", t) }
            }
        }

        // Balanced Stable Live never drops translated content. A negative offset
        // simply raises the micro catch-up pressure until the queue closes the gap.
        return manualSyncMs
    }

    private fun playbackLoop() {
        while (running.get()) {
            try {
                if (externallyPaused) {
                    Thread.sleep(40)
                    continue
                }
                if (autoSync) rebalanceBacklog()

                if (stableLiveMode && needsStablePrebuffer && queue.peekFirst() != null) {
                    val startWait = SystemClock.elapsedRealtime()
                    val targetPrebuffer = when (stableProfile) {
                        "stable" -> STABLE_PREBUFFER_MS
                        "ultra_fast" -> ULTRA_PREBUFFER_MS
                        else -> FAST_PREBUFFER_MS
                    }
                    val maxWait = when (stableProfile) {
                        "stable" -> STABLE_PREBUFFER_MAX_WAIT_MS
                        "ultra_fast" -> ULTRA_PREBUFFER_MAX_WAIT_MS
                        else -> FAST_PREBUFFER_MAX_WAIT_MS
                    }
                    while (
                        running.get() &&
                        !externallyPaused &&
                        queuedDurationMs() < targetPrebuffer &&
                        SystemClock.elapsedRealtime() - startWait < maxWait
                    ) {
                        Thread.sleep(8)
                    }
                    needsStablePrebuffer = false
                }

                val chunk = queue.poll(180, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (stableLiveMode) needsStablePrebuffer = true
                    onPlaybackIdle()
                    continue
                }
                queuedBytes.addAndGet(-chunk.data.size.toLong())
                val delay = manualSyncMs.coerceAtLeast(0)
                if (delay > 0) {
                    val waitMs = chunk.enqueuedAtMs + delay - SystemClock.elapsedRealtime()
                    if (waitMs > 0) Thread.sleep(waitMs.coerceAtMost(delay.toLong()))
                }
                updateCatchUpSpeed()
                acquireFocusForSpeech()
                synchronized(trackLock) {
                    val track = audioTrack
                    if (track != null && running.get()) {
                        var offset = 0
                        while (offset < chunk.data.size && running.get()) {
                            val wrote = track.write(chunk.data, offset, chunk.data.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (wrote <= 0) break
                            offset += wrote
                        }
                    }
                }
                lastSpeechWriteMs = SystemClock.elapsedRealtime()
            } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
            catch (t: Throwable) { Log.e(TAG, "Playback worker error", t) }
        }
    }

    private fun rebalanceBacklog() {
        // Completeness-first: never drop normal translated audio. Auto-sync catches up
        // by playback speed only. Destructive clearing is reserved for explicit seek/resync.
    }

    private fun updateCatchUpSpeed() {
        if (!catchUp) {
            applyPlaybackSpeed(1.0f)
            return
        }

        val queueLag = queuedDurationMs().toLong()
        if (stableLiveMode && balancedMicroCatchUp) {
            val pressure = queueLag + (-manualSyncMs).coerceAtLeast(0)
            val target = when {
                pressure < 220L -> 1.00f
                pressure < 420L -> 1.03f
                pressure < 700L -> 1.05f
                else -> 1.08f
            }
            applyPlaybackSpeed(target)
            return
        }

        val sourceLag = headSourceLagMs()
        val effectiveLag = maxOf(queueLag, sourceLag)
        val targetLag = if (lowLatency) 500L else 800L
        val excess = (effectiveLag - targetLag + (-manualSyncMs).coerceAtLeast(0)).coerceAtLeast(0L)
        val target = if (excess <= 0L) {
            1.0f
        } else {
            min(maxCatchUpSpeed, 1.0f + excess / 4200f)
        }
        applyPlaybackSpeed(target)
    }

    private fun headSourceLagMs(): Long {
        val first = queue.peekFirst() ?: return 0L
        val nowClock = sourceClockProvider?.invoke() ?: return 0L
        if (first.sourceClockMs < 0L || nowClock < 0L) return 0L
        return (nowClock - first.sourceClockMs).coerceAtLeast(0L)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(1.0f, maxCatchUpSpeed.coerceAtLeast(1.0f))
        if (kotlin.math.abs(safe - currentPlaybackSpeed) < 0.015f) return
        synchronized(trackLock) {
            try {
                audioTrack?.playbackParams = PlaybackParams().setSpeed(safe).setPitch(1.0f)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
                currentPlaybackSpeed = safe
            } catch (t: Throwable) { Log.w(TAG, "Playback speed unsupported", t); currentPlaybackSpeed = 1.0f }
        }
    }

    private fun desiredFocusGain(): Int? {
        if (dubMode == "parallel" || originalVolume >= 0.85f) return null
        return if (originalVolume <= 0.15f || dubMode == "full_dub") {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
    }

    private fun acquireFocusForSpeech() {
        val desired = desiredFocusGain()
        if (desired == null) releaseFocus() else ensureFocus(desired)
    }

    private fun onPlaybackIdle() {
        applyPlaybackSpeed(1.0f)
        if (dubMode != "voice_over" && SystemClock.elapsedRealtime() - lastSpeechWriteMs >= DYNAMIC_FOCUS_RELEASE_MS) {
            releaseFocus()
        }
    }

    @Synchronized
    private fun ensureFocus(gainType: Int) {
        if (currentFocusGain == gainType && focusRequest != null) return
        releaseFocus()
        val manager = audioManager ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(gainType)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        try { manager.requestAudioFocus(request); focusRequest = request; currentFocusGain = gainType }
        catch (t: Throwable) { Log.w(TAG, "Audio focus request failed", t) }
    }

    @Synchronized
    private fun releaseFocus() {
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) try { manager.abandonAudioFocusRequest(request) } catch (_: Throwable) {}
        focusRequest = null
        currentFocusGain = null
    }

    private fun dropAudioMs(ms: Int) {
        var remaining = ms.toLong() * BYTES_PER_MS
        while (remaining > 0) {
            val removed = queue.pollFirst() ?: break
            queuedBytes.addAndGet(-removed.data.size.toLong())
            remaining -= removed.data.size
        }
    }
    private fun dropOldestUntil(targetMs: Int) {
        while (queuedDurationMs() > targetMs) {
            val removed = queue.pollFirst() ?: break
            queuedBytes.addAndGet(-removed.data.size.toLong())
        }
    }
    fun queuedDurationMs(): Int = (queuedBytes.get().coerceAtLeast(0L) / BYTES_PER_MS).toInt()

    fun stop() {
        if (!running.getAndSet(false)) return
        workerThread?.interrupt(); workerThread = null
        externallyPaused = false
        stableLiveMode = false
        stableProfile = "balanced"
        balancedMicroCatchUp = false
        needsStablePrebuffer = true
        queue.clear(); queuedBytes.set(0); applyPlaybackSpeed(1.0f); releaseFocus()
        synchronized(trackLock) {
            try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.stop() } catch (_: Throwable) {}
            audioTrack?.release(); audioTrack = null
        }
        audioManager = null
        sourceClockProvider = null
    }
}
