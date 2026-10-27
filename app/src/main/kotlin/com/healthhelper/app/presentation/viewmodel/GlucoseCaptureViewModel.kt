package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.di.DefaultDispatcher
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.presentation.image.API_MAX_IMAGE_DIMENSION
import com.healthhelper.app.presentation.image.ImagePreparer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class GlucoseCaptureUiState(
    val isProcessing: Boolean = false,
)

@HiltViewModel
class GlucoseCaptureViewModel @Inject constructor(
    private val anthropicApiClient: AnthropicApiClient,
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val imagePreparer: ImagePreparer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlucoseCaptureUiState())
    val uiState: StateFlow<GlucoseCaptureUiState> = _uiState.asStateFlow()

    val tempFilePath: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_TEMP_FILE_PATH, null)

    fun setTempFilePath(path: String) {
        savedStateHandle[KEY_TEMP_FILE_PATH] = path
    }

    fun clearTempFilePath() {
        savedStateHandle[KEY_TEMP_FILE_PATH] = null
    }

    // Channels buffer one-shot events until a collector attaches (e.g. across Activity recreation).
    private val _navigateToConfirmation = Channel<Pair<Double, GlucoseUnit>>(Channel.BUFFERED)
    val navigateToConfirmation: Flow<Pair<Double, GlucoseUnit>> = _navigateToConfirmation.receiveAsFlow()

    private val _navigateBackWithError = Channel<String>(Channel.BUFFERED)
    val navigateBackWithError: Flow<String> = _navigateBackWithError.receiveAsFlow()

    private var processingJob: Job? = null

    fun onPhotoCaptured(imageBytes: ByteArray) {
        if (_uiState.value.isProcessing) return

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            try {
                val apiKey = settingsRepository.anthropicApiKeyFlow.first()
                if (apiKey.isEmpty()) {
                    _uiState.update { it.copy(isProcessing = false) }
                    _navigateBackWithError.send("Configure Anthropic API key in Settings")
                    return@launch
                }

                val preparedBytes = withContext(defaultDispatcher) {
                    imagePreparer.prepare(imageBytes, maxDimension = API_MAX_IMAGE_DIMENSION)
                }
                if (preparedBytes == null) {
                    _uiState.update { it.copy(isProcessing = false) }
                    _navigateBackWithError.send("Could not process image. Please try a different photo.")
                    return@launch
                }
                when (val result = anthropicApiClient.parseGlucoseImage(apiKey, preparedBytes)) {
                    is GlucoseParseResult.Success -> {
                        _uiState.update { it.copy(isProcessing = false) }
                        _navigateToConfirmation.send(Pair(result.value, result.detectedUnit))
                    }
                    is GlucoseParseResult.Error -> {
                        Timber.w("Glucose parse error (%s): %s", result.kind, result.message)
                        _uiState.update { it.copy(isProcessing = false) }
                        _navigateBackWithError.send(
                            imageParseErrorMessage(
                                result.kind,
                                "Could not read glucose from image. Please retake.",
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isProcessing = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error capturing glucose image")
                _uiState.update { it.copy(isProcessing = false) }
                _navigateBackWithError.send("Something went wrong. Please try again.")
            }
        }
    }

    fun onCaptureError(message: String) {
        processingJob?.cancel()
        processingJob = null
        _uiState.update { it.copy(isProcessing = false) }
        _navigateBackWithError.trySend(message)
    }

    companion object {
        private const val KEY_TEMP_FILE_PATH = "glucose_temp_file_path"
    }
}
