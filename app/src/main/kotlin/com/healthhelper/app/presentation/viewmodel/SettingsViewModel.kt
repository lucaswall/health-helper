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
    val syncInterval: Int = 5,
    val isConfigured: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private data class PersistedSettings(
        val apiKey: String = "",
        val baseUrl: String = "",
        val syncInterval: Int = 5,
    )

    private var persistedSettings = PersistedSettings()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
                settingsRepository.syncIntervalFlow,
            ) { apiKey, baseUrl, syncInterval ->
                Triple(apiKey, baseUrl, syncInterval)
            }.collect { (apiKey, baseUrl, syncInterval) ->
                persistedSettings = PersistedSettings(apiKey, baseUrl, syncInterval)
                val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()
                _uiState.update {
                    it.copy(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        syncInterval = syncInterval,
                        isConfigured = configured,
                        hasUnsavedChanges = false,
                    )
                }
            }
        }
    }

    fun updateApiKey(value: String) {
        _uiState.update {
            it.copy(apiKey = value).withDirtyFlag()
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update {
            it.copy(baseUrl = value).withDirtyFlag()
        }
    }

    fun updateSyncInterval(value: Int) {
        _uiState.update {
            it.copy(syncInterval = value).withDirtyFlag()
        }
    }

    fun save() {
        val current = _uiState.value
        viewModelScope.launch {
            var anyFailed = false
            val apiKeySaved = try {
                settingsRepository.setApiKey(current.apiKey)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save API key")
                anyFailed = true
                false
            }
            val baseUrlSaved = try {
                settingsRepository.setBaseUrl(current.baseUrl)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save base URL")
                anyFailed = true
                false
            }
            val intervalSaved = try {
                settingsRepository.setSyncInterval(current.syncInterval)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save sync interval")
                anyFailed = true
                false
            }
            persistedSettings = PersistedSettings(
                apiKey = if (apiKeySaved) current.apiKey else persistedSettings.apiKey,
                baseUrl = if (baseUrlSaved) current.baseUrl else persistedSettings.baseUrl,
                syncInterval = if (intervalSaved) current.syncInterval else persistedSettings.syncInterval,
            )
            _uiState.update { it.withDirtyFlag() }
        }
    }

    fun reset() {
        _uiState.update {
            val configured = persistedSettings.apiKey.isNotEmpty() &&
                persistedSettings.baseUrl.isNotEmpty()
            it.copy(
                apiKey = persistedSettings.apiKey,
                baseUrl = persistedSettings.baseUrl,
                syncInterval = persistedSettings.syncInterval,
                isConfigured = configured,
                hasUnsavedChanges = false,
            )
        }
    }

    private fun SettingsUiState.withDirtyFlag(): SettingsUiState {
        val dirty = apiKey != persistedSettings.apiKey ||
            baseUrl != persistedSettings.baseUrl ||
            syncInterval != persistedSettings.syncInterval
        return copy(hasUnsavedChanges = dirty)
    }
}
