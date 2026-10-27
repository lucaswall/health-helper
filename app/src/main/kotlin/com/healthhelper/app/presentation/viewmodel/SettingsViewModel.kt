package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.repository.SecureStorageUnavailableException
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.repository.isValidHttpsBaseUrl
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

const val MIN_SYNC_INTERVAL_MINUTES = 15
const val MAX_SYNC_INTERVAL_MINUTES = 120

data class SettingsUiState(
    val apiKey: String = "",
    val anthropicApiKey: String = "",
    val baseUrl: String = "",
    val syncInterval: Int = MIN_SYNC_INTERVAL_MINUTES,
    val isLoaded: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val baseUrlError: String? = null,
    val saveError: String? = null,
) {
    override fun toString(): String =
        "SettingsUiState(apiKey=***, anthropicApiKey=***, baseUrl=$baseUrl, " +
            "syncInterval=$syncInterval, isLoaded=$isLoaded, " +
            "hasUnsavedChanges=$hasUnsavedChanges, baseUrlError=$baseUrlError, saveError=$saveError)"
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
        val syncInterval: Int = MIN_SYNC_INTERVAL_MINUTES,
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
                    val state = _uiState.value
                    // Inputs are disabled until the first emission, so the first load always applies.
                    if (!state.isLoaded || (!isSaving && !state.hasUnsavedChanges)) {
                        _uiState.update {
                            it.copy(
                                apiKey = settings.apiKey,
                                anthropicApiKey = settings.anthropicApiKey,
                                baseUrl = settings.baseUrl,
                                syncInterval = settings.syncInterval,
                                isLoaded = true,
                                hasUnsavedChanges = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to collect settings flows")
                // Don't leave the screen permanently disabled; let the user re-enter settings.
                _uiState.update { it.copy(isLoaded = true) }
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
            it.copy(baseUrl = value, baseUrlError = null).withDirtyFlag()
        }
    }

    fun updateSyncInterval(value: Int) {
        _uiState.update {
            it.copy(
                syncInterval = value.coerceIn(MIN_SYNC_INTERVAL_MINUTES, MAX_SYNC_INTERVAL_MINUTES),
            ).withDirtyFlag()
        }
    }

    fun updateAnthropicApiKey(value: String) {
        _uiState.update {
            it.copy(anthropicApiKey = value).withDirtyFlag()
        }
    }

    fun save() {
        if (isSaving || !_uiState.value.isLoaded) return
        val current = _uiState.value.let {
            it.copy(
                apiKey = it.apiKey.trim(),
                anthropicApiKey = it.anthropicApiKey.trim(),
                baseUrl = it.baseUrl.trim(),
            )
        }
        // Empty is allowed (clears the configuration); anything else must be an https URL with a host.
        if (current.baseUrl.isNotEmpty() && !isValidHttpsBaseUrl(current.baseUrl)) {
            _uiState.update { it.copy(baseUrlError = INVALID_BASE_URL_ERROR) }
            return
        }
        isSaving = true
        _uiState.update {
            it.copy(
                apiKey = current.apiKey,
                anthropicApiKey = current.anthropicApiKey,
                baseUrl = current.baseUrl,
                baseUrlError = null,
            )
        }
        viewModelScope.launch {
            try {
                var anyFailed = false
                var secureStorageFailed = false
                val apiKeySaved = try {
                    settingsRepository.setApiKey(current.apiKey)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to save API key")
                    anyFailed = true
                    if (e is SecureStorageUnavailableException) secureStorageFailed = true
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
                    if (e is SecureStorageUnavailableException) secureStorageFailed = true
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
                        saveError = when {
                            secureStorageFailed -> SECURE_STORAGE_ERROR
                            anyFailed -> GENERIC_SAVE_ERROR
                            else -> null
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
            it.copy(
                apiKey = persistedSettings.apiKey,
                anthropicApiKey = persistedSettings.anthropicApiKey,
                baseUrl = persistedSettings.baseUrl,
                syncInterval = persistedSettings.syncInterval,
                hasUnsavedChanges = false,
                baseUrlError = null,
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

    companion object {
        const val GENERIC_SAVE_ERROR = "Some settings could not be saved. Please try again."
        const val SECURE_STORAGE_ERROR =
            "Secure storage unavailable on this device; API key not saved."
        const val INVALID_BASE_URL_ERROR = "Enter a valid https:// URL"
    }
}
