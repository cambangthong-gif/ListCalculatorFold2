package com.alad.app.core.network

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class ALADWebSocketManager(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    var onBinaryMessageReceived: ((ByteArray) -> Unit)? = null
    var onOutputTranscription: ((String) -> Unit)? = null
    var onInputTranscription: ((String, Boolean) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onGenerationComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Keep only the recent live edge during a reconnect/handoff.
        private const val MAX_PENDING_AUDIO_CHUNKS = 56
        private const val MAX_PENDING_AUDIO_AGE_MS = 1_500L

        // Fast reconnect profile for unexpected failures.
        private const val FIRST_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 4_000L

        // Gemini Live periodically rolls the WebSocket. Keep the old connection alive
        // until shortly before its advertised deadline, then resume the same session.
        private const val GO_AWAY_SAFETY_MARGIN_MS = 750L
        private const val MIN_GO_AWAY_SWITCH_DELAY_MS = 150L
        private const val DEFAULT_GO_AWAY_SWITCH_DELAY_MS = 1_000L
    }

    private data class PendingAudio(
        val base64: String,
        val enqueuedAtMs: Long
    )

    private val reconnectHandler = Handler(Looper.getMainLooper())

    @Volatile private var isSetupComplete = false
    @Volatile private var manualDisconnect = false
    @Volatile private var reconnectScheduled = false
    @Volatile private var lastServerMessageMs = 0L
    @Volatile private var lastAudioSendMs = 0L

    private var reconnectAttempt = 0
    private var socketGeneration = 0L
    private var currentApiKey = ""
    private var currentTargetLang = ""
    private var currentVoiceName = "Kore"
    private var currentEnableTranscription = true
    private var currentVadSilenceMs = 550
    private var currentActivityHandling = "NO_INTERRUPTION"
    private var currentClientActivityDetection = false
    @Volatile private var desiredClientActivityActive = false
    @Volatile private var clientActivityDirty = false
    private var sessionHandle: String? = null
    private val pendingAudio = ArrayDeque<PendingAudio>()
    private var goAwayRunnable: Runnable? = null

    @Synchronized
    fun connect(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        voiceName: String = "Kore",
        enableTranscription: Boolean = true,
        vadSilenceMs: Int = 550,
        activityHandling: String = "NO_INTERRUPTION",
        clientActivityDetection: Boolean = false
    ) {
        currentApiKey = apiKey
        currentTargetLang = targetLang
        currentVoiceName = voiceName.ifBlank { "Kore" }
        currentEnableTranscription = enableTranscription
        currentVadSilenceMs = vadSilenceMs.coerceIn(300, 900)
        currentActivityHandling =
            if (activityHandling == "START_OF_ACTIVITY_INTERRUPTS") {
                "START_OF_ACTIVITY_INTERRUPTS"
            } else {
                "NO_INTERRUPTION"
            }
        currentClientActivityDetection = clientActivityDetection
        desiredClientActivityActive = false
        clientActivityDirty = false
        manualDisconnect = false
        reconnectScheduled = false
        reconnectAttempt = 0
        sessionHandle = null
        isSetupComplete = false
        pendingAudio.clear()

        cancelGoAwayRolloverLocked()
        reconnectHandler.removeCallbacksAndMessages(null)
        socketGeneration++
        webSocket?.cancel()
        webSocket = null
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (manualDisconnect || currentApiKey.isBlank()) return

        isSetupComplete = false
        val generation = ++socketGeneration
        val finalUrl = "$GEMINI_WS_URL?key=$currentApiKey"
        val request = Request.Builder().url(finalUrl).build()
        val tryingResume = !sessionHandle.isNullOrBlank()

        onStatusChanged?.invoke(
            if (reconnectAttempt == 0) "Connecting"
            else "Fast reconnect (${reconnectAttempt})"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!isCurrent(generation)) return
                onStatusChanged?.invoke(
                    if (sessionHandle.isNullOrBlank()) "Connected"
                    else "Connected - resuming session"
                )
                sendGeminiSetup(
                    ws = ws,
                    targetLang = currentTargetLang,
                    voiceName = currentVoiceName,
                    resumeHandle = sessionHandle,
                    enableTranscription = currentEnableTranscription,
                    vadSilenceMs = currentVadSilenceMs,
                    activityHandling = currentActivityHandling,
                    clientActivityDetection = currentClientActivityDetection
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                lastServerMessageMs = SystemClock.elapsedRealtime()

                try {
                    val json = JSONObject(text)

                    val resumeUpdate =
                        json.optJSONObject("sessionResumptionUpdate")
                            ?: json.optJSONObject("session_resumption_update")
                    if (resumeUpdate != null) {
                        val resumable = resumeUpdate.optBoolean("resumable", false)
                        val newHandle = resumeUpdate.optString("newHandle")
                            .ifBlank { resumeUpdate.optString("new_handle") }
                            .ifBlank { resumeUpdate.optString("token") }
                        if (resumable && newHandle.isNotBlank()) {
                            sessionHandle = newHandle
                        } else if (!resumable) {
                            sessionHandle = null
                        }
                        return
                    }

                    val goAway = json.optJSONObject("goAway") ?: json.optJSONObject("go_away")
                    if (goAway != null) {
                        // GoAway is advance notice. Do not kill a healthy socket immediately:
                        // keep streaming until shortly before timeLeft expires, then resume.
                        val rawTimeLeft = when {
                            goAway.has("timeLeft") -> goAway.opt("timeLeft")
                            goAway.has("time_left") -> goAway.opt("time_left")
                            else -> null
                        }
                        val timeLeftMs = parseDurationMs(rawTimeLeft)
                        val switchDelayMs = if (timeLeftMs != null) {
                            (timeLeftMs - GO_AWAY_SAFETY_MARGIN_MS)
                                .coerceAtLeast(MIN_GO_AWAY_SWITCH_DELAY_MS)
                        } else {
                            DEFAULT_GO_AWAY_SWITCH_DELAY_MS
                        }
                        onStatusChanged?.invoke("Seamless handoff in " + switchDelayMs + "ms")
                        scheduleGoAwayRollover(switchDelayMs)
                        return
                    }

                    val serverContent =
                        json.optJSONObject("serverContent")
                            ?: json.optJSONObject("server_content")

                    if (serverContent != null) {
                        val interimInput =
                            serverContent.optJSONObject("interimInputTranscription")
                                ?: serverContent.optJSONObject("interim_input_transcription")
                        val interimText = interimInput?.optString("text").orEmpty()
                        if (interimText.isNotBlank()) {
                            onInputTranscription?.invoke(interimText, false)
                        }

                        val finalInput =
                            serverContent.optJSONObject("inputTranscription")
                                ?: serverContent.optJSONObject("input_transcription")
                        val finalInputText = finalInput?.optString("text").orEmpty()
                        if (finalInputText.isNotBlank()) {
                            onInputTranscription?.invoke(finalInputText, true)
                        }

                        val outputTranscription =
                            serverContent.optJSONObject("outputTranscription")
                                ?: serverContent.optJSONObject("output_transcription")
                        val transcriptText = outputTranscription?.optString("text").orEmpty()
                        if (transcriptText.isNotBlank()) {
                            onOutputTranscription?.invoke(transcriptText)
                        }

                        val modelTurn =
                            serverContent.optJSONObject("modelTurn")
                                ?: serverContent.optJSONObject("model_turn")
                        val parts = modelTurn?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val inlineData =
                                    part.optJSONObject("inlineData")
                                        ?: part.optJSONObject("inline_data")
                                val base64Data = inlineData?.optString("data").orEmpty()
                                if (base64Data.isNotBlank()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    onBinaryMessageReceived?.invoke(audioBytes)
                                }
                            }
                        }

                        if (serverContent.optBoolean("interrupted", false)) {
                            onInterrupted?.invoke()
                        }
                        if (
                            serverContent.optBoolean("generationComplete", false) ||
                            serverContent.optBoolean("generation_complete", false)
                        ) {
                            onGenerationComplete?.invoke()
                        }
                        if (
                            serverContent.optBoolean("turnComplete", false) ||
                            serverContent.optBoolean("turn_complete", false)
                        ) {
                            onTurnComplete?.invoke()
                        }
                        return
                    }

                    if (json.has("setupComplete") || json.has("setup_complete")) {
                        isSetupComplete = true
                        reconnectAttempt = 0
                        onStatusChanged?.invoke("Gemini Ready · $currentVoiceName")
                        flushClientActivityState()
                        flushPendingAudio()
                        return
                    }

                    if (json.has("error")) {
                        val err = json.optJSONObject("error")
                        val errMessage = err?.optString("message", "Unknown error") ?: "Unknown error"
                        val code = err?.optInt("code", 0) ?: 0

                        when {
                            code == 401 || code == 403 ||
                                errMessage.contains("API key", ignoreCase = true) &&
                                errMessage.contains("invalid", ignoreCase = true) -> {
                                onStatusChanged?.invoke("Fatal auth: $errMessage")
                                manualDisconnect = true
                                isSetupComplete = false
                                try { webSocket?.cancel() } catch (_: Throwable) {}
                            }
                            code == 429 -> {
                                onStatusChanged?.invoke("Rate limited · retrying")
                                scheduleReconnect("rate limited", 4_000L)
                            }
                            else -> {
                                onStatusChanged?.invoke("Error: $errMessage")
                                forceReconnect(
                                    resetSession = tryingResume,
                                    reason = "server error"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Gemini message", e)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                onMessage(ws, bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                isSetupComplete = false
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                isSetupComplete = false
                if (!manualDisconnect) {
                    synchronized(this@ALADWebSocketManager) { cancelGoAwayRolloverLocked() }
                    scheduleReconnect("closed $code ${reason.take(80)}", FIRST_RECONNECT_DELAY_MS)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                isSetupComplete = false
                if (!manualDisconnect) {
                    synchronized(this@ALADWebSocketManager) { cancelGoAwayRolloverLocked() }
                    scheduleReconnect(t.message ?: "network failure", FIRST_RECONNECT_DELAY_MS)
                }
            }
        })
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == socketGeneration && !manualDisconnect

    private fun sendGeminiSetup(
        ws: WebSocket,
        targetLang: String,
        voiceName: String,
        resumeHandle: String?,
        enableTranscription: Boolean,
        vadSilenceMs: Int,
        activityHandling: String,
        clientActivityDetection: Boolean
    ) {
        val targetLangCode = targetLang.split("-")[0]

        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLangCode)
                        // Do not parrot audio that is already in the target language.
                        // For Vietnamese target, Vietnamese source segments stay silent.
                        put("echoTargetLanguage", false)
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
                put("realtimeInputConfig", JSONObject().apply {
                    put("activityHandling", activityHandling)
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", clientActivityDetection)
                        if (!clientActivityDetection) {
                            put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                            put("prefixPaddingMs", 20)
                            put("silenceDurationMs", vadSilenceMs)
                        }
                    })
                })
                if (enableTranscription) {
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                }
                put("contextWindowCompression", JSONObject().apply {
                    put("slidingWindow", JSONObject())
                })
                put("sessionResumption", JSONObject().apply {
                    if (resumeHandle.isNullOrBlank()) put("handle", JSONObject.NULL)
                    else put("handle", resumeHandle)
                })
            })
        }

        if (!ws.send(setupPayload.toString())) {
            scheduleReconnect("failed to send setup")
        }
    }

    fun sendAudioData(pcmData: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        if (!isSetupComplete) {
            enqueuePending(base64Audio)
            return
        }
        if (!sendAudioNow(base64Audio)) {
            isSetupComplete = false
            enqueuePending(base64Audio)
            scheduleReconnect("audio send failed")
        }
    }

    fun sendActivityStart() {
        if (!currentClientActivityDetection) return
        desiredClientActivityActive = true
        clientActivityDirty = true
        if (isSetupComplete) flushClientActivityState()
    }

    fun sendActivityEnd() {
        if (!currentClientActivityDetection) return
        desiredClientActivityActive = false
        clientActivityDirty = true
        if (isSetupComplete) flushClientActivityState()
    }

    @Synchronized
    private fun flushClientActivityState() {
        if (
            !isSetupComplete ||
            !currentClientActivityDetection ||
            !clientActivityDirty
        ) return

        val active = desiredClientActivityActive
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put(
                    if (active) "activityStart" else "activityEnd",
                    JSONObject()
                )
            })
        }

        if (webSocket?.send(payload.toString()) == true) {
            clientActivityDirty = false
        } else {
            scheduleReconnect(
                if (active) "activity start failed" else "activity end failed"
            )
        }
    }

    fun sendAudioStreamEnd() {
        if (!isSetupComplete) return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audioStreamEnd", true)
            })
        }
        if (webSocket?.send(payload.toString()) != true) {
            scheduleReconnect("audio stream end failed")
        }
    }

    private fun sendAudioNow(base64Audio: String): Boolean {
        lastAudioSendMs = SystemClock.elapsedRealtime()
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }
        return webSocket?.send(inputPayload.toString()) == true
    }

    @Synchronized
    private fun enqueuePending(base64Audio: String) {
        val now = SystemClock.elapsedRealtime()
        pendingAudio.addLast(PendingAudio(base64Audio, now))
        trimPendingLocked(now)
    }

    @Synchronized
    private fun trimPendingLocked(now: Long = SystemClock.elapsedRealtime()) {
        while (
            pendingAudio.isNotEmpty() &&
            now - pendingAudio.first().enqueuedAtMs > MAX_PENDING_AUDIO_AGE_MS
        ) {
            pendingAudio.removeFirst()
        }
        while (pendingAudio.size > MAX_PENDING_AUDIO_CHUNKS) {
            pendingAudio.removeFirst()
        }
    }

    private fun flushPendingAudio() {
        val now = SystemClock.elapsedRealtime()
        val snapshot = synchronized(this) {
            trimPendingLocked(now)
            val copy = pendingAudio.toList()
            pendingAudio.clear()
            copy
        }
        for (i in snapshot.indices) {
            if (now - snapshot[i].enqueuedAtMs > MAX_PENDING_AUDIO_AGE_MS) continue
            if (!sendAudioNow(snapshot[i].base64)) {
                isSetupComplete = false
                synchronized(this) {
                    val retryNow = SystemClock.elapsedRealtime()
                    for (j in i until snapshot.size) {
                        if (retryNow - snapshot[j].enqueuedAtMs <= MAX_PENDING_AUDIO_AGE_MS) {
                            pendingAudio.addLast(snapshot[j])
                        }
                    }
                    trimPendingLocked(retryNow)
                }
                scheduleReconnect("pending audio flush failed")
                return
            }
        }
    }

    @Synchronized
    private fun scheduleGoAwayRollover(delayMs: Long) {
        if (manualDisconnect || currentApiKey.isBlank() || goAwayRunnable != null) return
        val safeDelay = delayMs.coerceAtLeast(MIN_GO_AWAY_SWITCH_DELAY_MS)
        val runnable = Runnable {
            synchronized(this) {
                goAwayRunnable = null
                if (manualDisconnect || currentApiKey.isBlank()) return@synchronized
                isSetupComplete = false
                reconnectAttempt = 1

                val old = webSocket
                socketGeneration++
                webSocket = null
                old?.cancel()
                openSocket()
            }
        }
        goAwayRunnable = runnable
        reconnectHandler.postDelayed(runnable, safeDelay)
    }

    @Synchronized
    private fun cancelGoAwayRolloverLocked() {
        goAwayRunnable?.let(reconnectHandler::removeCallbacks)
        goAwayRunnable = null
    }

    private fun parseDurationMs(value: Any?): Long? {
        return try {
            when (value) {
                is String -> {
                    val v = value.trim().lowercase()
                    when {
                        v.endsWith("ms") -> v.removeSuffix("ms").toDoubleOrNull()?.toLong()
                        v.endsWith("s") -> v.removeSuffix("s").toDoubleOrNull()
                            ?.let { (it * 1000.0).toLong() }
                        else -> v.toDoubleOrNull()?.toLong()
                    }
                }
                is JSONObject -> {
                    val seconds = value.optLong("seconds", 0L)
                    val nanos = value.optLong("nanos", 0L)
                    seconds * 1000L + nanos / 1_000_000L
                }
                is Number -> value.toLong()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    private fun scheduleReconnect(reason: String, forcedDelayMs: Long? = null) {
        if (manualDisconnect || reconnectScheduled || currentApiKey.isBlank()) return
        cancelGoAwayRolloverLocked()
        reconnectScheduled = true
        reconnectAttempt++

        val exponent = min(reconnectAttempt - 1, 4)
        val normalDelay = min(FIRST_RECONNECT_DELAY_MS shl exponent, MAX_RECONNECT_DELAY_MS)
        val delayMs = (forcedDelayMs ?: normalDelay).coerceAtLeast(50L)

        onStatusChanged?.invoke("Reconnecting in ${delayMs}ms")
        reconnectHandler.postDelayed({
            synchronized(this) {
                reconnectScheduled = false
                if (manualDisconnect) return@postDelayed
                isSetupComplete = false
                socketGeneration++
                webSocket?.cancel()
                webSocket = null
                openSocket()
            }
        }, delayMs)
    }

    fun isReady(): Boolean = isSetupComplete

    @Synchronized
    fun reconfigureRealtime(
        vadSilenceMs: Int,
        activityHandling: String,
        clientActivityDetection: Boolean = false
    ) {
        currentVadSilenceMs = vadSilenceMs.coerceIn(300, 900)
        currentActivityHandling =
            if (activityHandling == "START_OF_ACTIVITY_INTERRUPTS") {
                "START_OF_ACTIVITY_INTERRUPTS"
            } else {
                "NO_INTERRUPTION"
            }
        currentClientActivityDetection = clientActivityDetection
        desiredClientActivityActive = false
        clientActivityDirty = false
        forceReconnect(
            resetSession = true,
            reason = "realtime profile changed"
        )
    }

    fun serverSilenceMs(): Long {
        val last = lastServerMessageMs
        return if (last <= 0L) Long.MAX_VALUE
        else (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)
    }

    fun audioSendSilenceMs(): Long {
        val last = lastAudioSendMs
        return if (last <= 0L) Long.MAX_VALUE
        else (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)
    }

    @Synchronized
    fun forceReconnect(
        resetSession: Boolean = false,
        reason: String = "watchdog"
    ) {
        if (manualDisconnect || currentApiKey.isBlank()) return
        cancelGoAwayRolloverLocked()
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        isSetupComplete = false
        if (resetSession) sessionHandle = null

        val old = webSocket
        socketGeneration++
        webSocket = null
        try { old?.cancel() } catch (_: Throwable) {}

        reconnectAttempt = 1
        onStatusChanged?.invoke(
            if (resetSession) "Self-heal hard reconnect"
            else "Self-heal reconnect"
        )
        openSocket()
    }

    @Synchronized
    fun disconnect() {
        manualDisconnect = true
        isSetupComplete = false
        reconnectScheduled = false
        reconnectAttempt = 0
        sessionHandle = null
        pendingAudio.clear()
        desiredClientActivityActive = false
        clientActivityDirty = false
        lastServerMessageMs = 0L
        lastAudioSendMs = 0L
        cancelGoAwayRolloverLocked()
        reconnectHandler.removeCallbacksAndMessages(null)
        socketGeneration++
        webSocket?.close(1000, "User requested stop")
        webSocket = null
        onStatusChanged?.invoke("Disconnected")
    }
}
