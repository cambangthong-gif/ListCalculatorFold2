package com.alad.app.core.network

import android.os.Handler
import android.os.Looper
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
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Keep only a short slice of source audio while reconnecting. Replaying a long
        // stale buffer makes the translated voice fall further behind the video.
        private const val MAX_PENDING_AUDIO_CHUNKS = 10

        // Fast reconnect profile for live dubbing: 0.25s, 0.5s, 1s, 2s, then 4s max.
        private const val FIRST_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 4_000L
        private const val GO_AWAY_RECONNECT_DELAY_MS = 100L
    }

    private val reconnectHandler = Handler(Looper.getMainLooper())

    @Volatile private var isSetupComplete = false
    @Volatile private var manualDisconnect = false
    @Volatile private var reconnectScheduled = false

    private var reconnectAttempt = 0
    private var socketGeneration = 0L
    private var currentApiKey = ""
    private var currentTargetLang = ""
    private var currentVoiceName = "Kore"
    private var sessionHandle: String? = null
    private val pendingAudio = ArrayDeque<String>()

    @Synchronized
    fun connect(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        voiceName: String = "Kore"
    ) {
        currentApiKey = apiKey
        currentTargetLang = targetLang
        currentVoiceName = voiceName.ifBlank { "Kore" }
        manualDisconnect = false
        reconnectScheduled = false
        reconnectAttempt = 0
        sessionHandle = null
        isSetupComplete = false
        pendingAudio.clear()

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
                    resumeHandle = sessionHandle
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (!isCurrent(generation)) return

                try {
                    val json = JSONObject(text)

                    val resumeUpdate =
                        json.optJSONObject("sessionResumptionUpdate")
                            ?: json.optJSONObject("session_resumption_update")
                    if (resumeUpdate != null) {
                        val resumable = resumeUpdate.optBoolean("resumable", false)
                        val newHandle = resumeUpdate.optString("newHandle")
                            .ifBlank { resumeUpdate.optString("new_handle") }
                        if (resumable && newHandle.isNotBlank()) {
                            sessionHandle = newHandle
                        } else if (!resumable) {
                            sessionHandle = null
                        }
                        return
                    }

                    val goAway = json.optJSONObject("goAway") ?: json.optJSONObject("go_away")
                    if (goAway != null) {
                        isSetupComplete = false
                        onStatusChanged?.invoke("Server handoff · reconnecting")
                        scheduleReconnect("server goAway", GO_AWAY_RECONNECT_DELAY_MS)
                        return
                    }

                    val serverContent =
                        json.optJSONObject("serverContent")
                            ?: json.optJSONObject("server_content")

                    if (serverContent != null) {
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
                        flushPendingAudio()
                        return
                    }

                    if (json.has("error")) {
                        val errMessage = json.optJSONObject("error")
                            ?.optString("message", "Unknown error") ?: "Unknown error"
                        onStatusChanged?.invoke("Error: $errMessage")
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
                if (!manualDisconnect) scheduleReconnect("closed $code ${reason.take(80)}")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                isSetupComplete = false
                if (!manualDisconnect) scheduleReconnect(t.message ?: "network failure")
            }
        })
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == socketGeneration && !manualDisconnect

    private fun sendGeminiSetup(
        ws: WebSocket,
        targetLang: String,
        voiceName: String,
        resumeHandle: String?
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
                put("outputAudioTranscription", JSONObject())
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

    private fun sendAudioNow(base64Audio: String): Boolean {
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
        pendingAudio.addLast(base64Audio)
        while (pendingAudio.size > MAX_PENDING_AUDIO_CHUNKS) pendingAudio.removeFirst()
    }

    private fun flushPendingAudio() {
        val snapshot = synchronized(this) {
            val copy = pendingAudio.toList()
            pendingAudio.clear()
            copy
        }
        for (i in snapshot.indices) {
            if (!sendAudioNow(snapshot[i])) {
                isSetupComplete = false
                synchronized(this) {
                    for (j in i until snapshot.size) pendingAudio.addLast(snapshot[j])
                    while (pendingAudio.size > MAX_PENDING_AUDIO_CHUNKS) pendingAudio.removeFirst()
                }
                scheduleReconnect("pending audio flush failed")
                return
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect(reason: String, forcedDelayMs: Long? = null) {
        if (manualDisconnect || reconnectScheduled || currentApiKey.isBlank()) return
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

    @Synchronized
    fun disconnect() {
        manualDisconnect = true
        isSetupComplete = false
        reconnectScheduled = false
        reconnectAttempt = 0
        sessionHandle = null
        pendingAudio.clear()
        reconnectHandler.removeCallbacksAndMessages(null)
        socketGeneration++
        webSocket?.close(1000, "User requested stop")
        webSocket = null
        onStatusChanged?.invoke("Disconnected")
    }
}
