package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class DeviceTtsManager(
    private val context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) {
    private data class TtsItem(val text: String, val queuedAtMs: Long)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var stopped = false
    private val queue = ArrayDeque<TtsItem>()

    private var targetLanguageTag = "vi-VN"
    private var selectedEnginePackage = ""
    private var selectedVoiceName = ""
    private var baseRate = 1.0f
    private var volume = 1.0f
    private var originalVolume = 0.35f
    private var dubMode = "auto_duck"
    private var catchUpEnabled = true
    private var lowLatencyEnabled = true
    private var maxCatchUpSpeed = 1.15f
    private var speaking = false
    @Volatile private var externallyPaused = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentFocusGain: Int? = null

    fun start() {
        stopped = false
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createEngine()
    }

    @Synchronized
    private fun createEngine() {
        ready = false
        tts?.stop(); tts?.shutdown(); tts = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS && !stopped) {
                ready = true
                configureEngine()
                speakNextIfNeeded()
            }
        }
        tts = if (selectedEnginePackage.isBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, selectedEnginePackage)
        }
    }

    @Synchronized
    fun configure(
        languageTag: String,
        enginePackage: String,
        voiceName: String,
        speechRate: Float,
        outputVolume: Float,
        originalLevel: Float,
        mode: String,
        catchUp: Boolean,
        lowLatency: Boolean,
        maxSpeed: Float
    ) {
        val newEngine = enginePackage.trim()
        val engineChanged = selectedEnginePackage != newEngine
        targetLanguageTag = normalizeLanguageTag(languageTag)
        selectedEnginePackage = newEngine
        selectedVoiceName = voiceName.trim()
        baseRate = speechRate.coerceIn(0.70f, 1.50f)
        volume = outputVolume.coerceIn(0f, 1f)
        originalVolume = originalLevel.coerceIn(0f, 1f)
        dubMode = mode
        catchUpEnabled = catchUp
        lowLatencyEnabled = lowLatency
        maxCatchUpSpeed = maxSpeed.coerceIn(1.0f, 1.35f)
        if (ready && engineChanged && !stopped) createEngine() else if (ready) configureEngine()
    }

    private fun normalizeLanguageTag(tag: String): String = when (tag.lowercase(Locale.ROOT)) {
        "", "vi" -> "vi-VN"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "zh" -> "zh-CN"
        else -> tag
    }

    private fun configureEngine() {
        val engine = tts ?: return
        engine.language = Locale.forLanguageTag(targetLanguageTag)
        if (selectedVoiceName.isNotBlank()) {
            engine.voices?.firstOrNull { it.name == selectedVoiceName }?.let { engine.voice = it }
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
                acquireFocusForSpeech()
                onSpeakingChanged(true)
            }
            override fun onDone(utteranceId: String?) {
                synchronized(this@DeviceTtsManager) {
                    speaking = false
                    if (dubMode != "voice_over") releaseFocus()
                    onSpeakingChanged(false)
                    speakNextIfNeeded()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishError()
            override fun onError(utteranceId: String?, errorCode: Int) = finishError()
            private fun finishError() {
                synchronized(this@DeviceTtsManager) {
                    speaking = false
                    if (dubMode != "voice_over") releaseFocus()
                    onSpeakingChanged(false)
                    speakNextIfNeeded()
                }
            }
        })
    }

    @Synchronized
    fun enqueueText(text: String) {
        if (stopped) return
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        splitForSpeech(cleaned).forEach {
            if (it.isNotBlank()) queue.addLast(TtsItem(it, now))
        }

        // Keep the live edge. Old TTS is worse than skipping a stale phrase.
        val maxQueued = if (lowLatencyEnabled) 4 else 7
        if (catchUpEnabled) while (queue.size > maxQueued) queue.removeFirst()
        trimStaleQueue()
        speakNextIfNeeded()
    }

    private fun splitForSpeech(text: String): List<String> {
        if (text.length <= 72) return listOf(text)
        val out = mutableListOf<String>()
        var remaining = text
        while (remaining.length > 72) {
            val window = remaining.take(72)
            val cuts = listOf(
                window.lastIndexOf('.'), window.lastIndexOf('!'), window.lastIndexOf('?'),
                window.lastIndexOf('…'), window.lastIndexOf(','), window.lastIndexOf(';'),
                window.lastIndexOf(':'), window.lastIndexOf(' ')
            )
            val cut = cuts.maxOrNull()?.takeIf { it >= 24 } ?: 72
            out += remaining.substring(0, (cut + 1).coerceAtMost(remaining.length)).trim()
            remaining = remaining.substring((cut + 1).coerceAtMost(remaining.length)).trim()
        }
        if (remaining.isNotBlank()) out += remaining
        return out
    }

    @Synchronized
    private fun trimStaleQueue() {
        if (!catchUpEnabled || queue.size <= 1) return
        val now = SystemClock.elapsedRealtime()
        val staleLimit = if (lowLatencyEnabled) 1_600L else 2_600L
        while (queue.size > 1 && now - (queue.firstOrNull()?.queuedAtMs ?: now) > staleLimit) {
            queue.removeFirst()
        }
    }

    @Synchronized
    private fun speakNextIfNeeded() {
        if (!ready || stopped || externallyPaused || speaking || queue.isEmpty()) return
        trimStaleQueue()
        val engine = tts ?: return
        val item = queue.removeFirst()
        val ageMs = (SystemClock.elapsedRealtime() - item.queuedAtMs).coerceAtLeast(0L)

        val backlogFactor = if (catchUpEnabled) when {
            ageMs >= 1_400L -> maxCatchUpSpeed
            ageMs >= 850L -> 1.18f.coerceAtMost(maxCatchUpSpeed)
            ageMs >= 400L || queue.size >= 2 -> 1.10f.coerceAtMost(maxCatchUpSpeed)
            queue.isNotEmpty() -> 1.05f.coerceAtMost(maxCatchUpSpeed)
            else -> 1.0f
        } else 1.0f

        engine.setSpeechRate((baseRate * backlogFactor).coerceIn(0.70f, 1.65f))
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        speaking = true
        acquireFocusForSpeech()
        onSpeakingChanged(true)
        if (engine.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, "alad_tts_${UUID.randomUUID()}") == TextToSpeech.ERROR) {
            speaking = false
            releaseFocus()
            onSpeakingChanged(false)
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

    @Synchronized
    private fun acquireFocusForSpeech() {
        val desired = desiredFocusGain()
        if (desired == null) { releaseFocus(); return }
        if (currentFocusGain == desired && focusRequest != null) return
        releaseFocus()
        val manager = audioManager ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(desired)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        manager.requestAudioFocus(request)
        focusRequest = request
        currentFocusGain = desired
    }

    @Synchronized
    private fun releaseFocus() {
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) try { manager.abandonAudioFocusRequest(request) } catch (_: Throwable) {}
        focusRequest = null
        currentFocusGain = null
    }

    @Synchronized
    fun setExternalPaused(paused: Boolean) {
        externallyPaused = paused
        if (!paused) speakNextIfNeeded()
    }

    @Synchronized
    fun clearBacklog() {
        queue.clear(); tts?.stop(); speaking = false; releaseFocus(); onSpeakingChanged(false)
    }

    @Synchronized
    fun stop() {
        externallyPaused = false
        stopped = true; ready = false; queue.clear(); speaking = false; releaseFocus(); onSpeakingChanged(false)
        tts?.stop(); tts?.shutdown(); tts = null; audioManager = null
    }
}
