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
        val mediaActive = MutableStateFlow(false)
        val activePackage = MutableStateFlow("")
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
            mediaActive.value = false
            isPlaying.value = false
            statusText.value = "Media session ended"
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
        mediaActive.value = false
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
        val candidates = controllers.filter { it.packageName != packageName }
        val selected = candidates.firstOrNull {
            val st = it.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        } ?: candidates.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        } ?: candidates.firstOrNull()

        if (selected === activeController) {
            handlePlaybackState(selected?.playbackState)
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = selected

        if (selected == null) {
            mediaActive.value = false
            activePackage.value = ""
            isPlaying.value = false
            statusText.value = "Waiting for media app"
            return
        }

        selected.registerCallback(controllerCallback)
        mediaActive.value = true
        activePackage.value = selected.packageName
        statusText.value = "Media detected · " + selected.packageName
        handlePlaybackState(selected.playbackState)
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

        mediaActive.value = true
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
            "SEEK" -> "Media seek · resync"
            "PAUSE" -> "Media paused"
            "PLAY" -> "Media playing"
            "SPEED" -> "Media " + speed + "x"
            else -> if (playing) "Media synced" else "Media ready"
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
