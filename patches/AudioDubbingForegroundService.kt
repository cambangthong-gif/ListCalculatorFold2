package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.DeviceTtsManager
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        val isRunning = MutableStateFlow(false)
        val audioAmplitude = MutableStateFlow(0f)
        val syncOffsetMs = MutableStateFlow(0)
        val autoSyncActive = MutableStateFlow(true)
        val queueLatencyMs = MutableStateFlow(0)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var deviceTtsManager: DeviceTtsManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var sessionSettingsJob: Job? = null
    private var repository: UserPreferencesRepository? = null

    private val transcriptBuffer = StringBuilder()
    private var lastTranscriptSnapshot = ""
    private var activeVoiceSource = "gemini"

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
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        serviceScope.launch {
            val prefs = repository ?: UserPreferencesRepository(applicationContext)
            val apiKey = prefs.apiKeyFlow.first()
            val targetLang = prefs.targetLangFlow.first()
            val voiceName = prefs.voiceNameFlow.first()
            val voiceSource = prefs.voiceSourceFlow.first()
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

            if (voiceSource == "device_tts") {
                deviceTtsManager = DeviceTtsManager(applicationContext).also { manager ->
                    manager.configure(
                        languageTag = targetLang,
                        speechRate = ttsRate,
                        outputVolume = volumeRatio,
                        catchUp = catchUp,
                        lowLatency = lowLatency,
                        maxSpeed = maxCatchUpSpeed
                    )
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
                    player.start()
                }
            }

            webSocketManager?.onStatusChanged = { status ->
                if (status.startsWith("Error") || status.startsWith("Reconnecting")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "ALAD v3: $status",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                if (activeVoiceSource == "gemini") {
                    audioPlayerManager?.playAudioData(audioChunk)
                    queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
                }
            }

            webSocketManager?.onOutputTranscription = { text ->
                if (activeVoiceSource == "device_tts") processTranscriptFragment(text)
            }
            webSocketManager?.onTurnComplete = {
                if (activeVoiceSource == "device_tts") flushTranscriptBuffer()
            }
            webSocketManager?.onInterrupted = {
                synchronized(transcriptBuffer) {
                    transcriptBuffer.clear()
                    lastTranscriptSnapshot = ""
                }
                deviceTtsManager?.clearBacklog()
            }

            webSocketManager?.connect(apiKey, "", targetLang, voiceName)

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
                            // Audio pipeline type changed; restarting capture from UI is safer
                            // because MediaProjection permission belongs to this foreground session.
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
                    webSocketManager?.sendAudioData(pcmData)
                    var sum = 0.0
                    for (i in pcmData.indices step 2) {
                        if (i + 1 < pcmData.size) {
                            val sample = (pcmData[i].toInt() and 0xFF) or
                                (pcmData[i + 1].toInt() shl 8)
                            val signedSample = sample.toShort().toFloat()
                            sum += signedSample * signedSample
                        }
                    }
                    val rms = if (pcmData.isNotEmpty()) {
                        sqrt(sum / (pcmData.size / 2)).toFloat()
                    } else 0f
                    val normalized = (rms / 32767f * 3f).coerceIn(0f, 1f)
                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }
        }
    }

    private fun processTranscriptFragment(text: String) {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return
        synchronized(transcriptBuffer) {
            val delta = when {
                lastTranscriptSnapshot.isNotBlank() && cleaned.startsWith(lastTranscriptSnapshot) ->
                    cleaned.removePrefix(lastTranscriptSnapshot).trimStart()
                else -> cleaned
            }
            lastTranscriptSnapshot = cleaned
            if (delta.isNotBlank()) {
                if (transcriptBuffer.isNotEmpty() && !transcriptBuffer.last().isWhitespace()) {
                    transcriptBuffer.append(' ')
                }
                transcriptBuffer.append(delta)
            }
            val shouldFlush = transcriptBuffer.length >= 80 ||
                transcriptBuffer.toString().trimEnd().lastOrNull() in listOf('.', '!', '?', '…', ':', ';')
            if (shouldFlush) flushTranscriptBufferLocked()
        }
    }

    private fun flushTranscriptBuffer() {
        synchronized(transcriptBuffer) { flushTranscriptBufferLocked() }
    }

    private fun flushTranscriptBufferLocked() {
        val text = transcriptBuffer.toString().trim()
        transcriptBuffer.clear()
        lastTranscriptSnapshot = ""
        if (text.isNotBlank()) deviceTtsManager?.enqueueText(text)
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
        audioAmplitude.value = 0f
        queueLatencyMs.value = 0
        sessionSettingsJob?.cancel()
        sessionSettingsJob = null
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
                "ALAD TTS Sync v3",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Real-time dubbing with Gemini or device TTS" }
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
            .setContentTitle("ALAD TTS Sync v3")
            .setContentText("Live translate · Gemini / Device TTS")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
