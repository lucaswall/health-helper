package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.usecase.WriteBloodPressureReadingUseCase
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

data class BpConfirmationUiState(
    val systolic: String = "",
    val diastolic: String = "",
    val bodyPosition: BodyPosition = BodyPosition.SITTING_DOWN,
    val measurementLocation: MeasurementLocation = MeasurementLocation.LEFT_UPPER_ARM,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class BpConfirmationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val writeBloodPressureReadingUseCase: WriteBloodPressureReadingUseCase,
) : ViewModel() {

    private val initialSystolic: String =
        savedStateHandle.get<String>(KEY_EDIT_SYSTOLIC) ?: (savedStateHandle.get<Int>("systolic") ?: 120).toString()
    private val initialDiastolic: String =
        savedStateHandle.get<String>(KEY_EDIT_DIASTOLIC) ?: (savedStateHandle.get<Int>("diastolic") ?: 80).toString()
    private val initialBodyPosition: BodyPosition =
        savedStateHandle.get<String>(KEY_EDIT_BODY_POSITION)
            ?.let { name -> BodyPosition.entries.firstOrNull { it.name == name } }
            ?: BodyPosition.SITTING_DOWN
    private val initialLocation: MeasurementLocation =
        savedStateHandle.get<String>(KEY_EDIT_LOCATION)
            ?.let { name -> MeasurementLocation.entries.firstOrNull { it.name == name } }
            ?: MeasurementLocation.LEFT_UPPER_ARM

    private val _uiState = MutableStateFlow(
        validate(initialSystolic, initialDiastolic).let { (enabled, validationError) ->
            BpConfirmationUiState(
                systolic = initialSystolic,
                diastolic = initialDiastolic,
                bodyPosition = initialBodyPosition,
                measurementLocation = initialLocation,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        },
    )
    val uiState: StateFlow<BpConfirmationUiState> = _uiState.asStateFlow()

    // Channel buffers the one-shot event until a collector attaches (e.g. across Activity recreation).
    private val _navigateHome = Channel<String>(Channel.BUFFERED)
    val navigateHome: Flow<String> = _navigateHome.receiveAsFlow()

    private var savingJob: Job? = null

    fun updateSystolic(value: String) {
        savedStateHandle[KEY_EDIT_SYSTOLIC] = value
        val (enabled, validationError) = validate(value, _uiState.value.diastolic)
        _uiState.update {
            it.copy(
                systolic = value,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    fun updateDiastolic(value: String) {
        savedStateHandle[KEY_EDIT_DIASTOLIC] = value
        val (enabled, validationError) = validate(_uiState.value.systolic, value)
        _uiState.update {
            it.copy(
                diastolic = value,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    fun updateBodyPosition(position: BodyPosition) {
        savedStateHandle[KEY_EDIT_BODY_POSITION] = position.name
        _uiState.update { it.copy(bodyPosition = position) }
    }

    fun updateMeasurementLocation(location: MeasurementLocation) {
        savedStateHandle[KEY_EDIT_LOCATION] = location.name
        _uiState.update { it.copy(measurementLocation = location) }
    }

    fun save() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        if (!state.isSaveEnabled) return

        val systolic = state.systolic.toIntOrNull() ?: return
        val diastolic = state.diastolic.toIntOrNull() ?: return

        savingJob?.cancel()
        savingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = BloodPressureReading(
                    systolic = systolic,
                    diastolic = diastolic,
                    bodyPosition = state.bodyPosition,
                    measurementLocation = state.measurementLocation,
                )
                val result = writeBloodPressureReadingUseCase.invoke(reading)
                val savedMsg = "$systolic/$diastolic mmHg saved"
                when {
                    result.allSucceeded -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send(savedMsg)
                    }
                    result.healthConnectSuccess && result.foodScannerFailed -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Food-scanner sync failed for blood pressure")
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send("$savedMsg to Health Connect, but could not sync to food-scanner")
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

    private companion object {
        const val KEY_EDIT_SYSTOLIC = "edit_systolic"
        const val KEY_EDIT_DIASTOLIC = "edit_diastolic"
        const val KEY_EDIT_BODY_POSITION = "edit_body_position"
        const val KEY_EDIT_LOCATION = "edit_measurement_location"

        fun validate(systolicStr: String, diastolicStr: String): Pair<Boolean, String?> {
            val systolic = systolicStr.toIntOrNull()
            val diastolic = diastolicStr.toIntOrNull()

            if (systolic == null || diastolic == null) {
                return Pair(false, "Please enter valid numbers")
            }
            if (systolic !in 60..300) {
                return Pair(false, "Systolic must be between 60 and 300")
            }
            if (diastolic !in 30..200) {
                return Pair(false, "Diastolic must be between 30 and 200")
            }
            if (systolic <= diastolic) {
                return Pair(false, "Systolic must be greater than diastolic")
            }
            return Pair(true, null)
        }
    }
}
