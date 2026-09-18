package com.alad.app.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class YouTubeCompanionService : NotificationListenerService() {

    companion object {
        val listenerConnected = MutableStateFlow(false)
        val youtubeActive = MutableStateFlow(false)
        val isPlaying = MutableStateFlow(false)
        val playbackPositionMs = MutableStateFlow(0L)
        val playbackSpeed = MutableStateFlow(1f)
        val sharedVideoUrl = MutableStateFlow("")
        val statusText = MutableStateFlow("Companion idle")

        private const val PREFS = "youtube_companion"
        private const val KEY_URL = "shared_url"

        fun setSharedVideo(context: Context, url: String) {
            val cleaned = url.trim()
            sharedVideoUrl.value = cleaned
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, cleaned)
                .apply()
            statusText.value = if (cleaned.isBlank()) "Companion idle" else "YouTube linked"
        }

        fun restoreSharedVideo(context: Context) {
            if (sharedVideoUrl.value.isBlank()) {
                sharedVideoUrl.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_URL, "")
                    .orEmpty()
            }
        }

        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return enabled.split(":").any { flat ->
                ComponentName.unflattenFromString(flat)?.packageName == context.packageName
            }
        }
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var lastReportedPosition = 0L
    private var lastReportedState = PlaybackState.STATE_NONE
    private var lastReportedSpeed = 1f
    private var lastStateChangeElapsed = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            activeController?.unregisterCallback(this)
            activeController = null
            youtubeActive.value = false
            isPlaying.value = false
            statusText.value = "YouTube session ended"
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectYouTubeController(controllers.orEmpty())
        }

    override fun onCreate() {
        super.onCreate()
        restoreSharedVideo(this)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected.value = true
        statusText.value = "Companion ready"
        val component = ComponentName(this, YouTubeCompanionService::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                component
            )
            selectYouTubeController(
                mediaSessionManager?.getActiveSessions(component).orEmpty()
            )
        } catch (_: SecurityException) {
            listenerConnected.value = false
            statusText.value = "Enable Notification access"
        }
    }

    override fun onListenerDisconnected() {
        listenerConnected.value = false
        youtubeActive.value = false
        isPlaying.value = false
        statusText.value = "Notification access off"
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Throwable) {
        }
        super.onListenerDisconnected()
    }

    private fun selectYouTubeController(controllers: List<MediaController>) {
        val youtube = controllers.firstOrNull {
            it.packageName == "com.google.android.youtube" ||
                it.packageName.startsWith("com.google.android.youtube.")
        }

        if (youtube === activeController) {
            handlePlaybackState(youtube?.playbackState)
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = youtube

        if (youtube == null) {
            youtubeActive.value = false
            isPlaying.value = false
            statusText.value = if (sharedVideoUrl.value.isBlank()) {
                "Share a YouTube video to ALAD"
            } else {
                "Waiting for YouTube"
            }
            return
        }

        youtube.registerCallback(controllerCallback)
        youtubeActive.value = true
        statusText.value = "YouTube detected"
        handlePlaybackState(youtube.playbackState)
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        if (state == null) return

        val playing = state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING
        val position = state.position.coerceAtLeast(0L)
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f

        val stateChanged = state.state != lastReportedState
        val speedChanged = abs(speed - lastReportedSpeed) >= 0.05f

        val now = android.os.SystemClock.elapsedRealtime()
        val elapsedSinceLast = (now - lastStateChangeElapsed).coerceAtLeast(0L)
        val predicted = if (lastReportedState == PlaybackState.STATE_PLAYING) {
            lastReportedPosition + (elapsedSinceLast * lastReportedSpeed).toLong()
        } else {
            lastReportedPosition
        }

        val seekDetected = !stateChanged &&
            lastReportedPosition > 0L &&
            abs(position - predicted) > 2_000L

        youtubeActive.value = true
        isPlaying.value = playing
        playbackPositionMs.value = position
        playbackSpeed.value = speed

        val event = when {
            seekDetected -> "SEEK"
            !playing && stateChanged -> "PAUSE"
            playing && stateChanged -> "PLAY"
            speedChanged -> "SPEED"
            else -> "STATE"
        }

        statusText.value = when (event) {
            "SEEK" -> "YouTube seek · resync"
            "PAUSE" -> "YouTube paused"
            "PLAY" -> "YouTube playing"
            "SPEED" -> "YouTube " + speed + "x"
            else -> if (playing) "YouTube synced" else "YouTube ready"
        }

        sendCompanionEvent(event, playing, position, speed)

        lastReportedState = state.state
        lastReportedPosition = position
        lastReportedSpeed = speed
        lastStateChangeElapsed = now
    }

    private fun sendCompanionEvent(
        event: String,
        playing: Boolean,
        position: Long,
        speed: Float
    ) {
        if (!AudioDubbingForegroundService.isRunning.value) return
        val intent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = AudioDubbingForegroundService.ACTION_COMPANION_STATE
            putExtra(AudioDubbingForegroundService.EXTRA_COMPANION_EVENT, event)
            putExtra(AudioDubbingForegroundService.EXTRA_COMPANION_PLAYING, playing)
            putExtra(AudioDubbingForegroundService.EXTRA_COMPANION_POSITION_MS, position)
            putExtra(AudioDubbingForegroundService.EXTRA_COMPANION_SPEED, speed)
        }
        try {
            startService(intent)
        } catch (_: Throwable) {
        }
    }
}
