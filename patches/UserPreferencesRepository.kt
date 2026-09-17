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

        val DUB_MODE = stringPreferencesKey("dub_mode")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val MANUAL_SYNC_MS = intPreferencesKey("manual_sync_ms")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val CATCH_UP = booleanPreferencesKey("catch_up")
        val LOW_LATENCY = booleanPreferencesKey("low_latency")
        val MAX_CATCH_UP_SPEED = floatPreferencesKey("max_catch_up_speed")
    }

    val wsUrlFlow: Flow<String> = context.dataStore.data.map {
        it[WS_URL] ?: "ws://192.168.1.100:8000/ws/dub"
    }
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val sourceLangFlow: Flow<String> = context.dataStore.data.map { it[SOURCE_LANG] ?: "en" }
    val targetLangFlow: Flow<String> = context.dataStore.data.map { it[TARGET_LANG] ?: "fa" }
    val volumeRatioFlow: Flow<Float> = context.dataStore.data.map { it[VOLUME_RATIO] ?: 1.0f }

    val dubModeFlow: Flow<String> = context.dataStore.data.map { it[DUB_MODE] ?: "auto_duck" }
    val voiceNameFlow: Flow<String> = context.dataStore.data.map { it[VOICE_NAME] ?: "Kore" }
    val manualSyncMsFlow: Flow<Int> = context.dataStore.data.map { it[MANUAL_SYNC_MS] ?: 0 }
    val autoSyncFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_SYNC] ?: true }
    val catchUpFlow: Flow<Boolean> = context.dataStore.data.map { it[CATCH_UP] ?: true }
    val lowLatencyFlow: Flow<Boolean> = context.dataStore.data.map { it[LOW_LATENCY] ?: true }
    val maxCatchUpSpeedFlow: Flow<Float> = context.dataStore.data.map {
        (it[MAX_CATCH_UP_SPEED] ?: 1.15f).coerceIn(1.0f, 1.30f)
    }

    suspend fun updateWsUrl(url: String) {
        context.dataStore.edit { it[WS_URL] = url }
    }

    suspend fun updateApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key }
    }

    suspend fun updateSourceLang(lang: String) {
        context.dataStore.edit { it[SOURCE_LANG] = lang }
    }

    suspend fun updateTargetLang(lang: String) {
        context.dataStore.edit { it[TARGET_LANG] = lang }
    }

    suspend fun updateVolumeRatio(ratio: Float) {
        context.dataStore.edit { it[VOLUME_RATIO] = ratio.coerceIn(0f, 1f) }
    }

    suspend fun updateDubMode(mode: String) {
        context.dataStore.edit { it[DUB_MODE] = mode }
    }

    suspend fun updateVoiceName(voice: String) {
        context.dataStore.edit { it[VOICE_NAME] = voice }
    }

    suspend fun updateManualSyncMs(delayMs: Int) {
        context.dataStore.edit { it[MANUAL_SYNC_MS] = delayMs.coerceIn(-2000, 5000) }
    }

    suspend fun updateAutoSync(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_SYNC] = enabled }
    }

    suspend fun updateCatchUp(enabled: Boolean) {
        context.dataStore.edit { it[CATCH_UP] = enabled }
    }

    suspend fun updateLowLatency(enabled: Boolean) {
        context.dataStore.edit { it[LOW_LATENCY] = enabled }
    }

    suspend fun updateMaxCatchUpSpeed(speed: Float) {
        context.dataStore.edit { it[MAX_CATCH_UP_SPEED] = speed.coerceIn(1.0f, 1.30f) }
    }
}
