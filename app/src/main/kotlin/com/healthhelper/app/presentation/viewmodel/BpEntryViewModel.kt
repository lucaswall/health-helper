package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.usecase.WriteBloodPressureReadingUseCase
import com.healthhelper.app.presentation.image.ImageTooLargeException
import com.healthhelper.app.presentation.image.ReadingImageScanner
import com.healthhelper.app.presentation.image.ScanOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BpEntryUiState(
    val systolic: String = "",
    val diastolic: String = "",
    /** Optional pulse in bpm; blank means "not recorded". */
    val heartRate: String = "",
    val bodyPosition: BodyPosition = BodyPosition.SITTING_DOWN,
    val measurementLocation: MeasurementLocation = MeasurementLocation.LEFT_UPPER_ARM,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val isScanning: Boolean = false,
    /** Set after a successful scan so the user knows to double-check the filled values. */
    val filledFromPhoto: Boolean = false,
    val scanError: String? = null,
    val error: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class BpEntryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val writeBloodPressureReadingUseCase: WriteBloodPressureReadingUseCase,
    private val readingImageScanner: ReadingImageScanner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreState())
    val uiState: StateFlow<BpEntryUiState> = _uiState.asStateFlow()

    // Channel buffers the one-shot event until a collector attaches (e.g. across Activity recreation).
    private val _navigateHome = Channel<String>(Channel.BUFFERED)
    val navigateHome: Flow<String> = _navigateHome.receiveAsFlow()

    private var savingJob: Job? = null

    init {
        consumeSharedImage()
    }

    // While a scan runs its result is about to replace these fields, so edits are ignored (the
    // fields are also disabled) rather than silently overwritten a moment later.
    fun updateSystolic(value: String) {
        if (_uiState.value.isScanning) return
        updateNumbers(systolic = sanitizeInt(value))
    }

    fun updateDiastolic(value: String) {
        if (_uiState.value.isScanning) return
        updateNumbers(diastolic = sanitizeInt(value))
    }

    fun updateHeartRate(value: String) {
        if (_uiState.value.isScanning) return
        updateNumbers(heartRate = sanitizeInt(value))
    }

    fun updateBodyPosition(position: BodyPosition) {
        savedStateHandle[KEY_EDIT_BODY_POSITION] = position.name
        _uiState.update { it.copy(bodyPosition = position) }
    }

    fun updateMeasurementLocation(location: MeasurementLocation) {
        savedStateHandle[KEY_EDIT_LOCATION] = location.name
        _uiState.update { it.copy(measurementLocation = location) }
    }

    /** Scans a photo taken with the camera and fills the form with what was read. */
    fun onPhotoCaptured(imageBytes: ByteArray) {
        startScan { imageBytes }
    }

    /** Reports a camera/capture problem that happened before any bytes reached the ViewModel. */
    fun onCaptureError(message: String) {
        _uiState.update { it.copy(scanError = message) }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isScanning || !state.isSaveEnabled) return

        val systolic = state.systolic.toIntOrNull() ?: return
        val diastolic = state.diastolic.toIntOrNull() ?: return
        val heartRate = state.heartRate.toIntOrNull()

        savingJob?.cancel()
        savingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = BloodPressureReading(
                    systolic = systolic,
                    diastolic = diastolic,
                    bodyPosition = state.bodyPosition,
                    measurementLocation = state.measurementLocation,
                    heartRate = heartRate,
                )
                val result = writeBloodPressureReadingUseCase.invoke(reading)
                val savedMsg = buildString {
                    append("$systolic/$diastolic mmHg")
                    if (heartRate != null) append(", $heartRate bpm")
                    append(" saved")
                }
                val heartRateWarning = if (result.healthConnectSuccess && result.heartRateFailed) {
                    Timber.w("Health Connect heart rate write failed (non-blocking)")
                    "; heart rate could not be written to Health Connect"
                } else {
                    ""
                }
                when {
                    result.allSucceeded -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send(savedMsg + heartRateWarning)
                    }
                    result.healthConnectSuccess && result.foodScannerFailed -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Food-scanner sync failed for blood pressure")
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send(
                            "$savedMsg to Health Connect, but could not sync to food-scanner$heartRateWarning",
                        )
                    }
                    !result.healthConnectSuccess && !result.foodScannerFailed -> {
                        Timber.w("Health Connect write failed for blood pressure (non-blocking)")
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send("$savedMsg to food-scanner, but could not write to Health Connect")
                    }
                    else -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Both writes failed for blood pressure")
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                error = "Failed to save reading. Please try again.",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error saving blood pressure reading")
                _uiState.update {
                    it.copy(isSaving = false, error = "Something went wrong. Please try again.")
                }
            }
        }
    }

    /** A share-intent image arrives as a nav argument; scan it once (not again after rotation or restore). */
    private fun consumeSharedImage() {
        val path = savedStateHandle.get<String>(ARG_SHARED_PATH) ?: return
        if (savedStateHandle.get<Boolean>(KEY_SHARED_IMAGE_CONSUMED) == true) return
        savedStateHandle[KEY_SHARED_IMAGE_CONSUMED] = true
        startScan { readingImageScanner.readSharedImage(path) }
    }

    private fun startScan(loadImage: suspend () -> ByteArray) {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanError = null, filledFromPhoto = false) }
            try {
                val bytes = try {
                    loadImage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImageTooLargeException) {
                    Timber.w(e, "Shared image too large")
                    _uiState.update { it.copy(isScanning = false, scanError = "Image is too large. Please choose a smaller photo.") }
                    return@launch
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read shared image")
                    _uiState.update { it.copy(isScanning = false, scanError = "Could not read shared image.") }
                    return@launch
                }
                when (val outcome = readingImageScanner.scanBloodPressure(bytes)) {
                    is ScanOutcome.Success -> {
                        val scanned = outcome.value
                        _uiState.update { it.copy(isScanning = false, filledFromPhoto = true) }
                        updateNumbers(
                            systolic = scanned.systolic.toString(),
                            diastolic = scanned.diastolic.toString(),
                            // Keep a manually typed pulse when the display didn't show one.
                            heartRate = scanned.heartRate?.toString() ?: _uiState.value.heartRate,
                        )
                    }
                    is ScanOutcome.Failure -> {
                        _uiState.update { it.copy(isScanning = false, scanError = outcome.message) }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isScanning = false) }
                throw e
            }
        }
    }

    private fun updateNumbers(
        systolic: String = _uiState.value.systolic,
        diastolic: String = _uiState.value.diastolic,
        heartRate: String = _uiState.value.heartRate,
    ) {
        savedStateHandle[KEY_EDIT_SYSTOLIC] = systolic
        savedStateHandle[KEY_EDIT_DIASTOLIC] = diastolic
        savedStateHandle[KEY_EDIT_HEART_RATE] = heartRate
        val (enabled, validationError) = validate(systolic, diastolic, heartRate)
        _uiState.update {
            it.copy(
                systolic = systolic,
                diastolic = diastolic,
                heartRate = heartRate,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    private fun restoreState(): BpEntryUiState {
        val systolic = savedStateHandle.get<String>(KEY_EDIT_SYSTOLIC).orEmpty()
        val diastolic = savedStateHandle.get<String>(KEY_EDIT_DIASTOLIC).orEmpty()
        val heartRate = savedStateHandle.get<String>(KEY_EDIT_HEART_RATE).orEmpty()
        val (enabled, validationError) = validate(systolic, diastolic, heartRate)
        return BpEntryUiState(
            systolic = systolic,
            diastolic = diastolic,
            heartRate = heartRate,
            bodyPosition = savedStateHandle.get<String>(KEY_EDIT_BODY_POSITION)
                ?.let { name -> BodyPosition.entries.firstOrNull { it.name == name } }
                ?: BodyPosition.SITTING_DOWN,
            measurementLocation = savedStateHandle.get<String>(KEY_EDIT_LOCATION)
                ?.let { name -> MeasurementLocation.entries.firstOrNull { it.name == name } }
                ?: MeasurementLocation.LEFT_UPPER_ARM,
            isSaveEnabled = enabled,
            validationError = validationError,
        )
    }

    companion object {
        /** Optional nav argument: path of a shared image (copied into the cache) to scan on open. */
        const val ARG_SHARED_PATH = "sharedPath"

        private const val KEY_EDIT_SYSTOLIC = "edit_systolic"
        private const val KEY_EDIT_DIASTOLIC = "edit_diastolic"
        private const val KEY_EDIT_HEART_RATE = "edit_heart_rate"
        private const val KEY_EDIT_BODY_POSITION = "edit_body_position"
        private const val KEY_EDIT_LOCATION = "edit_measurement_location"
        private const val KEY_SHARED_IMAGE_CONSUMED = "shared_image_consumed"
        private const val MAX_DIGITS = 3

        /** Keeps digits only (an IME or paste may insert signs, separators or spaces). */
        private fun sanitizeInt(input: String): String = input.filter { it in '0'..'9' }.take(MAX_DIGITS)

        /**
         * Blank required fields just keep Save disabled (the form starts empty); only values the user
         * actually entered produce a message.
         */
        private fun validate(systolicStr: String, diastolicStr: String, heartRateStr: String): Pair<Boolean, String?> {
            val systolic = systolicStr.toIntOrNull()
            val diastolic = diastolicStr.toIntOrNull()
            val systolicRange = BloodPressureReading.SYSTOLIC_RANGE
            val diastolicRange = BloodPressureReading.DIASTOLIC_RANGE
            val heartRateRange = BloodPressureReading.HEART_RATE_RANGE

            if (systolic != null && systolic !in systolicRange) {
                return Pair(false, "Systolic must be between ${systolicRange.first} and ${systolicRange.last}")
            }
            if (diastolic != null && diastolic !in diastolicRange) {
                return Pair(false, "Diastolic must be between ${diastolicRange.first} and ${diastolicRange.last}")
            }
            if (systolic != null && diastolic != null && systolic <= diastolic) {
                return Pair(false, "Systolic must be greater than diastolic")
            }
            if (heartRateStr.isNotEmpty()) {
                val heartRate = heartRateStr.toIntOrNull()
                if (heartRate == null || heartRate !in heartRateRange) {
                    return Pair(false, "Heart rate must be between ${heartRateRange.first} and ${heartRateRange.last}")
                }
            }
            return Pair(systolic != null && diastolic != null, null)
        }
    }
}
