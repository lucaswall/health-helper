package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CameraCaptureUiState(
    val isProcessing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CameraCaptureViewModel @Inject constructor(
    private val anthropicApiClient: AnthropicApiClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraCaptureUiState())
    val uiState: StateFlow<CameraCaptureUiState> = _uiState.asStateFlow()

    private val _navigateToConfirmation = MutableSharedFlow<Pair<Int, Int>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateToConfirmation: SharedFlow<Pair<Int, Int>> = _navigateToConfirmation.asSharedFlow()

    private var processingJob: Job? = null

    fun onPhotoCaptured(imageBytes: ByteArray) {
        if (_uiState.value.isProcessing) return

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val apiKey = settingsRepository.anthropicApiKeyFlow.first()
                if (apiKey.isEmpty()) {
                    _uiState.update {
                        it.copy(isProcessing = false, error = "Configure Anthropic API key in Settings")
                    }
                    return@launch
                }

                when (val result = anthropicApiClient.parseBloodPressureImage(apiKey, imageBytes)) {
                    is BloodPressureParseResult.Success -> {
                        _uiState.update { it.copy(isProcessing = false) }
                        _navigateToConfirmation.emit(Pair(result.systolic, result.diastolic))
                    }
                    is BloodPressureParseResult.Error -> {
                        Timber.w("BP parse error: ${result.message}")
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                error = "Could not read blood pressure from image. Please retake.",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isProcessing = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error capturing blood pressure image")
                _uiState.update {
                    it.copy(isProcessing = false, error = "Something went wrong. Please try again.")
                }
            }
        }
    }

    fun onCaptureError(message: String) {
        _uiState.update { it.copy(isProcessing = false, error = message) }
    }

    fun onRetake() {
        processingJob?.cancel()
        _uiState.update { it.copy(isProcessing = false, error = null) }
    }
}
