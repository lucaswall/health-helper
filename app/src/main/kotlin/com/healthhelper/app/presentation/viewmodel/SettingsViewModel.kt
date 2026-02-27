package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val syncInterval: Int = 15,
    val isConfigured: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
                settingsRepository.syncIntervalFlow,
            ) { apiKey, baseUrl, syncInterval ->
                Triple(apiKey, baseUrl, syncInterval)
            }.collect { (apiKey, baseUrl, syncInterval) ->
                val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()
                _uiState.update {
                    it.copy(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        syncInterval = syncInterval,
                        isConfigured = configured,
                    )
                }
            }
        }
    }

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value) }
        viewModelScope.launch {
            try {
                settingsRepository.setApiKey(value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save API key")
            }
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value) }
        viewModelScope.launch {
            try {
                settingsRepository.setBaseUrl(value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save base URL")
            }
        }
    }

    fun updateSyncInterval(value: Int) {
        _uiState.update { it.copy(syncInterval = value) }
        viewModelScope.launch {
            try {
                settingsRepository.setSyncInterval(value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save sync interval")
            }
        }
    }
}
