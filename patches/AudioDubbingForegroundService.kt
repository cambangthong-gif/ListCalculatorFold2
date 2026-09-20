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
import android.os.SystemClock
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
        const val ACTION_NOTIFICATION_TOGGLE = "ACTION_NOTIFICATION_TOGGLE"
        const val ACTION_PROFILE_CYCLE = "ACTION_PROFILE_CYCLE"
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
        val captureBlockMs = MutableStateFlow(0L)
        val nativeOutputRateHz = MutableStateFlow(0)
        val outputBufferMs = MutableStateFlow(0)
        val outputUnderruns = MutableStateFlow(0)
        val geminiPipelineState = MutableStateFlow("IDLE")
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
    private var currentTurnStartClockMs = -1L
    private var lastTurnStartClockMs = -1L
    private val pendingTurnStartClocks = ArrayDeque<Long>()
    private var activeOutputTurnAnchorMs = -1L
    private var geminiOutputSourceCursorMs = -1L
    private var transcriptSourceAnchorMs = -1L
    private var playbackAudioManager: AudioManager? = null
    private var sourceMediaPlaying = true

    // Low-latency Live input path: 30 ms PCM16 mono @ 16 kHz = 960 bytes.
    private val geminiInputChunk = ByteArray(960)
    private var geminiInputChunkSize = 0

    // Hybrid VAD: server handles speech-start robustly; client only nudges end-of-turn
    // after a conservative 500 ms of low-level audio to reduce the ~800 ms default wait.
    private var hybridSpeechActive = false
    private var hybridSilenceStartMs = 0L
    private var hybridSpeechStartMs = 0L
    private var hybridNoiseFloor = 0.0025f
    private var lastHybridStreamEndMs = 0L
    private var hybridPauseEmaMs = 0f
    @Volatile private var lastGeminiAudioReceivedMs = 0L
    @Volatile private var activeGeminiSyncMode = "auto"
    @Volatile private var geminiMicroCatchUpEnabled = true
    @Volatile private var geminiTailFinishEnabled = true
    @Volatile private var geminiInterruptionMode = "no_interruption"
    @Volatile private var geminiAdaptiveVadEnabled = true

    private var geminiWatchdogJob: Job? = null
    @Volatile private var geminiSessionStartedMs = 0L
    @Volatile private var lastMeaningfulSourceMs = 0L
    @Volatile private var lastSelfHealKickMs = 0L
    @Volatile private var lastSelfHealReconnectMs = 0L
    @Volatile private var geminiEverProducedAudio = false
    @Volatile private var initialNoOutputRecoveryUsed = false
    @Volatile private var notificationPaused = false
    @Volatile private var selfHealStage = 0
    @Volatile private var autoVadBiasMs = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            // ALAD's own dubbed output uses USAGE_ASSISTANT, so it is naturally
            // excluded from this source-media detector without sensitive UID APIs.
            val mediaActive = configs.orEmpty().any { config ->
                when (config.audioAttributes.usage) {
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN -> true
                    else -> false
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

            if (activeVoiceSource == "gemini") {
                if (mediaActive) {
                    if (!notificationPaused) {
                        audioPlayerManager?.setExternalPaused(false)
                        audioPlayerManager?.ensurePlaybackAlive(forceResume = true)
                    }
                    smartSyncStatus.value =
                        if (notificationPaused) "USER PAUSED" else geminiProfileLabel()
                } else {
                    mediaStopJob = serviceScope.launch {
                        // Preserve the old Tail Finish behavior without a fixed hard cut:
                        // finalize the current server turn, then keep draining until Gemini
                        // has gone quiet and the local queue is empty (or a safe max timeout).
                        val sourceStopDelay =
                            if (activeGeminiSyncMode == "continuous") 1_050L else 650L
                        delay(sourceStopDelay)
                        if (!sourceMediaPlaying && activeVoiceSource == "gemini") {
                            flushGeminiInputChunk()
                            if (usesClientActivityDetection(activeGeminiSyncMode)) {
                                synchronized(this@AudioDubbingForegroundService) {
                                    if (hybridSpeechActive) {
                                        webSocketManager?.sendActivityEnd()
                                    }
                                    hybridSpeechActive = false
                                    hybridSilenceStartMs = 0L
                                    hybridSpeechStartMs = 0L
                                }
                            } else {
                                webSocketManager?.sendAudioStreamEnd()
                            }

                            if (!geminiTailFinishEnabled) {
                                delay(250L)
                                if (!sourceMediaPlaying && activeVoiceSource == "gemini") {
                                    audioPlayerManager?.setExternalPaused(true)
                                    smartSyncStatus.value = "PAUSED"
                                }
                                return@launch
                            }

                            smartSyncStatus.value = "TAIL FINISH"
                            val tailStarted = SystemClock.elapsedRealtime()
                            while (!sourceMediaPlaying && activeVoiceSource == "gemini") {
                                val now = SystemClock.elapsedRealtime()
                                val elapsed = now - tailStarted
                                val queueMs = audioPlayerManager?.queuedDurationMs() ?: 0
                                queueLatencyMs.value = queueMs

                                val receivedTailAudio = lastGeminiAudioReceivedMs >= tailStarted
                                val quietFor = if (lastGeminiAudioReceivedMs > 0L) {
                                    now - lastGeminiAudioReceivedMs
                                } else {
                                    Long.MAX_VALUE
                                }

                                if (
                                    (receivedTailAudio && quietFor >= 750L && queueMs <= 60) ||
                                    elapsed >= 6_500L
                                ) {
                                    break
                                }
                                delay(120L)
                            }

                            if (!sourceMediaPlaying && activeVoiceSource == "gemini") {
                                audioPlayerManager?.setExternalPaused(true)
                                smartSyncStatus.value = "TAIL DONE"
                            }
                        }
                    }
                }
                return
            }

            if (mediaActive) {
                if (!notificationPaused) {
                    audioPlayerManager?.setExternalPaused(false)
                    deviceTtsManager?.setExternalPaused(false)
                }
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
            ACTION_SYNC_MINUS -> {
                adjustSync(-100)
                refreshNotification()
            }
            ACTION_SYNC_PLUS -> {
                adjustSync(100)
                refreshNotification()
            }
            ACTION_SYNC_AUTO -> {
                enableAutoSyncAndCenter()
                refreshNotification()
            }
            ACTION_NOTIFICATION_TOGGLE -> toggleNotificationPause()
            ACTION_PROFILE_CYCLE -> cycleGeminiProfile()
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
        synchronized(geminiInputChunk) { geminiInputChunkSize = 0 }
        lastGeminiAudioReceivedMs = 0L
        activeGeminiSyncMode = "auto"
        geminiMicroCatchUpEnabled = true
        geminiTailFinishEnabled = true
        geminiInterruptionMode = "no_interruption"
        geminiAdaptiveVadEnabled = true
        geminiSessionStartedMs = SystemClock.elapsedRealtime()
        lastMeaningfulSourceMs = 0L
        lastSelfHealKickMs = 0L
        lastSelfHealReconnectMs = 0L
        geminiEverProducedAudio = false
        initialNoOutputRecoveryUsed = false
        notificationPaused = false
        selfHealStage = 0
        autoVadBiasMs = 0L
        selfHealStage = 0
        autoVadBiasMs = 0L
        geminiPipelineState.value = "CONNECTING"
        synchronized(this) {
            hybridSpeechActive = false
            hybridSilenceStartMs = 0L
            hybridSpeechStartMs = 0L
            hybridNoiseFloor = 0.0025f
            lastHybridStreamEndMs = 0L
            hybridPauseEmaMs = 0f
        }
        audioClockSync.reset()
        audioClockMs.value = 0L
        audioClockLagMs.value = 0L
        lastInputSourceAnchorMs = -1L
        currentTurnStartClockMs = -1L
        lastTurnStartClockMs = -1L
        pendingTurnStartClocks.clear()
        activeOutputTurnAnchorMs = -1L
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
            val geminiSyncMode = prefs.geminiSyncModeFlow.first()
            val geminiMicroCatchUp = prefs.geminiMicroCatchUpFlow.first()
            val geminiTailFinish = prefs.geminiTailFinishFlow.first()
            val geminiInterruption = prefs.geminiInterruptionModeFlow.first()
            val geminiAdaptiveVad = prefs.geminiAdaptiveVadFlow.first()

            activeGeminiSyncMode = geminiSyncMode
            geminiMicroCatchUpEnabled = geminiMicroCatchUp
            geminiTailFinishEnabled = geminiTailFinish
            geminiInterruptionMode = geminiInterruption
            geminiAdaptiveVadEnabled = geminiAdaptiveVad

            activeVoiceSource = voiceSource
            syncOffsetMs.value = manualSync
            refreshNotification()
            autoSyncActive.value = if (voiceSource == "gemini") {
                (geminiSyncMode == "balanced" || geminiSyncMode == "auto") &&
                    geminiMicroCatchUp
            } else {
                autoSync
            }

            val wsClient = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            webSocketManager = ALADWebSocketManager(wsClient)
            smartSyncManager = SmartSyncManager(applicationContext, wsClient)
            if (voiceSource == "device_tts") {
                prepareSmartSync()
            } else {
                smartSyncStatus.value = geminiProfileLabel()
                smartSyncPositionMs.value = -1L
                audioClockLagMs.value = 0L
            }

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
                val useMicroCatchUp =
                    (geminiSyncMode == "balanced" || geminiSyncMode == "auto") &&
                        geminiMicroCatchUp
                audioPlayerManager = AudioPlayerManager(applicationContext).also { player ->
                    player.configure(
                        mode = dubMode,
                        volume = volumeRatio,
                        syncMs = manualSync,
                        autoSyncEnabled = false,
                        catchUpEnabled = useMicroCatchUp,
                        lowLatencyEnabled = geminiSyncMode != "stable",
                        maxSpeed = 1.08f
                    )
                    player.setStableLiveMode(
                        enabled = true,
                        profile = geminiSyncMode,
                        microCatchUp = useMicroCatchUp
                    )
                    player.start()
                }
                autoSyncActive.value = useMicroCatchUp
            }

            webSocketManager?.onStatusChanged = { status ->
                when {
                    status.startsWith("Gemini Ready") -> geminiPipelineState.value = "READY"
                    status.contains("reconnect", ignoreCase = true) ||
                        status.startsWith("Connecting") -> geminiPipelineState.value = "RECOVERING"
                    status.startsWith("Fatal auth") -> geminiPipelineState.value = "ERROR"
                }
                refreshNotification()
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
                    lastGeminiAudioReceivedMs = SystemClock.elapsedRealtime()
                    geminiEverProducedAudio = true
                    selfHealStage = 0
                    if (activeGeminiSyncMode == "auto") {
                        autoVadBiasMs = (autoVadBiasMs - 20L).coerceAtLeast(0L)
                    }
                    geminiPipelineState.value = "PLAYING"
                    audioPlayerManager?.ensurePlaybackAlive(forceResume = !notificationPaused)
                    audioPlayerManager?.playAudioData(audioChunk)
                    val player = audioPlayerManager
                    queueLatencyMs.value = player?.queuedDurationMs() ?: 0
                    audioClockLagMs.value = queueLatencyMs.value.toLong()
                    nativeOutputRateHz.value = player?.nativeOutputRateHz() ?: 0
                    outputBufferMs.value = player?.outputBufferMs() ?: 0
                    outputUnderruns.value = player?.underrunCount() ?: 0
                }
            }

            webSocketManager?.onInputTranscription = { text, isFinal ->
                if (activeVoiceSource == "device_tts" && isFinal) {
                    val fallback = audioClockSync.markInputFinal()
                    val anchor = when {
                        currentTurnStartClockMs >= 0L -> currentTurnStartClockMs
                        lastTurnStartClockMs >= 0L -> lastTurnStartClockMs
                        else -> fallback
                    }
                    lastInputSourceAnchorMs = anchor
                    audioClockLagMs.value = audioClockSync.lagFrom(anchor)
                    if (!smartSubtitlePlaybackActive) {
                        smartSyncStatus.value = "TURN CLOCK"
                    }
                    handleSmartInputTranscript(text)
                }
            }

            webSocketManager?.onOutputTranscription = { text ->
                if (activeVoiceSource == "device_tts" && !smartSubtitlePlaybackActive) {
                    if (transcriptSourceAnchorMs < 0L) {
                        transcriptSourceAnchorMs = synchronized(this@AudioDubbingForegroundService) {
                            if (pendingTurnStartClocks.isNotEmpty()) {
                                pendingTurnStartClocks.removeFirst()
                            } else if (lastTurnStartClockMs >= 0L) {
                                lastTurnStartClockMs
                            } else {
                                lastInputSourceAnchorMs
                            }
                        }
                    }
                    processTranscriptFragment(text, transcriptSourceAnchorMs)
                }
            }
            webSocketManager?.onGenerationComplete = {
                if (activeVoiceSource == "gemini") {
                    geminiPipelineState.value = "IDLE"
                }
            }
            webSocketManager?.onTurnComplete = {
                if (activeVoiceSource == "device_tts") {
                    flushTranscriptBuffer(resetSnapshot = true)
                    transcriptSourceAnchorMs = -1L
                } else {
                    geminiPipelineState.value = "IDLE"
                }
            }
            webSocketManager?.onInterrupted = {
                if (activeVoiceSource == "gemini") {
                    if (geminiInterruptionMode == "interrupt") {
                        audioPlayerManager?.clearForExternalSeek()
                        queueLatencyMs.value = 0
                    }
                } else {
                    synchronized(transcriptBuffer) {
                        transcriptBuffer.clear()
                        lastTranscriptSnapshot = ""
                    }
                    deviceTtsManager?.clearBacklog()
                }
            }

            webSocketManager?.connect(
                apiKey,
                "",
                targetLang,
                voiceName,
                enableTranscription = voiceSource == "device_tts",
                vadSilenceMs = when {
                    voiceSource != "gemini" -> 550
                    geminiSyncMode == "stable" || geminiSyncMode == "continuous" -> 800
                    geminiSyncMode == "ultra_fast" -> 500
                    else -> 550
                },
                activityHandling = if (
                    voiceSource == "gemini" && geminiInterruption == "interrupt"
                ) {
                    "START_OF_ACTIVITY_INTERRUPTS"
                } else {
                    "NO_INTERRUPTION"
                },
                clientActivityDetection =
                    voiceSource == "gemini" && usesClientActivityDetection(geminiSyncMode)
            )
            if (voiceSource == "device_tts") startSmartSyncScheduler()

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
                            webSocketManager?.connect(
                                currentKey,
                                "",
                                newLang,
                                newVoice,
                                enableTranscription = activeVoiceSource == "device_tts",
                                vadSilenceMs = when {
                                    activeVoiceSource != "gemini" -> 550
                                    activeGeminiSyncMode == "stable" ||
                                        activeGeminiSyncMode == "continuous" -> 800
                                    activeGeminiSyncMode == "ultra_fast" -> 500
                                    else -> 550
                                },
                                activityHandling = if (
                                    activeVoiceSource == "gemini" &&
                                    geminiInterruptionMode == "interrupt"
                                ) {
                                    "START_OF_ACTIVITY_INTERRUPTS"
                                } else {
                                    "NO_INTERRUPTION"
                                },
                                clientActivityDetection =
                                    activeVoiceSource == "gemini" &&
                                        usesClientActivityDetection(activeGeminiSyncMode)
                            )
                        }
                    }
            }

            audioCaptureManager = AudioCaptureManager()
            val appUid = applicationInfo.uid
            mediaProjection?.let { projection ->
                audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                    val normalized = calculateNormalizedLevel(pcmData)

                    captureBlockMs.value =
                        audioCaptureManager?.lastReadDurationMs() ?: 0L

                    if (notificationPaused) {
                        audioAmplitude.value = 0f
                        return@startCapture
                    }

                    if (activeVoiceSource == "gemini") {
                        if (normalized >= 0.006f) {
                            lastMeaningfulSourceMs = SystemClock.elapsedRealtime()
                        }
                        feedGeminiContinuousAudio(pcmData)
                        if (usesClientActivityDetection(activeGeminiSyncMode)) {
                            updateHybridGeminiVad(normalized)
                        }
                    } else {
                        val clockUpdate = audioClockSync.onCaptured(pcmData.size)
                        audioClockMs.value = clockUpdate.sourceClockMs
                        if (clockUpdate.discontinuity) {
                            handleAudioClockDiscontinuity(clockUpdate.wallGapMs)
                        }
                        feedAudioThroughVad(pcmData, normalized)
                    }

                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }

            if (voiceSource == "gemini") {
                startGeminiWatchdog()
            }
        }
    }

    private fun startGeminiWatchdog() {
        geminiWatchdogJob?.cancel()
        geminiWatchdogJob = serviceScope.launch {
            while (isRunning.value) {
                delay(750L)
                if (activeVoiceSource != "gemini" || notificationPaused) continue

                val now = SystemClock.elapsedRealtime()
                val recentSource =
                    lastMeaningfulSourceMs > 0L &&
                    now - lastMeaningfulSourceMs <= 2_500L
                val ws = webSocketManager ?: continue

                // Stage 1: a reconnect that keeps the latest resumption handle.
                // If that reconnect stays silent, escalate to a clean session only then.
                if (
                    selfHealStage == 1 &&
                    lastGeminiAudioReceivedMs < lastSelfHealReconnectMs &&
                    now - lastSelfHealReconnectMs >= 3_500L
                ) {
                    selfHealStage = 2
                    lastSelfHealReconnectMs = now
                    geminiSessionStartedMs = now
                    if (activeGeminiSyncMode == "auto") {
                        autoVadBiasMs = (autoVadBiasMs + 80L).coerceAtMost(180L)
                    }
                    synchronized(this@AudioDubbingForegroundService) {
                        hybridSpeechActive = false
                        hybridSilenceStartMs = 0L
                        hybridSpeechStartMs = 0L
                        lastHybridStreamEndMs = 0L
                    }
                    audioPlayerManager?.ensurePlaybackAlive(forceResume = true)
                    geminiPipelineState.value = "RECOVERING"
                    smartSyncStatus.value = "SELF-HEAL HARD RESET"
                    refreshNotification()
                    ws.forceReconnect(
                        resetSession = true,
                        reason = "resume produced no audio"
                    )
                    continue
                }

                if (
                    selfHealStage == 2 &&
                    lastGeminiAudioReceivedMs < lastSelfHealReconnectMs &&
                    now - lastSelfHealReconnectMs >= 12_000L
                ) {
                    // Allow another staged recovery later, but avoid a tight reconnect loop.
                    selfHealStage = 0
                }

                if (!recentSource || selfHealStage != 0) continue

                val referenceOutputMs =
                    if (lastGeminiAudioReceivedMs > 0L) {
                        lastGeminiAudioReceivedMs
                    } else {
                        geminiSessionStartedMs
                    }
                val outputSilenceMs = (now - referenceOutputMs).coerceAtLeast(0L)
                val serverSilentWhileSending =
                    ws.audioSendSilenceMs() <= 1_500L &&
                    ws.serverSilenceMs() >= 7_000L

                val clientTurnWaiting =
                    usesClientActivityDetection(activeGeminiSyncMode) &&
                    !hybridSpeechActive &&
                    lastHybridStreamEndMs > 0L &&
                    now - lastHybridStreamEndMs <= 5_000L &&
                    geminiPipelineState.value == "GENERATING" &&
                    outputSilenceMs >= 5_000L

                val initialNoOutputStall =
                    !geminiEverProducedAudio &&
                    outputSilenceMs >= 10_000L

                if (
                    !serverSilentWhileSending &&
                    !clientTurnWaiting &&
                    !initialNoOutputStall
                ) {
                    continue
                }

                selfHealStage = 1
                lastSelfHealReconnectMs = now
                geminiSessionStartedMs = now
                if (activeGeminiSyncMode == "auto") {
                    autoVadBiasMs = (autoVadBiasMs + 60L).coerceAtMost(180L)
                }
                audioPlayerManager?.ensurePlaybackAlive(forceResume = true)
                geminiPipelineState.value = "RECOVERING"
                smartSyncStatus.value = "SELF-HEAL RESUME"
                refreshNotification()
                ws.forceReconnect(
                    resetSession = false,
                    reason = "resume-first watchdog"
                )
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

    private fun feedGeminiContinuousAudio(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return

        // True 30 ms capture already arrives as exactly 960 bytes. Send it directly
        // to avoid an extra copy/allocation on the realtime hot path.
        if (pcmData.size == geminiInputChunk.size) {
            synchronized(geminiInputChunk) {
                if (geminiInputChunkSize == 0) {
                    webSocketManager?.sendAudioData(pcmData)
                    return
                }
            }
        }

        synchronized(geminiInputChunk) {
            var offset = 0
            while (offset < pcmData.size) {
                val space = geminiInputChunk.size - geminiInputChunkSize
                val count = minOf(space, pcmData.size - offset)
                System.arraycopy(
                    pcmData,
                    offset,
                    geminiInputChunk,
                    geminiInputChunkSize,
                    count
                )
                geminiInputChunkSize += count
                offset += count

                if (geminiInputChunkSize == geminiInputChunk.size) {
                    webSocketManager?.sendAudioData(geminiInputChunk)
                    geminiInputChunkSize = 0
                }
            }
        }
    }

    private fun flushGeminiInputChunk() {
        synchronized(geminiInputChunk) {
            if (geminiInputChunkSize <= 0) return
            webSocketManager?.sendAudioData(geminiInputChunk.copyOf(geminiInputChunkSize))
            geminiInputChunkSize = 0
        }
    }

    @Synchronized
    private fun updateHybridGeminiVad(level: Float) {
        val now = SystemClock.elapsedRealtime()
        val startThreshold = maxOf(0.0050f, hybridNoiseFloor * 2.5f)
        val holdThreshold = maxOf(0.0038f, hybridNoiseFloor * 1.8f)

        if (!hybridSpeechActive) {
            if (level < startThreshold) {
                hybridNoiseFloor =
                    (hybridNoiseFloor * 0.985f + level * 0.015f).coerceIn(0.0012f, 0.02f)
            }
            if (level >= startThreshold) {
                hybridSpeechActive = true
                hybridSpeechStartMs = now
                hybridSilenceStartMs = 0L
                webSocketManager?.sendActivityStart()
                geminiPipelineState.value = "SPEAKING"
            }
            return
        }

        if (level >= holdThreshold) {
            if (hybridSilenceStartMs > 0L && geminiAdaptiveVadEnabled) {
                val shortPause = now - hybridSilenceStartMs
                if (shortPause in 60L..700L) {
                    hybridPauseEmaMs = if (hybridPauseEmaMs <= 0f) {
                        shortPause.toFloat()
                    } else {
                        hybridPauseEmaMs * 0.75f + shortPause.toFloat() * 0.25f
                    }
                }
            }
            hybridSilenceStartMs = 0L
            return
        }

        if (hybridSilenceStartMs == 0L) {
            hybridSilenceStartMs = now
            return
        }

        val silenceMs = now - hybridSilenceStartMs
        val speechAgeMs = now - hybridSpeechStartMs

        val baseEndSilenceMs = when (activeGeminiSyncMode) {
            "ultra_fast" -> 320L
            "auto" -> 500L + autoVadBiasMs
            else -> 500L
        }
        val endSilenceMs = when {
            activeGeminiSyncMode == "auto" -> {
                if (!geminiAdaptiveVadEnabled || hybridPauseEmaMs <= 0f) {
                    baseEndSilenceMs.coerceIn(420L, 680L)
                } else {
                    (hybridPauseEmaMs.toLong() + 140L + autoVadBiasMs)
                        .coerceIn(420L, 680L)
                }
            }
            !geminiAdaptiveVadEnabled || hybridPauseEmaMs <= 0f -> baseEndSilenceMs
            activeGeminiSyncMode == "ultra_fast" ->
                (hybridPauseEmaMs.toLong() + 90L).coerceIn(280L, 460L)
            else ->
                (hybridPauseEmaMs.toLong() + 120L).coerceIn(380L, 620L)
        }

        val minSpeechMs = when (activeGeminiSyncMode) {
            "ultra_fast" -> 200L
            "auto" -> 240L
            else -> 260L
        }
        val endCooldownMs = when (activeGeminiSyncMode) {
            "ultra_fast" -> 420L
            "auto" -> 550L
            else -> 600L
        }

        if (
            silenceMs >= endSilenceMs &&
            speechAgeMs >= minSpeechMs &&
            now - lastHybridStreamEndMs >= endCooldownMs
        ) {
            flushGeminiInputChunk()
            webSocketManager?.sendActivityEnd()
            lastHybridStreamEndMs = now
            geminiPipelineState.value = "GENERATING"
            hybridSpeechActive = false
            hybridSilenceStartMs = 0L
            hybridSpeechStartMs = 0L
            smartSyncStatus.value = geminiProfileLabel()
        }
    }

    private fun usesClientActivityDetection(mode: String): Boolean =
        mode == "auto" ||
            mode == "ultra_fast" ||
            mode == "hybrid_fast" ||
            mode == "balanced"

    private fun geminiProfileLabel(): String = when (activeGeminiSyncMode) {
        "auto" -> "AUTO RELIABLE"
        "continuous" -> "CONTINUOUS"
        "stable" -> "STABLE"
        "ultra_fast" -> "ULTRA FAST"
        "hybrid_fast" -> "HYBRID FAST"
        else -> if (geminiMicroCatchUpEnabled) "BALANCED" else "BALANCED FIXED"
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
                // Anchor the translated turn to the START of the source utterance, not to
                // the later "input transcription final" event.
                val preRollBytes = vadPreRoll.sumOf { it.size.toLong() }
                val preRollMs = preRollBytes / 32L
                currentTurnStartClockMs =
                    (audioClockSync.currentClockMs() - preRollMs).coerceAtLeast(0L)
                lastTurnStartClockMs = currentTurnStartClockMs
                synchronized(this) {
                    pendingTurnStartClocks.addLast(currentTurnStartClockMs)
                    while (pendingTurnStartClocks.size > 8) {
                        pendingTurnStartClocks.removeFirst()
                    }
                }

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
            if (vadSpeechActive) {
                lastTurnStartClockMs = currentTurnStartClockMs
            }
            vadSpeechActive = false
            currentTurnStartClockMs = -1L
        }
    }

    private fun handleAudioClockDiscontinuity(wallGapMs: Long) {
        if (!isRunning.value) return

        // A capture gap may be buffering, app scheduling, or a pause. It is NOT enough
        // evidence to throw away translated content. Preserve all generated/queued speech
        // and only reset VAD boundary detection for the next source turn.
        synchronized(this) {
            vadPreRoll.clear()
            vadSpeechActive = false
            vadHangoverChunks = 0
            currentTurnStartClockMs = -1L
        }

        if (!smartSubtitlePlaybackActive) {
            smartSyncStatus.value = "CLOCK GAP · keep audio"
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
                // Preserve queued translated content on pause; resume from the same place.
                audioPlayerManager?.setExternalPaused(true)
                deviceTtsManager?.setExternalPaused(true)
                queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
            }
            "PLAY" -> {
                if (!notificationPaused) {
                    audioPlayerManager?.setExternalPaused(false)
                    deviceTtsManager?.setExternalPaused(false)
                }
            }
            "SEEK" -> {
                audioPlayerManager?.clearForExternalSeek()
                audioPlayerManager?.setExternalPaused(notificationPaused || !playing)
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
                audioPlayerManager?.setExternalPaused(notificationPaused || !playing)
            }
            else -> {
                audioPlayerManager?.setExternalPaused(notificationPaused || !playing)
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
            refreshNotification()
        }
    }

    private fun enableAutoSyncAndCenter() {
        if (!isRunning.value) return
        serviceScope.launch {
            syncOffsetMs.value = 0
            audioPlayerManager?.setManualSyncMs(0)

            if (activeVoiceSource == "gemini") {
                val allowMicro =
                    (activeGeminiSyncMode == "balanced" ||
                        activeGeminiSyncMode == "auto") &&
                        geminiMicroCatchUpEnabled
                autoSyncActive.value = allowMicro
                audioPlayerManager?.setAutoSyncEnabled(false)
                audioPlayerManager?.setBalancedMicroCatchUp(allowMicro)
                repository?.updateAutoSync(allowMicro)
            } else {
                autoSyncActive.value = true
                audioPlayerManager?.setAutoSyncEnabled(true)
                repository?.updateAutoSync(true)
            }
            repository?.updateManualSyncMs(0)
            refreshNotification()
        }
    }

    private fun stopDubbing() {
        isRunning.value = false
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        synchronized(geminiInputChunk) { geminiInputChunkSize = 0 }
        geminiInterruptionMode = "no_interruption"
        geminiAdaptiveVadEnabled = true
        synchronized(this) {
            hybridSpeechActive = false
            hybridSilenceStartMs = 0L
            hybridSpeechStartMs = 0L
            hybridNoiseFloor = 0.0025f
            lastHybridStreamEndMs = 0L
            hybridPauseEmaMs = 0f
        }
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
        captureBlockMs.value = 0L
        nativeOutputRateHz.value = 0
        outputBufferMs.value = 0
        outputUnderruns.value = 0
        geminiPipelineState.value = "IDLE"
        geminiWatchdogJob?.cancel()
        geminiWatchdogJob = null
        geminiSessionStartedMs = 0L
        lastMeaningfulSourceMs = 0L
        lastSelfHealKickMs = 0L
        lastSelfHealReconnectMs = 0L
        geminiEverProducedAudio = false
        initialNoOutputRecoveryUsed = false
        notificationPaused = false
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
        currentTurnStartClockMs = -1L
        lastTurnStartClockMs = -1L
        synchronized(this) { pendingTurnStartClocks.clear() }
        activeOutputTurnAnchorMs = -1L
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

    private fun toggleNotificationPause() {
        if (!isRunning.value) return
        notificationPaused = !notificationPaused

        if (notificationPaused) {
            flushGeminiInputChunk()
            if (activeVoiceSource == "gemini") {
                if (usesClientActivityDetection(activeGeminiSyncMode)) {
                    synchronized(this) {
                        if (hybridSpeechActive) webSocketManager?.sendActivityEnd()
                        hybridSpeechActive = false
                        hybridSilenceStartMs = 0L
                        hybridSpeechStartMs = 0L
                    }
                } else {
                    webSocketManager?.sendAudioStreamEnd()
                }
                geminiPipelineState.value = "PAUSED"
            }
            audioPlayerManager?.setExternalPaused(true)
            deviceTtsManager?.setExternalPaused(true)
            smartSyncStatus.value = "USER PAUSED"
        } else {
            lastMeaningfulSourceMs = 0L
            geminiSessionStartedMs = SystemClock.elapsedRealtime()
            audioPlayerManager?.setExternalPaused(false)
            audioPlayerManager?.ensurePlaybackAlive(forceResume = true)
            deviceTtsManager?.setExternalPaused(false)
            smartSyncStatus.value =
                if (activeVoiceSource == "gemini") geminiProfileLabel() else "Live Sync"
        }
        refreshNotification()
    }

    private fun cycleGeminiProfile() {
        if (!isRunning.value || activeVoiceSource != "gemini") return
        serviceScope.launch {
            val order = listOf(
                "auto",
                "ultra_fast",
                "hybrid_fast",
                "balanced",
                "continuous",
                "stable"
            )
            val index = order.indexOf(activeGeminiSyncMode).let { if (it < 0) 0 else it }
            val next = order[(index + 1) % order.size]

            activeGeminiSyncMode = next
            val useMicro =
                (next == "balanced" || next == "auto") && geminiMicroCatchUpEnabled

            synchronized(this@AudioDubbingForegroundService) {
                hybridSpeechActive = false
                hybridSilenceStartMs = 0L
                hybridSpeechStartMs = 0L
                lastHybridStreamEndMs = 0L
                hybridPauseEmaMs = 0f
            }

            audioPlayerManager?.setStableLiveMode(
                enabled = true,
                profile = next,
                microCatchUp = useMicro
            )
            audioPlayerManager?.setBalancedMicroCatchUp(useMicro)
            autoSyncActive.value = useMicro
            repository?.updateGeminiSyncMode(next)

            val vadMs = when (next) {
                "stable", "continuous" -> 800
                "ultra_fast" -> 500
                else -> 550
            }
            val activityHandling =
                if (geminiInterruptionMode == "interrupt") {
                    "START_OF_ACTIVITY_INTERRUPTS"
                } else {
                    "NO_INTERRUPTION"
                }
            webSocketManager?.reconfigureRealtime(
                vadMs,
                activityHandling,
                clientActivityDetection = usesClientActivityDetection(next)
            )

            geminiSessionStartedMs = SystemClock.elapsedRealtime()
            lastSelfHealKickMs = 0L
            lastSelfHealReconnectMs = 0L
            selfHealStage = 0
            autoVadBiasMs = 0L
            smartSyncStatus.value = geminiProfileLabel()
            refreshNotification()
        }
    }

    private fun profileShortName(): String = when (activeGeminiSyncMode) {
        "auto" -> "AUTO"
        "ultra_fast" -> "ULTRA"
        "hybrid_fast" -> "HYBRID"
        "balanced" -> "BALANCED"
        "continuous" -> "CONTINUOUS"
        "stable" -> "STABLE"
        else -> activeGeminiSyncMode.uppercase()
    }

    private fun serviceActionPendingIntent(
        requestCode: Int,
        actionName: String
    ): android.app.PendingIntent {
        val actionIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = actionName
        }
        return android.app.PendingIntent.getService(
            this,
            requestCode,
            actionIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun refreshNotification() {
        if (!isRunning.value) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseAction = NotificationCompat.Action(
            if (notificationPaused) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause,
            if (notificationPaused) "Resume" else "Pause",
            serviceActionPendingIntent(10, ACTION_NOTIFICATION_TOGGLE)
        )
        val minusAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew,
            "-100",
            serviceActionPendingIntent(11, ACTION_SYNC_MINUS)
        )
        val autoAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_revert,
            if (autoSyncActive.value) "Auto" else "Sync 0",
            serviceActionPendingIntent(12, ACTION_SYNC_AUTO)
        )
        val plusAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff,
            "+100",
            serviceActionPendingIntent(13, ACTION_SYNC_PLUS)
        )
        val profileAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_manage,
            profileShortName(),
            serviceActionPendingIntent(14, ACTION_PROFILE_CYCLE)
        )

        val stateText = when {
            notificationPaused -> "PAUSED"
            smartSyncStatus.value.startsWith("SELF-HEAL") -> smartSyncStatus.value
            else -> profileShortName()
        }
        val detailText =
            stateText +
                " · " + geminiPipelineState.value +
                " · Sync " +
                (if (syncOffsetMs.value >= 0) "+" else "") +
                syncOffsetMs.value +
                "ms · q" +
                queueLatencyMs.value +
                "ms"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (notificationPaused) "ALAD · Paused"
                else "ALAD · " + profileShortName()
            )
            .setContentText(detailText)
            .setSubText("Self-Heal ON")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .addAction(pauseAction)
            .addAction(minusAction)
            .addAction(autoAction)
            .addAction(plusAction)
            .addAction(profileAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 2, 4)
            )
            .setOngoing(true)
            .build()
    }
}
