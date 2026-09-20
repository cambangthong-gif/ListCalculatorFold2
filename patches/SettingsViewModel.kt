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
    private val _originalVolume = MutableStateFlow(0.35f)
    val originalVolume: StateFlow<Float> = _originalVolume.asStateFlow()
    private val _dubMode = MutableStateFlow("auto_duck")
    val dubMode: StateFlow<String> = _dubMode.asStateFlow()
    private val _voiceName = MutableStateFlow("Kore")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()
    private val _voiceSource = MutableStateFlow("gemini")
    val voiceSource: StateFlow<String> = _voiceSource.asStateFlow()
    private val _ttsEnginePackage = MutableStateFlow("")
    val ttsEnginePackage: StateFlow<String> = _ttsEnginePackage.asStateFlow()
    private val _ttsVoiceName = MutableStateFlow("")
    val ttsVoiceName: StateFlow<String> = _ttsVoiceName.asStateFlow()
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
    private val _geminiSyncMode = MutableStateFlow("auto")
    val geminiSyncMode: StateFlow<String> = _geminiSyncMode.asStateFlow()
    private val _geminiMicroCatchUp = MutableStateFlow(true)
    val geminiMicroCatchUp: StateFlow<Boolean> = _geminiMicroCatchUp.asStateFlow()
    private val _geminiTailFinish = MutableStateFlow(true)
    val geminiTailFinish: StateFlow<Boolean> = _geminiTailFinish.asStateFlow()
    private val _geminiInterruptionMode = MutableStateFlow("no_interruption")
    val geminiInterruptionMode: StateFlow<String> = _geminiInterruptionMode.asStateFlow()
    private val _geminiAdaptiveVad = MutableStateFlow(true)
    val geminiAdaptiveVad: StateFlow<Boolean> = _geminiAdaptiveVad.asStateFlow()

    init {
        viewModelScope.launch {
            _apiKey.value = repository.apiKeyFlow.first()
            _volumeRatio.value = repository.volumeRatioFlow.first()
            _originalVolume.value = repository.originalVolumeFlow.first()
            _dubMode.value = repository.dubModeFlow.first()
            _voiceName.value = repository.voiceNameFlow.first()
            _voiceSource.value = repository.voiceSourceFlow.first()
            _ttsEnginePackage.value = repository.ttsEnginePackageFlow.first()
            _ttsVoiceName.value = repository.ttsVoiceNameFlow.first()
            _ttsRate.value = repository.ttsRateFlow.first()
            _manualSyncMs.value = repository.manualSyncMsFlow.first()
            _autoSync.value = repository.autoSyncFlow.first()
            _catchUp.value = repository.catchUpFlow.first()
            _lowLatency.value = repository.lowLatencyFlow.first()
            _maxCatchUpSpeed.value = repository.maxCatchUpSpeedFlow.first()
            _geminiSyncMode.value = repository.geminiSyncModeFlow.first()
            _geminiMicroCatchUp.value = repository.geminiMicroCatchUpFlow.first()
            _geminiTailFinish.value = repository.geminiTailFinishFlow.first()
            _geminiInterruptionMode.value = repository.geminiInterruptionModeFlow.first()
            _geminiAdaptiveVad.value = repository.geminiAdaptiveVadFlow.first()
        }
    }

    fun updateApiKey(v: String) { _apiKey.value = v }
    fun updateVolumeRatio(v: Float) { _volumeRatio.value = v.coerceIn(0f, 1f) }
    fun updateOriginalVolume(v: Float) { _originalVolume.value = v.coerceIn(0f, 1f) }
    fun updateDubMode(v: String) { _dubMode.value = v }
    fun updateVoiceName(v: String) { _voiceName.value = v }
    fun updateVoiceSource(v: String) { _voiceSource.value = v }
    fun updateTtsEnginePackage(v: String) {
        if (_ttsEnginePackage.value != v) {
            _ttsEnginePackage.value = v
            _ttsVoiceName.value = ""
        }
    }
    fun updateTtsVoiceName(v: String) { _ttsVoiceName.value = v }
    fun updateTtsRate(v: Float) { _ttsRate.value = v.coerceIn(0.70f, 1.50f) }
    fun updateManualSyncMs(v: Int) { _manualSyncMs.value = v.coerceIn(-2000, 5000) }
    fun updateAutoSync(v: Boolean) { _autoSync.value = v }
    fun updateCatchUp(v: Boolean) { _catchUp.value = v }
    fun updateLowLatency(v: Boolean) { _lowLatency.value = v }
    fun updateMaxCatchUpSpeed(v: Float) { _maxCatchUpSpeed.value = v.coerceIn(1.0f, 1.30f) }
    fun updateGeminiSyncMode(v: String) { _geminiSyncMode.value = v }
    fun updateGeminiMicroCatchUp(v: Boolean) { _geminiMicroCatchUp.value = v }
    fun updateGeminiTailFinish(v: Boolean) { _geminiTailFinish.value = v }
    fun updateGeminiInterruptionMode(v: String) { _geminiInterruptionMode.value = v }
    fun updateGeminiAdaptiveVad(v: Boolean) { _geminiAdaptiveVad.value = v }

    fun saveSettings() {
        viewModelScope.launch {
            repository.updateApiKey(_apiKey.value)
            repository.updateVolumeRatio(_volumeRatio.value)
            repository.updateOriginalVolume(_originalVolume.value)
            repository.updateDubMode(_dubMode.value)
            repository.updateVoiceName(_voiceName.value)
            repository.updateVoiceSource(_voiceSource.value)
            repository.updateTtsEnginePackage(_ttsEnginePackage.value)
            repository.updateTtsVoiceName(_ttsVoiceName.value)
            repository.updateTtsRate(_ttsRate.value)
            repository.updateManualSyncMs(_manualSyncMs.value)
            repository.updateAutoSync(_autoSync.value)
            repository.updateCatchUp(_catchUp.value)
            repository.updateLowLatency(_lowLatency.value)
            repository.updateMaxCatchUpSpeed(_maxCatchUpSpeed.value)
            repository.updateGeminiSyncMode(_geminiSyncMode.value)
            repository.updateGeminiMicroCatchUp(_geminiMicroCatchUp.value)
            repository.updateGeminiTailFinish(_geminiTailFinish.value)
            repository.updateGeminiInterruptionMode(_geminiInterruptionMode.value)
            repository.updateGeminiAdaptiveVad(_geminiAdaptiveVad.value)
        }
    }
}

class SettingsViewModelFactory(private val repository: UserPreferencesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST") return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
