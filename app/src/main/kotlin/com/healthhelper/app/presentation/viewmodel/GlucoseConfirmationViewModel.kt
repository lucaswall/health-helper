package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.usecase.WriteGlucoseReadingUseCase
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
import kotlin.math.roundToInt

data class GlucoseConfirmationUiState(
    val valueMmolL: String = "",
    val displayMgDl: String = "",
    val detectedUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
    val originalValue: String = "",
    val relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN,
    val glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN,
    val specimenSource: SpecimenSource = SpecimenSource.UNKNOWN,
    val mealTypeVisible: Boolean = false,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class GlucoseConfirmationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val writeGlucoseReadingUseCase: WriteGlucoseReadingUseCase,
) : ViewModel() {

    private val rawValue: Float = savedStateHandle["value"] ?: 5.6f
    private val unitStr: String = savedStateHandle["unit"] ?: "mmol/L"
    private val detectedUnitStr: String = savedStateHandle["detectedUnit"] ?: "mmol/L"

    private val detectedUnit = if (detectedUnitStr == "mg/dL") GlucoseUnit.MG_DL else GlucoseUnit.MMOL_L

    private val initialMmolL: Double = if (unitStr == "mg/dL") {
        (rawValue / 18.018).toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP).toDouble()
    } else {
        rawValue.toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP).toDouble()
    }

    private val _uiState = MutableStateFlow(
        GlucoseConfirmationUiState(
            valueMmolL = formatMmolL(initialMmolL),
            displayMgDl = computeMgDl(initialMmolL),
            detectedUnit = detectedUnit,
            originalValue = if (detectedUnit == GlucoseUnit.MG_DL) rawValue.roundToInt().toString() else "",
            isSaveEnabled = validate(formatMmolL(initialMmolL)).first,
        ),
    )
    val uiState: StateFlow<GlucoseConfirmationUiState> = _uiState.asStateFlow()

    private val _navigateHome = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateHome: SharedFlow<String> = _navigateHome.asSharedFlow()

    private var savingJob: Job? = null

    fun updateValue(value: String) {
        val (enabled, validationError) = validate(value)
        val mgDl = value.toDoubleOrNull()?.let { computeMgDl(it) } ?: ""
        _uiState.update {
            it.copy(
                valueMmolL = value,
                displayMgDl = mgDl,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    fun updateRelationToMeal(relation: RelationToMeal) {
        _uiState.update {
            it.copy(
                relationToMeal = relation,
                mealTypeVisible = relation == RelationToMeal.BEFORE_MEAL || relation == RelationToMeal.AFTER_MEAL,
            )
        }
    }

    fun updateGlucoseMealType(mealType: GlucoseMealType) {
        _uiState.update { it.copy(glucoseMealType = mealType) }
    }

    fun updateSpecimenSource(source: SpecimenSource) {
        _uiState.update { it.copy(specimenSource = source) }
    }

    fun save() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        if (!state.isSaveEnabled) return

        val mmolL = state.valueMmolL.toDoubleOrNull() ?: return

        savingJob?.cancel()
        savingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = GlucoseReading(
                    valueMmolL = mmolL,
                    relationToMeal = state.relationToMeal,
                    glucoseMealType = state.glucoseMealType,
                    specimenSource = state.specimenSource,
                )
                val success = writeGlucoseReadingUseCase.invoke(reading)
                if (success) {
                    _uiState.update { it.copy(isSaving = false) }
                    _navigateHome.emit("${state.valueMmolL} mmol/L saved")
                } else {
                    _uiState.update {
                        it.copy(isSaving = false, error = "Failed to save reading. Please try again.")
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error saving glucose reading")
                _uiState.update {
                    it.copy(isSaving = false, error = "Something went wrong. Please try again.")
                }
            }
        }
    }

    private companion object {
        fun validate(valueStr: String): Pair<Boolean, String?> {
            val value = valueStr.toDoubleOrNull()
                ?: return Pair(false, "Please enter a valid number")

            if (value < 1.0 || value > 40.0) {
                return Pair(false, "Value must be between 1.0 and 40.0 mmol/L")
            }
            return Pair(true, null)
        }

        fun formatMmolL(value: Double): String = "%.1f".format(value)

        fun computeMgDl(mmolL: Double): String =
            (mmolL * 18.018).roundToInt().toString()
    }
}
