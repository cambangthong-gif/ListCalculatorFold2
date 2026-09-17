package com.alad.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _volumeRatio = MutableStateFlow(1.0f)
    val volumeRatio: StateFlow<Float> = _volumeRatio.asStateFlow()

    private val _dubMode = MutableStateFlow("auto_duck")
    val dubMode: StateFlow<String> = _dubMode.asStateFlow()

    private val _voiceName = MutableStateFlow("Kore")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    private val _voiceSource = MutableStateFlow("gemini")
    val voiceSource: StateFlow<String> = _voiceSource.asStateFlow()

    private val _ttsRate = MutableStateFlow(1.0f)
    val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

    private val _manualSyncMs = MutableStateFlow(0)
    val manualSyncMs: StateFlow<Int> = _manualSyncMs.asStateFlow()

    private val _autoSync = MutableStateFlow(true)
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    private val _catchUp = MutableStateFlow(true)
    val catchUp: StateFlow<Boolean> = _catchUp.asStateFlow()

    private val _lowLatency = MutableStateFlow(true)
    val lowLatency: StateFlow<Boolean> = _lowLatency.asStateFlow()

    private val _maxCatchUpSpeed = MutableStateFlow(1.15f)
    val maxCatchUpSpeed: StateFlow<Float> = _maxCatchUpSpeed.asStateFlow()

    init {
        viewModelScope.launch {
            _apiKey.value = repository.apiKeyFlow.first()
            _volumeRatio.value = repository.volumeRatioFlow.first()
            _dubMode.value = repository.dubModeFlow.first()
            _voiceName.value = repository.voiceNameFlow.first()
            _voiceSource.value = repository.voiceSourceFlow.first()
            _ttsRate.value = repository.ttsRateFlow.first()
            _manualSyncMs.value = repository.manualSyncMsFlow.first()
            _autoSync.value = repository.autoSyncFlow.first()
            _catchUp.value = repository.catchUpFlow.first()
            _lowLatency.value = repository.lowLatencyFlow.first()
            _maxCatchUpSpeed.value = repository.maxCatchUpSpeedFlow.first()
        }
    }

    fun updateApiKey(value: String) { _apiKey.value = value }
    fun updateVolumeRatio(value: Float) { _volumeRatio.value = value.coerceIn(0f, 1f) }
    fun updateDubMode(value: String) { _dubMode.value = value }
    fun updateVoiceName(value: String) { _voiceName.value = value }
    fun updateVoiceSource(value: String) { _voiceSource.value = value }
    fun updateTtsRate(value: Float) { _ttsRate.value = value.coerceIn(0.70f, 1.50f) }
    fun updateManualSyncMs(value: Int) { _manualSyncMs.value = value.coerceIn(-2000, 5000) }
    fun updateAutoSync(value: Boolean) { _autoSync.value = value }
    fun updateCatchUp(value: Boolean) { _catchUp.value = value }
    fun updateLowLatency(value: Boolean) { _lowLatency.value = value }
    fun updateMaxCatchUpSpeed(value: Float) { _maxCatchUpSpeed.value = value.coerceIn(1.0f, 1.30f) }

    fun saveSettings() {
        viewModelScope.launch {
            repository.updateApiKey(_apiKey.value)
            repository.updateVolumeRatio(_volumeRatio.value)
            repository.updateDubMode(_dubMode.value)
            repository.updateVoiceName(_voiceName.value)
            repository.updateVoiceSource(_voiceSource.value)
            repository.updateTtsRate(_ttsRate.value)
            repository.updateManualSyncMs(_manualSyncMs.value)
            repository.updateAutoSync(_autoSync.value)
            repository.updateCatchUp(_catchUp.value)
            repository.updateLowLatency(_lowLatency.value)
            repository.updateMaxCatchUpSpeed(_maxCatchUpSpeed.value)
        }
    }
}

class SettingsViewModelFactory(
    private val repository: UserPreferencesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
