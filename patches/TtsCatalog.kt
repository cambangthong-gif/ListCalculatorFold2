package com.alad.app.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object TtsCatalog {
    data class EngineItem(
        val packageName: String,
        val label: String
    )

    data class VoiceItem(
        val name: String,
        val label: String,
        val languageTag: String,
        val networkRequired: Boolean
    )

    fun loadEngines(
        context: Context,
        onLoaded: (List<EngineItem>) -> Unit
    ): TextToSpeech {
        var probe: TextToSpeech? = null
        probe = TextToSpeech(context.applicationContext) { status ->
            val tts = probe
            if (status == TextToSpeech.SUCCESS && tts != null) {
                val items = tts.engines
                    .map { info ->
                        EngineItem(
                            packageName = info.name,
                            label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: info.name
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
                onLoaded(items)
            } else {
                onLoaded(emptyList())
            }
            tts?.shutdown()
        }
        return probe
    }

    fun loadVoices(
        context: Context,
        enginePackage: String,
        preferredLanguageTag: String = "vi-VN",
        onLoaded: (List<VoiceItem>) -> Unit
    ): TextToSpeech {
        var probe: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            val tts = probe
            if (status == TextToSpeech.SUCCESS && tts != null) {
                val preferredLanguage = Locale.forLanguageTag(preferredLanguageTag).language
                val all = tts.voices.orEmpty().map { voice ->
                    val tag = voice.locale?.toLanguageTag().orEmpty()
                    val network = voice.isNetworkConnectionRequired
                    val mode = if (network) "online" else "offline"
                    val localeLabel = if (tag.isBlank()) "?" else tag
                    VoiceItem(
                        name = voice.name,
                        label = "${voice.name} · $localeLabel · $mode",
                        languageTag = tag,
                        networkRequired = network
                    )
                }
                val sorted = all.sortedWith(
                    compareBy<VoiceItem> {
                        if (Locale.forLanguageTag(it.languageTag).language == preferredLanguage) 0 else 1
                    }.thenBy { it.networkRequired }
                        .thenBy { it.label.lowercase(Locale.getDefault()) }
                )
                onLoaded(sorted)
            } else {
                onLoaded(emptyList())
            }
            tts?.shutdown()
        }
        probe = if (enginePackage.isBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, enginePackage)
        }
        return probe
    }
}
