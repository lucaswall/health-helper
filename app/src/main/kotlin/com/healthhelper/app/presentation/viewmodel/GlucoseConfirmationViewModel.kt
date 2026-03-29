package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.usecase.InferGlucoseDefaultsUseCase
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
    val valueMgDl: String = "",
    val displayMmolL: String = "",
    val detectedUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
    val originalValue: String = "",
    val relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN,
    val glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN,
    val specimenSource: SpecimenSource = SpecimenSource.UNKNOWN,
    val mealTypeVisible: Boolean = false,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class GlucoseConfirmationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val writeGlucoseReadingUseCase: WriteGlucoseReadingUseCase,
    private val inferGlucoseDefaultsUseCase: InferGlucoseDefaultsUseCase,
) : ViewModel() {

    private val rawValue: Float = savedStateHandle["value"] ?: 5.6f
    private val unitStr: String = savedStateHandle["unit"] ?: "mmol/L"
    private val detectedUnitStr: String = savedStateHandle["detectedUnit"] ?: "mmol/L"

    private val detectedUnit = if (detectedUnitStr == "mg/dL") GlucoseUnit.MG_DL else GlucoseUnit.MMOL_L

    private val initialMgDl: Int = if (unitStr == "mg/dL") {
        rawValue.roundToInt()
    } else {
        GlucoseReading.fromMmolL(rawValue.toDouble())
    }

    private val _uiState = MutableStateFlow(
        GlucoseConfirmationUiState(
            valueMgDl = initialMgDl.toString(),
            displayMmolL = computeMmolL(initialMgDl.toDouble()),
            detectedUnit = detectedUnit,
            originalValue = if (detectedUnit == GlucoseUnit.MG_DL) rawValue.roundToInt().toString() else "",
            isSaveEnabled = validate(initialMgDl.toString()).first,
        ),
    )
    val uiState: StateFlow<GlucoseConfirmationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val defaults = inferGlucoseDefaultsUseCase()
                _uiState.update {
                    it.copy(
                        relationToMeal = defaults.relationToMeal,
                        glucoseMealType = defaults.glucoseMealType,
                        specimenSource = defaults.specimenSource,
                        mealTypeVisible = defaults.relationToMeal == RelationToMeal.BEFORE_MEAL ||
                            defaults.relationToMeal == RelationToMeal.AFTER_MEAL,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to infer glucose defaults")
            }
        }
    }

    private val _navigateHome = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateHome: SharedFlow<String> = _navigateHome.asSharedFlow()

    private var savingJob: Job? = null

    fun updateValue(value: String) {
        val (enabled, validationError) = validate(value)
        val mmolL = value.toDoubleOrNull()?.let { computeMmolL(it) } ?: ""
        _uiState.update {
            it.copy(
                valueMgDl = value,
                displayMmolL = mmolL,
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

        val mgDl = state.valueMgDl.toDoubleOrNull()?.roundToInt() ?: return

        savingJob?.cancel()
        savingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = GlucoseReading(
                    valueMgDl = mgDl,
                    relationToMeal = state.relationToMeal,
                    glucoseMealType = state.glucoseMealType,
                    specimenSource = state.specimenSource,
                )
                val result = writeGlucoseReadingUseCase.invoke(reading)
                when {
                    result.allSucceeded -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.emit("${state.valueMgDl} mg/dL saved")
                    }
                    result.healthConnectSuccess && result.foodScannerFailed -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Food-scanner sync failed for glucose")
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                warning = "Saved to Health Connect but could not sync to food-scanner.",
                            )
                        }
                        _navigateHome.emit("${state.valueMgDl} mg/dL saved")
                    }
                    !result.healthConnectSuccess && !result.foodScannerFailed -> {
                        Timber.w("Health Connect write failed for glucose (non-blocking)")
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                warning = "Reading saved but could not be written to Health Connect.",
                            )
                        }
                        _navigateHome.emit("${state.valueMgDl} mg/dL saved")
                    }
                    else -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Both writes failed for glucose")
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
                Timber.e(e, "Unexpected error saving glucose reading")
                _uiState.update {
                    it.copy(isSaving = false, error = "Something went wrong. Please try again.")
                }
            }
        }
    }

    private companion object {
        fun validate(valueStr: String): Pair<Boolean, String?> {
            val value = valueStr.toDoubleOrNull()?.roundToInt()
                ?: return Pair(false, "Please enter a valid number")

            if (value < 18 || value > 720) {
                return Pair(false, "Value must be between 18 and 720 mg/dL")
            }
            return Pair(true, null)
        }

        fun computeMmolL(mgDl: Double): String = "%.1f".format(mgDl / 18.018)
    }
}
