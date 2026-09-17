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
    private var webSocketManager: ALADWebSocketManager? = null
    private var sessionSettingsJob: Job? = null
    private var repository: UserPreferencesRepository? = null

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
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                }
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
            val volumeRatio = prefs.volumeRatioFlow.first()
            val dubMode = prefs.dubModeFlow.first()
            val manualSync = prefs.manualSyncMsFlow.first()
            val autoSync = prefs.autoSyncFlow.first()
            val catchUp = prefs.catchUpFlow.first()
            val lowLatency = prefs.lowLatencyFlow.first()
            val maxCatchUpSpeed = prefs.maxCatchUpSpeedFlow.first()

            syncOffsetMs.value = manualSync
            autoSyncActive.value = autoSync

            val wsClient = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            webSocketManager = ALADWebSocketManager(wsClient)

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

            webSocketManager?.onStatusChanged = { status ->
                // Keep reconnect visible without blocking audio processing.
                if (status.startsWith("Error") || status.startsWith("Reconnecting")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "ALAD: $status",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                audioPlayerManager?.playAudioData(audioChunk)
                queueLatencyMs.value = audioPlayerManager?.queuedDurationMs() ?: 0
            }

            webSocketManager?.connect(apiKey, "", targetLang, voiceName)

            // Voice and target language are session-level Gemini settings, so changing
            // either one automatically creates a fresh Live session.
            sessionSettingsJob?.cancel()
            sessionSettingsJob = serviceScope.launch {
                var firstEmit = true
                combine(
                    prefs.targetLangFlow,
                    prefs.voiceNameFlow
                ) { language, voice -> language to voice }
                    .distinctUntilChanged()
                    .collect { (newLang, newVoice) ->
                        if (firstEmit) {
                            firstEmit = false
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
                            val sample =
                                (pcmData[i].toInt() and 0xFF) or
                                    (pcmData[i + 1].toInt() shl 8)
                            val signedSample = sample.toShort().toFloat()
                            sum += signedSample * signedSample
                        }
                    }

                    val rms = if (pcmData.isNotEmpty()) {
                        sqrt(sum / (pcmData.size / 2)).toFloat()
                    } else {
                        0f
                    }

                    val normalized = (rms / 32767f * 3f).coerceIn(0f, 1f)
                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }
        }
    }

    private fun adjustSync(deltaMs: Int) {
        if (!isRunning.value) return
        serviceScope.launch {
            val newOffset = audioPlayerManager?.nudgeSync(deltaMs)
                ?: (syncOffsetMs.value + deltaMs).coerceIn(-2000, 5000)
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

        webSocketManager?.disconnect()
        webSocketManager = null

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
                "Live Dubbing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Capturing and streaming audio for dubbing"
            }

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
            this,
            0,
            openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD Live Dubbing")
            .setContentText("Live translate · adaptive sync")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }
}
