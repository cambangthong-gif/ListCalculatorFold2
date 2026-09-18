package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.DeviceTtsManager
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.core.sync.AudioClockSyncController
import com.alad.app.core.sync.SmartSyncManager
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SYNC_MINUS = "ACTION_SYNC_MINUS"
        const val ACTION_SYNC_PLUS = "ACTION_SYNC_PLUS"
        const val ACTION_SYNC_AUTO = "ACTION_SYNC_AUTO"
        const val ACTION_COMPANION_STATE = "ACTION_COMPANION_STATE"
        const val ACTION_SMART_SYNC_PREPARE = "ACTION_SMART_SYNC_PREPARE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_COMPANION_EVENT = "EXTRA_COMPANION_EVENT"
        const val EXTRA_COMPANION_PLAYING = "EXTRA_COMPANION_PLAYING"
        const val EXTRA_COMPANION_POSITION_MS = "EXTRA_COMPANION_POSITION_MS"
        const val EXTRA_COMPANION_SPEED = "EXTRA_COMPANION_SPEED"

        val isRunning = MutableStateFlow(false)
        val audioAmplitude = MutableStateFlow(0f)
        val syncOffsetMs = MutableStateFlow(0)
        val autoSyncActive = MutableStateFlow(true)
        val queueLatencyMs = MutableStateFlow(0)
        val smartSyncStatus = MutableStateFlow("Live Sync")
        val smartSyncPositionMs = MutableStateFlow(-1L)
        val audioClockMs = MutableStateFlow(0L)
        val audioClockLagMs = MutableStateFlow(0L)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var deviceTtsManager: DeviceTtsManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var sessionSettingsJob: Job? = null
    private var repository: UserPreferencesRepository? = null
    private var smartSyncManager: SmartSyncManager? = null
    private val audioClockSync = AudioClockSyncController()
    private var smartSyncJob: Job? = null
    private var mediaStopJob: Job? = null
    private var lastSmartCueStartMs = Long.MIN_VALUE
    private var smartSubtitlePlaybackActive = false
    private var lastInputSourceAnchorMs = -1L
    private var geminiOutputSourceCursorMs = -1L
    private var transcriptSourceAnchorMs = -1L
    private var playbackAudioManager: AudioManager? = null
    private var sourceMediaPlaying = true

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            val ownUid = applicationInfo.uid
            val mediaActive = configs.orEmpty().any { config ->
                if (config.clientUid == ownUid) {
                    false
                } else {
                    when (config.audioAttributes.usage) {
                        AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.USAGE_GAME,
                        AudioAttributes.USAGE_UNKNOWN -> true
                        else -> false
                    }
                }
            }
            if (mediaActive == sourceMediaPlaying) return
            sourceMediaPlaying = mediaActive

            smartSyncManager?.setPlaying(mediaActive)
            audioClockSync.setPlaying(mediaActive)
            smartSyncPositionMs.value = smartSyncManager?.estimatePosition() ?: -1L
            audioClockMs.value = audioClockSync.currentClockMs()

            if (!isRunning.value) return

            mediaStopJob?.cancel()
            mediaStopJob = null

            if (mediaActive) {
                audioPlayerManager?.setExternalPaused(false)
                deviceTtsManager?.setExternalPaused(false)
                return
            }

            mediaStopJob = serviceScope.launch {
                val manager = smartSyncManager
                val nearSmartEnd = manager?.isNearEnd(9_000L) == true

                if (nearSmartEnd) {
                    // Natural end: do not cut the translated tail. Freeze the timeline,
                    // enqueue the remaining final caption cues, and let both audio paths drain.
                    smartSyncStatus.value = "TAIL FINISH"

                    if (
                        activeVoiceSource == "device_tts" &&
                        smartSubtitlePlaybackActive &&
                        manager != null &&
                        manager.hasTargetTimeline
                    ) {
                        val tail = manager.remainingTargetCues(
                            lastStartMs = lastSmartCueStartMs,
                            maxLookaheadMs = 12_000L,
                            maxCues = 4
                        )
                        for (cue in tail) {
                            lastSmartCueStartMs = maxOf(lastSmartCueStartMs, cue.startMs)
                            deviceTtsManager?.enqueueText(cue.text)
                        }
                    }

                    val remaining = manager?.remainingTimelineMs() ?: 0L
                    val drainMs = (remaining + 3_500L).coerceIn(3_000L, 12_000L)
                    delay(drainMs)

                    if (!sourceMediaPlaying) {
                        audioPlayerManager?.setExternalPaused(true)
                        deviceTtsManager?.setExternalPaused(true)
                        smartSyncStatus.value = "TAIL DONE"
                    }
                } else {
                    // Without an external timeline, allow the last translated sentence to
                    // finish when playback stops after recent speech. This also avoids
                    // chopping the final line at end-of-video. A manual pause may finish
                    // the already-generated sentence, then output freezes.
                    val recentSpeech = audioClockSync.hasRecentSpeech(2_800L)
                    val pauseGraceMs = when {
                        manager?.hasTimeline == true -> 500L
                        recentSpeech -> 3_800L
                        else -> 1_000L
                    }
                    if (recentSpeech && manager?.hasTimeline != true) {
                        smartSyncStatus.value = "AUDIO TAIL"
                    }
                    delay(pauseGraceMs)
                    if (!sourceMediaPlaying) {
                        audioPlayerManager?.setExternalPaused(true)
                        deviceTtsManager?.setExternalPaused(true)
                        if (manager?.hasTimeline != true) {
                            smartSyncStatus.value = "AUDIO PAUSED"
                        }
                    }
                }
            }
        }
    }

    private val transcriptBuffer = StringBuilder()
    private var lastTranscriptSnapshot = ""
    private var activeVoiceSource = "gemini"

    // Conservative playback-VAD. It mostly suppresses true/near silence; music and
    // ambiguous audio are intentionally passed through so spoken words are not clipped.
    private val vadPreRoll = ArrayDeque<ByteArray>()
    private var vadSpeechActive = false
    private var vadHangoverChunks = 0
    private var vadNoiseFloor = 0.0025f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = UserPreferencesRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else startForeground(NOTIFICATION_ID, notification)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && data != null) startDubbing(resultCode, data)
            }
            ACTION_SYNC_MINUS -> adjustSync(-100)
            ACTION_SYNC_PLUS -> adjustSync(100)
            ACTION_SYNC_AUTO -> enableAutoSyncAndCenter()
            ACTION_COMPANION_STATE -> handleCompanionState(intent)
            ACTION_SMART_SYNC_PREPARE -> prepareSmartSync()
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, data: Intent) {
        isRunning.value = true
        sourceMediaPlaying = true
        audioClockSync.reset()
        audioClockMs.value = 0L
        audioClockLagMs.value = 0L
        lastInputSourceAnchorMs = -1L
        geminiOutputSourceCursorMs = -1L
        transcriptSourceAnchorMs = -1L
        playbackAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            playbackAudioManager?.registerAudioPlaybackCallback(playbackCallback, null)
        } catch (_: Throwable) {
        }
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        serviceScope.launch {
            val prefs = repository ?: UserPreferencesRepository(applicationContext)
            val apiKey = prefs.apiKeyFlow.first()
            val targetLang = prefs.targetLangFlow.first()
            val voiceName = prefs.voiceNameFlow.first()
            val voiceSource = prefs.voiceSourceFlow.first()
            val ttsEnginePackage = prefs.ttsEnginePackageFlow.first()
            val ttsVoiceName = prefs.ttsVoiceNameFlow.first()
            val ttsRate = prefs.ttsRateFlow.first()
            val volumeRatio = prefs.volumeRatioFlow.first()
            val dubMode = prefs.dubModeFlow.first()
            val manualSync = prefs.manualSyncMsFlow.first()
            val autoSync = prefs.autoSyncFlow.first()
            val catchUp = prefs.catchUpFlow.first()
            val lowLatency = prefs.lowLatencyFlow.first()
            val maxCatchUpSpeed = prefs.maxCatchUpSpeedFlow.first()

            activeVoiceSource = voiceSource
            syncOffsetMs.value = manualSync
            autoSyncActive.value = autoSync

            val wsClient = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            webSocketManager = ALADWebSocketManager(wsClient)
            smartSyncManager = SmartSyncManager(applicationContext, wsClient)
            prepareSmartSync()

            if (voiceSource == "device_tts") {
                deviceTtsManager = DeviceTtsManager(applicationContext).also { manager ->
                    manager.configure(
                        languageTag = targetLang,
                        enginePackage = ttsEnginePackage,
                        voiceName = ttsVoiceName,
                        speechRate = ttsRate,
                        outputVolume = volumeRatio,
                        catchUp = catchUp,
                        lowLatency = lowLatency,
                        maxSpeed = maxCatchUpSpeed
                    )
                    manager.setSourceClockProvider { audioClockSync.currentClockMs() }
                    manager.start()
                }
            } else {
                audioPlayerManager = AudioPlayerManager(applicationContext).also { player ->
                    player.configure(
                        mode = dubMode,
                        volume = volumeRatio,
                        syncMs = manualSync,
                        autoSyncEnabled = autoSync,
                        catchUpEnabled = catchUp,
                        lowLatencyEnabled = lowLatency,
                        maxSpeed = maxCatchUpSpeed
                    )
                    player.setSourceClockProvider { audioClockSync.currentClockMs() }
                    player.start()
                }
            }

            webSocketManager?.onStatusChanged = { status ->
                if (status.startsWith("Error") || status.startsWith("Reconnecting")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "ALAD TTS: $status",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                if (activeVoiceSource == "gemini") {
                    val anchor = if (geminiOutputSourceCursorMs >= 0L) {
                        geminiOutputSourceCursorMs
                    } else {
                        audioClockSync.bestLiveAnchor()
                    }
                    audioPlayerManager?.playAudioData(audioChunk, anchor)
                    val chunkDurationMs = (audioChunk.size.toLong() / 48L).coerceAtLeast(1L)
                    geminiOutputSourceCursorMs = anchor + chunkDurationMs
                    audioClockLagMs.value = audioClockSync.lagFrom(anchor)
                    queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
                }
            }

            webSocketManager?.onInputTranscription = { text, isFinal ->
                if (isFinal) {
                    val anchor = audioClockSync.markInputFinal()
                    lastInputSourceAnchorMs = anchor
                    geminiOutputSourceCursorMs = anchor
                    audioClockLagMs.value = audioClockSync.lagFrom(anchor)
                    if (!smartSubtitlePlaybackActive) {
                        smartSyncStatus.value = "AUDIO CLOCK"
                    }
                    handleSmartInputTranscript(text)
                }
            }

            webSocketManager?.onOutputTranscription = { text ->
                if (activeVoiceSource == "device_tts" && !smartSubtitlePlaybackActive) {
                    processTranscriptFragment(text, lastInputSourceAnchorMs)
                }
            }
            webSocketManager?.onTurnComplete = {
                if (activeVoiceSource == "device_tts") flushTranscriptBuffer(resetSnapshot = true)
            }
            webSocketManager?.onInterrupted = {
                synchronized(transcriptBuffer) {
                    transcriptBuffer.clear()
                    lastTranscriptSnapshot = ""
                }
                deviceTtsManager?.clearBacklog()
            }

            webSocketManager?.connect(apiKey, "", targetLang, voiceName)
            startSmartSyncScheduler()

            sessionSettingsJob?.cancel()
            sessionSettingsJob = serviceScope.launch {
                var firstEmit = true
                combine(
                    prefs.targetLangFlow,
                    prefs.voiceNameFlow,
                    prefs.voiceSourceFlow
                ) { language, voice, source -> Triple(language, voice, source) }
                    .distinctUntilChanged()
                    .collect { (newLang, newVoice, newSource) ->
                        if (firstEmit) {
                            firstEmit = false
                        } else if (newSource != activeVoiceSource) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    "Đã đổi nguồn giọng. Dừng và Start lại để áp dụng.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            val currentKey = prefs.apiKeyFlow.first()
                            webSocketManager?.disconnect()
                            webSocketManager?.connect(currentKey, "", newLang, newVoice)
                        }
                    }
            }

            audioCaptureManager = AudioCaptureManager()
            val appUid = applicationInfo.uid
            mediaProjection?.let { projection ->
                audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                    val clockUpdate = audioClockSync.onCaptured(pcmData.size)
                    audioClockMs.value = clockUpdate.sourceClockMs
                    if (clockUpdate.discontinuity) {
                        handleAudioClockDiscontinuity(clockUpdate.wallGapMs)
                    }

                    val normalized = calculateNormalizedLevel(pcmData)
                    feedAudioThroughVad(pcmData, normalized)
                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }
        }
    }

    private fun prepareSmartSync() {
        val manager = smartSyncManager ?: return
        serviceScope.launch {
            val targetLang = repository?.targetLangFlow?.first() ?: "vi"
            smartSubtitlePlaybackActive = false
            lastSmartCueStartMs = Long.MIN_VALUE
            val ready = manager.prepareSharedVideo(targetLang)
            smartSyncStatus.value = SmartSyncManager.status
            smartSyncPositionMs.value = manager.estimatePosition()
            if (ready) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        applicationContext,
                        if (manager.hasTargetTimeline) {
                            "Smart Sync sẵn sàng: đã có subtitle + timeline."
                        } else {
                            "Smart Sync sẵn sàng: dùng subtitle để tự bám vị trí."
                        },
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun startSmartSyncScheduler() {
        smartSyncJob?.cancel()
        smartSyncJob = serviceScope.launch {
            while (isRunning.value) {
                val manager = smartSyncManager
                if (manager != null) {
                    smartSyncStatus.value = SmartSyncManager.status
                    smartSyncPositionMs.value = manager.estimatePosition()

                    if (
                        activeVoiceSource == "device_tts" &&
                        smartSubtitlePlaybackActive &&
                        manager.hasTargetTimeline
                    ) {
                        val cue = manager.targetCueToSpeak(lastSmartCueStartMs)
                        if (cue != null) {
                            lastSmartCueStartMs = cue.startMs
                            deviceTtsManager?.enqueueText(cue.text)
                        }
                    }
                }
                delay(80L)
            }
        }
    }

    private fun handleSmartInputTranscript(text: String) {
        val manager = smartSyncManager ?: return
        val match = manager.matchInputTranscript(text) ?: return
        smartSyncStatus.value = SmartSyncManager.status
        smartSyncPositionMs.value = match.positionMs

        if (
            activeVoiceSource == "device_tts" &&
            manager.hasTargetTimeline &&
            !smartSubtitlePlaybackActive
        ) {
            smartSubtitlePlaybackActive = true
            deviceTtsManager?.clearBacklog()
            synchronized(transcriptBuffer) {
                transcriptBuffer.clear()
                lastTranscriptSnapshot = ""
            }
            lastSmartCueStartMs = Long.MIN_VALUE
        }

        if (match.jumpDetected) {
            audioPlayerManager?.clearForExternalSeek()
            deviceTtsManager?.clearBacklog()
            synchronized(transcriptBuffer) {
                transcriptBuffer.clear()
                lastTranscriptSnapshot = ""
            }
            synchronized(this) {
                vadPreRoll.clear()
                vadSpeechActive = false
                vadHangoverChunks = 0
            }
            lastSmartCueStartMs = Long.MIN_VALUE
            manager.resetAfterSeek()
            queueLatencyMs.value = 0

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    applicationContext,
                    "Smart Sync: đã bám lại vị trí mới.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun calculateNormalizedLevel(pcmData: ByteArray): Float {
        if (pcmData.size < 2) return 0f
        var sum = 0.0
        var samples = 0
        for (i in pcmData.indices step 2) {
            if (i + 1 < pcmData.size) {
                val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
                val signedSample = sample.toShort().toFloat()
                sum += signedSample * signedSample
                samples++
            }
        }
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples).toFloat()
        return (rms / 32767f * 3f).coerceIn(0f, 1f)
    }

    @Synchronized
    private fun feedAudioThroughVad(pcmData: ByteArray, level: Float) {
        val copy = pcmData.copyOf()
        vadPreRoll.addLast(copy)
        while (vadPreRoll.size > 4) vadPreRoll.removeFirst()

        val threshold = maxOf(0.006f, vadNoiseFloor * 2.8f)
        val voiceOrUsefulAudio = level >= threshold

        if (!vadSpeechActive && !voiceOrUsefulAudio) {
            vadNoiseFloor = (vadNoiseFloor * 0.97f + level * 0.03f).coerceIn(0.0015f, 0.025f)
        }

        if (voiceOrUsefulAudio) {
            audioClockSync.markSpeechSent()
            if (!vadSpeechActive) {
                // Send a tiny pre-roll so the first consonant is not clipped.
                vadPreRoll.forEach { webSocketManager?.sendAudioData(it) }
                vadPreRoll.clear()
            } else {
                webSocketManager?.sendAudioData(copy)
            }
            vadSpeechActive = true
            vadHangoverChunks = 12
        } else if (vadSpeechActive && vadHangoverChunks > 0) {
            // Keep enough trailing silence for Gemini to detect the end of speech.
            webSocketManager?.sendAudioData(copy)
            vadHangoverChunks--
        } else {
            vadSpeechActive = false
        }
    }

    private fun handleAudioClockDiscontinuity(wallGapMs: Long) {
        if (!isRunning.value) return
        audioPlayerManager?.clearForExternalSeek()
        deviceTtsManager?.clearBacklog()
        synchronized(transcriptBuffer) {
            transcriptBuffer.clear()
            lastTranscriptSnapshot = ""
            transcriptSourceAnchorMs = -1L
        }
        synchronized(this) {
            vadPreRoll.clear()
            vadSpeechActive = false
            vadHangoverChunks = 0
        }
        lastInputSourceAnchorMs = -1L
        geminiOutputSourceCursorMs = -1L
        audioClockLagMs.value = 0L
        queueLatencyMs.value = 0
        if (!smartSubtitlePlaybackActive) {
            smartSyncStatus.value = "AUDIO RESYNC"
        }
    }

    private fun processTranscriptFragment(text: String, sourceClockMs: Long = -1L) {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return
        synchronized(transcriptBuffer) {
            val delta = when {
                lastTranscriptSnapshot.isNotBlank() && cleaned.startsWith(lastTranscriptSnapshot) ->
                    cleaned.removePrefix(lastTranscriptSnapshot).trimStart()
                cleaned == lastTranscriptSnapshot -> ""
                else -> cleaned
            }
            lastTranscriptSnapshot = cleaned
            if (delta.isNotBlank()) {
                if (transcriptBuffer.isEmpty()) {
                    transcriptSourceAnchorMs = sourceClockMs
                }
                if (transcriptBuffer.isNotEmpty() && !transcriptBuffer.last().isWhitespace()) {
                    transcriptBuffer.append(' ')
                }
                transcriptBuffer.append(delta)
            }

            val trimmed = transcriptBuffer.toString().trimEnd()
            val endChar = trimmed.lastOrNull()
            val naturalBreak = endChar in listOf('.', '!', '?', '…', ':', ';', ',')
            val shouldFlush = transcriptBuffer.length >= 48 ||
                (transcriptBuffer.length >= 24 && naturalBreak)
            if (shouldFlush) flushTranscriptBufferLocked(resetSnapshot = false)
        }
    }

    private fun flushTranscriptBuffer(resetSnapshot: Boolean = false) {
        synchronized(transcriptBuffer) { flushTranscriptBufferLocked(resetSnapshot) }
    }

    private fun flushTranscriptBufferLocked(resetSnapshot: Boolean) {
        val text = transcriptBuffer.toString().trim()
        val sourceAnchor = transcriptSourceAnchorMs
        transcriptBuffer.clear()
        transcriptSourceAnchorMs = -1L
        if (resetSnapshot) lastTranscriptSnapshot = ""
        if (text.isNotBlank()) {
            deviceTtsManager?.enqueueText(text, sourceAnchor)
            audioClockLagMs.value = audioClockSync.lagFrom(sourceAnchor)
        }
    }

    private fun handleCompanionState(intent: Intent) {
        if (!isRunning.value) return
        val event = intent.getStringExtra(EXTRA_COMPANION_EVENT).orEmpty()
        val playing = intent.getBooleanExtra(EXTRA_COMPANION_PLAYING, true)

        when (event) {
            "PAUSE" -> {
                audioPlayerManager?.setExternalPaused(true)
                deviceTtsManager?.clearBacklog()
                synchronized(transcriptBuffer) {
                    transcriptBuffer.clear()
                    lastTranscriptSnapshot = ""
                }
                queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
            }
            "PLAY" -> {
                audioPlayerManager?.setExternalPaused(false)
            }
            "SEEK" -> {
                audioPlayerManager?.clearForExternalSeek()
                audioPlayerManager?.setExternalPaused(!playing)
                deviceTtsManager?.clearBacklog()
                synchronized(transcriptBuffer) {
                    transcriptBuffer.clear()
                    lastTranscriptSnapshot = ""
                }
                synchronized(this) {
                    vadPreRoll.clear()
                    vadSpeechActive = false
                    vadHangoverChunks = 0
                }
                queueLatencyMs.value = 0
            }
            "SPEED" -> {
                // The live source itself is already captured at YouTube's playback speed.
                // Keep the dubbing queue near the live edge; no destructive flush needed.
                audioPlayerManager?.setExternalPaused(!playing)
            }
            else -> {
                audioPlayerManager?.setExternalPaused(!playing)
            }
        }
    }

    private fun adjustSync(deltaMs: Int) {
        if (!isRunning.value) return
        serviceScope.launch {
            val newOffset = if (activeVoiceSource == "gemini") {
                audioPlayerManager?.nudgeSync(deltaMs)
                    ?: (syncOffsetMs.value + deltaMs).coerceIn(-2000, 5000)
            } else {
                (syncOffsetMs.value + deltaMs).coerceIn(-2000, 5000)
            }
            syncOffsetMs.value = newOffset
            repository?.updateManualSyncMs(newOffset)
            queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
        }
    }

    private fun enableAutoSyncAndCenter() {
        if (!isRunning.value) return
        serviceScope.launch {
            autoSyncActive.value = true
            syncOffsetMs.value = 0
            audioPlayerManager?.setAutoSyncEnabled(true)
            audioPlayerManager?.setManualSyncMs(0)
            repository?.updateAutoSync(true)
            repository?.updateManualSyncMs(0)
        }
    }

    private fun stopDubbing() {
        isRunning.value = false
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        audioPlayerManager?.stop()
        audioPlayerManager = null
        deviceTtsManager?.stop()
        deviceTtsManager = null
        webSocketManager?.disconnect()
        webSocketManager = null
        synchronized(transcriptBuffer) {
            transcriptBuffer.clear()
            lastTranscriptSnapshot = ""
        }
        synchronized(this) {
            vadPreRoll.clear()
            vadSpeechActive = false
            vadHangoverChunks = 0
            vadNoiseFloor = 0.0025f
        }
        audioAmplitude.value = 0f
        queueLatencyMs.value = 0
        sessionSettingsJob?.cancel()
        sessionSettingsJob = null
        smartSyncJob?.cancel()
        smartSyncJob = null
        mediaStopJob?.cancel()
        mediaStopJob = null
        smartSyncManager = null
        smartSubtitlePlaybackActive = false
        lastSmartCueStartMs = Long.MIN_VALUE
        smartSyncStatus.value = "Live Sync"
        smartSyncPositionMs.value = -1L
        audioClockSync.reset()
        audioClockMs.value = 0L
        audioClockLagMs.value = 0L
        lastInputSourceAnchorMs = -1L
        geminiOutputSourceCursorMs = -1L
        transcriptSourceAnchorMs = -1L
        try {
            playbackAudioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Throwable) {
        }
        playbackAudioManager = null
        sourceMediaPlaying = true
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.value) stopDubbing()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ALAD TTS Select",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Real-time dubbing with selectable Android TTS engine" }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD TTS Select")
            .setContentText("Live translate · selectable device TTS")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
