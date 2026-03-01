package com.healthhelper.app.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.di.DefaultDispatcher
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
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

    private val _navigateToConfirmation = MutableSharedFlow<Triple<Double, String, String>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateToConfirmation: SharedFlow<Triple<Double, String, String>> = _navigateToConfirmation.asSharedFlow()

    private val _navigateBackWithError = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateBackWithError: SharedFlow<String> = _navigateBackWithError.asSharedFlow()

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
                    _navigateBackWithError.emit("Configure Anthropic API key in Settings")
                    return@launch
                }

                val preparedBytes = withContext(defaultDispatcher) {
                    prepareImageForApi(imageBytes, maxDimension = 1568)
                }
                if (preparedBytes == null) {
                    _uiState.update { it.copy(isProcessing = false) }
                    _navigateBackWithError.emit("Could not process image. Please try a different photo.")
                    return@launch
                }
                when (val result = anthropicApiClient.parseGlucoseImage(apiKey, preparedBytes)) {
                    is GlucoseParseResult.Success -> {
                        val unitStr = when (result.detectedUnit) {
                            GlucoseUnit.MMOL_L -> "mmol/L"
                            GlucoseUnit.MG_DL -> "mg/dL"
                        }
                        _uiState.update { it.copy(isProcessing = false) }
                        _navigateToConfirmation.emit(Triple(result.value, unitStr, unitStr))
                    }
                    is GlucoseParseResult.Error -> {
                        Timber.w("Glucose parse error: ${result.message}")
                        _uiState.update { it.copy(isProcessing = false) }
                        _navigateBackWithError.emit("Could not read glucose from image. Please retake.")
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isProcessing = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error capturing glucose image")
                _uiState.update { it.copy(isProcessing = false) }
                _navigateBackWithError.emit("Something went wrong. Please try again.")
            }
        }
    }

    fun onCaptureError(message: String) {
        _uiState.update { it.copy(isProcessing = false) }
        viewModelScope.launch {
            _navigateBackWithError.emit(message)
        }
    }

    private fun prepareImageForApi(imageBytes: ByteArray, maxDimension: Int): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) {
                Timber.w("prepareImageForApi: could not decode image dimensions")
                return null
            }

            val needsResize = width > maxDimension || height > maxDimension
            val scale = if (needsResize) maxDimension.toFloat() / maxOf(width, height) else 1f
            val newWidth = (width * scale).toInt()
            val newHeight = (height * scale).toInt()

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap == null) {
                Timber.w("prepareImageForApi: BitmapFactory.decodeByteArray returned null")
                return null
            }
            var scaled: Bitmap? = null
            try {
                scaled = if (needsResize) {
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else {
                    bitmap
                }
                val outputStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.toByteArray()
            } finally {
                if (scaled != null && scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Timber.w(e, "prepareImageForApi: failed to process image")
            null
        }
    }

    companion object {
        private const val KEY_TEMP_FILE_PATH = "glucose_temp_file_path"
    }
}
