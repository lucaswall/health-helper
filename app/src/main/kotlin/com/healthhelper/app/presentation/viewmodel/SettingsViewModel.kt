package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val anthropicApiKey: String = "",
    val baseUrl: String = "",
    val syncInterval: Int = 15,
    val isConfigured: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val saveError: String? = null,
) {
    override fun toString(): String =
        "SettingsUiState(apiKey=***, anthropicApiKey=***, baseUrl=$baseUrl, " +
            "syncInterval=$syncInterval, isConfigured=$isConfigured, " +
            "hasUnsavedChanges=$hasUnsavedChanges, saveError=$saveError)"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private data class PersistedSettings(
        val apiKey: String = "",
        val anthropicApiKey: String = "",
        val baseUrl: String = "",
        val syncInterval: Int = 15,
    ) {
        override fun toString(): String =
            "PersistedSettings(apiKey=***, anthropicApiKey=***, baseUrl=$baseUrl, syncInterval=$syncInterval)"
    }

    private var persistedSettings = PersistedSettings()
    private var isSaving = false

    init {
        viewModelScope.launch {
            try {
                combine(
                    settingsRepository.apiKeyFlow,
                    settingsRepository.baseUrlFlow,
                    settingsRepository.syncIntervalFlow,
                    settingsRepository.anthropicApiKeyFlow,
                ) { apiKey, baseUrl, syncInterval, anthropicApiKey ->
                    PersistedSettings(apiKey, anthropicApiKey, baseUrl, syncInterval)
                }.collect { settings ->
                    persistedSettings = settings
                    if (!isSaving && !_uiState.value.hasUnsavedChanges) {
                        val configured = settings.apiKey.isNotEmpty() && settings.baseUrl.isNotEmpty()
                        _uiState.update {
                            it.copy(
                                apiKey = settings.apiKey,
                                anthropicApiKey = settings.anthropicApiKey,
                                baseUrl = settings.baseUrl,
                                syncInterval = settings.syncInterval,
                                isConfigured = configured,
                                hasUnsavedChanges = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to collect settings flows")
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

    fun updateAnthropicApiKey(value: String) {
        _uiState.update {
            it.copy(anthropicApiKey = value).withDirtyFlag()
        }
    }

    fun save() {
        if (isSaving) return
        isSaving = true
        val current = _uiState.value
        viewModelScope.launch {
            try {
                var anyFailed = false
                val apiKeySaved = try {
                    settingsRepository.setApiKey(current.apiKey)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to save API key")
                    anyFailed = true
                    false
                }
                val baseUrlSaved = try {
                    settingsRepository.setBaseUrl(current.baseUrl)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to save base URL")
                    anyFailed = true
                    false
                }
                val intervalSaved = try {
                    settingsRepository.setSyncInterval(current.syncInterval)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to save sync interval")
                    anyFailed = true
                    false
                }
                val anthropicApiKeySaved = try {
                    settingsRepository.setAnthropicApiKey(current.anthropicApiKey)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to save Anthropic API key")
                    anyFailed = true
                    false
                }
                persistedSettings = PersistedSettings(
                    apiKey = if (apiKeySaved) current.apiKey else persistedSettings.apiKey,
                    anthropicApiKey = if (anthropicApiKeySaved) current.anthropicApiKey else persistedSettings.anthropicApiKey,
                    baseUrl = if (baseUrlSaved) current.baseUrl else persistedSettings.baseUrl,
                    syncInterval = if (intervalSaved) current.syncInterval else persistedSettings.syncInterval,
                )
                _uiState.update {
                    it.copy(
                        saveError = if (anyFailed) {
                            "Some settings could not be saved. Please try again."
                        } else {
                            null
                        },
                    ).withDirtyFlag()
                }
            } finally {
                isSaving = false
            }
        }
    }

    fun reset() {
        _uiState.update {
            val configured = persistedSettings.apiKey.isNotEmpty() &&
                persistedSettings.baseUrl.isNotEmpty()
            it.copy(
                apiKey = persistedSettings.apiKey,
                anthropicApiKey = persistedSettings.anthropicApiKey,
                baseUrl = persistedSettings.baseUrl,
                syncInterval = persistedSettings.syncInterval,
                isConfigured = configured,
                hasUnsavedChanges = false,
            )
        }
    }

    private fun SettingsUiState.withDirtyFlag(): SettingsUiState {
        val dirty = apiKey != persistedSettings.apiKey ||
            anthropicApiKey != persistedSettings.anthropicApiKey ||
            baseUrl != persistedSettings.baseUrl ||
            syncInterval != persistedSettings.syncInterval
        return copy(hasUnsavedChanges = dirty)
    }
}
