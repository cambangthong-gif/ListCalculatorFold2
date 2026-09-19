package com.alad.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alad_settings")

class UserPreferencesRepository(private val context: Context) {
    companion object {
        val WS_URL = stringPreferencesKey("ws_url")
        val API_KEY = stringPreferencesKey("api_key")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val VOLUME_RATIO = floatPreferencesKey("volume_ratio")
        val ORIGINAL_VOLUME = floatPreferencesKey("original_volume")
        val DUB_MODE = stringPreferencesKey("dub_mode")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val VOICE_SOURCE = stringPreferencesKey("voice_source")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val MANUAL_SYNC_MS = intPreferencesKey("manual_sync_ms")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val CATCH_UP = booleanPreferencesKey("catch_up")
        val LOW_LATENCY = booleanPreferencesKey("low_latency")
        val MAX_CATCH_UP_SPEED = floatPreferencesKey("max_catch_up_speed")
        val GEMINI_SYNC_MODE = stringPreferencesKey("gemini_sync_mode")
        val GEMINI_MICRO_CATCH_UP = booleanPreferencesKey("gemini_micro_catch_up")
        val GEMINI_TAIL_FINISH = booleanPreferencesKey("gemini_tail_finish")
    }

    val wsUrlFlow: Flow<String> = context.dataStore.data.map { it[WS_URL] ?: "ws://192.168.1.100:8000/ws/dub" }
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val sourceLangFlow: Flow<String> = context.dataStore.data.map { it[SOURCE_LANG] ?: "en" }
    val targetLangFlow: Flow<String> = context.dataStore.data.map { it[TARGET_LANG] ?: "vi" }
    val volumeRatioFlow: Flow<Float> = context.dataStore.data.map { (it[VOLUME_RATIO] ?: 1.0f).coerceIn(0f, 1f) }
    val originalVolumeFlow: Flow<Float> = context.dataStore.data.map { (it[ORIGINAL_VOLUME] ?: 0.35f).coerceIn(0f, 1f) }
    val dubModeFlow: Flow<String> = context.dataStore.data.map { it[DUB_MODE] ?: "auto_duck" }
    val voiceNameFlow: Flow<String> = context.dataStore.data.map { it[VOICE_NAME] ?: "Kore" }
    val voiceSourceFlow: Flow<String> = context.dataStore.data.map { it[VOICE_SOURCE] ?: "gemini" }
    val ttsEnginePackageFlow: Flow<String> = context.dataStore.data.map { it[TTS_ENGINE_PACKAGE] ?: "" }
    val ttsVoiceNameFlow: Flow<String> = context.dataStore.data.map { it[TTS_VOICE_NAME] ?: "" }
    val ttsRateFlow: Flow<Float> = context.dataStore.data.map { (it[TTS_RATE] ?: 1.0f).coerceIn(0.70f, 1.50f) }
    val manualSyncMsFlow: Flow<Int> = context.dataStore.data.map { it[MANUAL_SYNC_MS] ?: 0 }
    val autoSyncFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_SYNC] ?: true }
    val catchUpFlow: Flow<Boolean> = context.dataStore.data.map { it[CATCH_UP] ?: true }
    val lowLatencyFlow: Flow<Boolean> = context.dataStore.data.map { it[LOW_LATENCY] ?: true }
    val maxCatchUpSpeedFlow: Flow<Float> = context.dataStore.data.map { (it[MAX_CATCH_UP_SPEED] ?: 1.15f).coerceIn(1.0f, 1.30f) }
    val geminiSyncModeFlow: Flow<String> = context.dataStore.data.map {
        it[GEMINI_SYNC_MODE]?.takeIf { v -> v in setOf("stable", "ultra_fast", "hybrid_fast", "balanced") } ?: "balanced"
    }
    val geminiMicroCatchUpFlow: Flow<Boolean> = context.dataStore.data.map { it[GEMINI_MICRO_CATCH_UP] ?: true }
    val geminiTailFinishFlow: Flow<Boolean> = context.dataStore.data.map { it[GEMINI_TAIL_FINISH] ?: true }

    suspend fun updateWsUrl(v: String) { context.dataStore.edit { it[WS_URL] = v } }
    suspend fun updateApiKey(v: String) { context.dataStore.edit { it[API_KEY] = v } }
    suspend fun updateSourceLang(v: String) { context.dataStore.edit { it[SOURCE_LANG] = v } }
    suspend fun updateTargetLang(v: String) { context.dataStore.edit { it[TARGET_LANG] = v } }
    suspend fun updateVolumeRatio(v: Float) { context.dataStore.edit { it[VOLUME_RATIO] = v.coerceIn(0f, 1f) } }
    suspend fun updateOriginalVolume(v: Float) { context.dataStore.edit { it[ORIGINAL_VOLUME] = v.coerceIn(0f, 1f) } }
    suspend fun updateDubMode(v: String) { context.dataStore.edit { it[DUB_MODE] = v } }
    suspend fun updateVoiceName(v: String) { context.dataStore.edit { it[VOICE_NAME] = v } }
    suspend fun updateVoiceSource(v: String) { context.dataStore.edit { it[VOICE_SOURCE] = v } }
    suspend fun updateTtsEnginePackage(v: String) { context.dataStore.edit { it[TTS_ENGINE_PACKAGE] = v } }
    suspend fun updateTtsVoiceName(v: String) { context.dataStore.edit { it[TTS_VOICE_NAME] = v } }
    suspend fun updateTtsRate(v: Float) { context.dataStore.edit { it[TTS_RATE] = v.coerceIn(0.70f, 1.50f) } }
    suspend fun updateManualSyncMs(v: Int) { context.dataStore.edit { it[MANUAL_SYNC_MS] = v.coerceIn(-2000, 5000) } }
    suspend fun updateAutoSync(v: Boolean) { context.dataStore.edit { it[AUTO_SYNC] = v } }
    suspend fun updateCatchUp(v: Boolean) { context.dataStore.edit { it[CATCH_UP] = v } }
    suspend fun updateLowLatency(v: Boolean) { context.dataStore.edit { it[LOW_LATENCY] = v } }
    suspend fun updateMaxCatchUpSpeed(v: Float) { context.dataStore.edit { it[MAX_CATCH_UP_SPEED] = v.coerceIn(1.0f, 1.30f) } }
    suspend fun updateGeminiSyncMode(v: String) {
        val safe = v.takeIf { it in setOf("stable", "hybrid_fast", "balanced") } ?: "balanced"
        context.dataStore.edit { it[GEMINI_SYNC_MODE] = safe }
    }
    suspend fun updateGeminiMicroCatchUp(v: Boolean) { context.dataStore.edit { it[GEMINI_MICRO_CATCH_UP] = v } }
    suspend fun updateGeminiTailFinish(v: Boolean) { context.dataStore.edit { it[GEMINI_TAIL_FINISH] = v } }
}
