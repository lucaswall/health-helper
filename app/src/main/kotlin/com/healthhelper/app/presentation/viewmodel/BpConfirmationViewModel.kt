package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.usecase.WriteBloodPressureReadingUseCase
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
    val warning: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class BpConfirmationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val writeBloodPressureReadingUseCase: WriteBloodPressureReadingUseCase,
) : ViewModel() {

    private val initialSystolic: Int = savedStateHandle["systolic"] ?: 120
    private val initialDiastolic: Int = savedStateHandle["diastolic"] ?: 80

    private val _uiState = MutableStateFlow(
        BpConfirmationUiState(
            systolic = initialSystolic.toString(),
            diastolic = initialDiastolic.toString(),
            isSaveEnabled = validate(initialSystolic.toString(), initialDiastolic.toString()).first,
        ),
    )
    val uiState: StateFlow<BpConfirmationUiState> = _uiState.asStateFlow()

    private val _navigateHome = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateHome: SharedFlow<String> = _navigateHome.asSharedFlow()

    private var savingJob: Job? = null

    fun updateSystolic(value: String) {
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
        _uiState.update { it.copy(bodyPosition = position) }
    }

    fun updateMeasurementLocation(location: MeasurementLocation) {
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
                when {
                    result.allSucceeded -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.emit("$systolic/$diastolic mmHg saved")
                    }
                    result.healthConnectSuccess && result.foodScannerFailed -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Food-scanner sync failed for blood pressure")
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                warning = "Saved to Health Connect but could not sync to food-scanner.",
                            )
                        }
                        _navigateHome.emit("$systolic/$diastolic mmHg saved")
                    }
                    !result.healthConnectSuccess && !result.foodScannerFailed -> {
                        Timber.w("Health Connect write failed for blood pressure (non-blocking)")
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                warning = "Reading saved but could not be written to Health Connect.",
                            )
                        }
                        _navigateHome.emit("$systolic/$diastolic mmHg saved")
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
