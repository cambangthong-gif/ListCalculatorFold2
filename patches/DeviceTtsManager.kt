package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class DeviceTtsManager(
    private val context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var stopped = false
    private val queue = ArrayDeque<String>()

    private var targetLanguageTag: String = "vi-VN"
    private var baseRate: Float = 1.0f
    private var volume: Float = 1.0f
    private var catchUpEnabled: Boolean = true
    private var lowLatencyEnabled: Boolean = true
    private var maxCatchUpSpeed: Float = 1.15f
    private var speaking = false

    fun start() {
        stopped = false
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS && !stopped) {
                ready = true
                configureEngine()
                speakNextIfNeeded()
            }
        }
    }

    @Synchronized
    fun configure(
        languageTag: String,
        speechRate: Float,
        outputVolume: Float,
        catchUp: Boolean,
        lowLatency: Boolean,
        maxSpeed: Float
    ) {
        targetLanguageTag = normalizeLanguageTag(languageTag)
        baseRate = speechRate.coerceIn(0.70f, 1.50f)
        volume = outputVolume.coerceIn(0f, 1f)
        catchUpEnabled = catchUp
        lowLatencyEnabled = lowLatency
        maxCatchUpSpeed = maxSpeed.coerceIn(1.0f, 1.35f)
        if (ready) configureEngine()
    }

    private fun normalizeLanguageTag(tag: String): String {
        if (tag.isBlank()) return "vi-VN"
        return when (tag.lowercase(Locale.ROOT)) {
            "vi" -> "vi-VN"
            "en" -> "en-US"
            "ja" -> "ja-JP"
            "ko" -> "ko-KR"
            "zh" -> "zh-CN"
            else -> tag
        }
    }

    private fun configureEngine() {
        val engine = tts ?: return
        engine.language = Locale.forLanguageTag(targetLanguageTag)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
                onSpeakingChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                synchronized(this@DeviceTtsManager) {
                    speaking = false
                    onSpeakingChanged(false)
                    speakNextIfNeeded()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                synchronized(this@DeviceTtsManager) {
                    speaking = false
                    onSpeakingChanged(false)
                    speakNextIfNeeded()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                synchronized(this@DeviceTtsManager) {
                    speaking = false
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

        val chunks = splitForSpeech(cleaned)
        chunks.forEach { chunk ->
            if (chunk.isNotBlank()) queue.addLast(chunk)
        }

        // Live dubbing must stay near the video. If TTS falls far behind, keep the
        // newest phrases instead of reading a long stale backlog.
        val maxQueued = if (lowLatencyEnabled) 3 else 6
        if (catchUpEnabled) {
            while (queue.size > maxQueued) queue.removeFirst()
        }

        speakNextIfNeeded()
    }

    private fun splitForSpeech(text: String): List<String> {
        if (text.length <= 110) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > 110) {
            val window = remaining.take(110)
            val cut = listOf(
                window.lastIndexOf('.'),
                window.lastIndexOf('!'),
                window.lastIndexOf('?'),
                window.lastIndexOf(','),
                window.lastIndexOf(';'),
                window.lastIndexOf(' ')
            ).maxOrNull()?.takeIf { it >= 45 } ?: 110
            result += remaining.substring(0, cut + if (cut < remaining.length && cut < 110) 1 else 0).trim()
            remaining = remaining.substring((cut + 1).coerceAtMost(remaining.length)).trim()
        }
        if (remaining.isNotBlank()) result += remaining
        return result
    }

    @Synchronized
    private fun speakNextIfNeeded() {
        if (!ready || stopped || speaking || queue.isEmpty()) return
        val engine = tts ?: return
        val text = queue.removeFirst()

        val backlogFactor = if (catchUpEnabled) {
            when {
                queue.size >= 3 -> maxCatchUpSpeed
                queue.size >= 1 -> (1.08f).coerceAtMost(maxCatchUpSpeed)
                else -> 1.0f
            }
        } else 1.0f

        engine.setSpeechRate((baseRate * backlogFactor).coerceIn(0.70f, 1.60f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val utteranceId = "alad_tts_${UUID.randomUUID()}"
        speaking = true
        onSpeakingChanged(true)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            speaking = false
            onSpeakingChanged(false)
        }
    }

    @Synchronized
    fun clearBacklog() {
        queue.clear()
        tts?.stop()
        speaking = false
        onSpeakingChanged(false)
    }

    @Synchronized
    fun stop() {
        stopped = true
        ready = false
        queue.clear()
        speaking = false
        onSpeakingChanged(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
