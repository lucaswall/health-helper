package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.usecase.InferGlucoseDefaultsUseCase
import com.healthhelper.app.domain.usecase.WriteGlucoseReadingUseCase
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
import kotlin.math.roundToInt

data class GlucoseConfirmationUiState(
    val valueMgDl: String = "",
    val displayMmolL: String = "",
    val detectedUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
    /** Raw value as read from a mmol/L meter; empty when no unit conversion happened. */
    val originalMmolL: String = "",
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
    private val savedStateHandle: SavedStateHandle,
    private val writeGlucoseReadingUseCase: WriteGlucoseReadingUseCase,
    private val inferGlucoseDefaultsUseCase: InferGlucoseDefaultsUseCase,
) : ViewModel() {

    private val rawValue: Double = savedStateHandle.get<String>(ARG_VALUE)
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?: 5.6
    private val detectedUnit: GlucoseUnit = savedStateHandle.get<String>(ARG_UNIT)
        ?.let { name -> GlucoseUnit.entries.firstOrNull { it.name == name } }
        ?: GlucoseUnit.MMOL_L

    private val initialMgDl: Int = when (detectedUnit) {
        GlucoseUnit.MG_DL -> rawValue.roundToInt()
        GlucoseUnit.MMOL_L -> GlucoseReading.fromMmolL(rawValue)
    }

    private val initialValue: String = savedStateHandle.get<String>(KEY_EDIT_VALUE) ?: initialMgDl.toString()
    private val restoredRelation: RelationToMeal? = savedStateHandle.get<String>(KEY_EDIT_RELATION)
        ?.let { name -> RelationToMeal.entries.firstOrNull { it.name == name } }

    private val _uiState = MutableStateFlow(
        validate(initialValue).let { (enabled, validationError) ->
            GlucoseConfirmationUiState(
                valueMgDl = initialValue,
                displayMmolL = parseMgDl(initialValue)?.let { computeMmolL(it) } ?: "",
                detectedUnit = detectedUnit,
                originalMmolL = if (detectedUnit == GlucoseUnit.MMOL_L) rawValue.toString() else "",
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        },
    )
    val uiState: StateFlow<GlucoseConfirmationUiState> = _uiState.asStateFlow()

    // Channel buffers the one-shot event until a collector attaches (e.g. across Activity recreation).
    private val _navigateHome = Channel<String>(Channel.BUFFERED)
    val navigateHome: Flow<String> = _navigateHome.receiveAsFlow()

    private var savingJob: Job? = null

    init {
        if (restoredRelation != null) {
            // Restored after process death: keep the user's edits instead of re-inferring defaults.
            val mealType = savedStateHandle.get<String>(KEY_EDIT_MEAL_TYPE)
                ?.let { name -> GlucoseMealType.entries.firstOrNull { it.name == name } }
                ?: GlucoseMealType.UNKNOWN
            val specimen = savedStateHandle.get<String>(KEY_EDIT_SPECIMEN)
                ?.let { name -> SpecimenSource.entries.firstOrNull { it.name == name } }
                ?: SpecimenSource.UNKNOWN
            _uiState.update {
                it.copy(
                    relationToMeal = restoredRelation,
                    glucoseMealType = mealType,
                    specimenSource = specimen,
                    mealTypeVisible = isMealTypeRelevant(restoredRelation),
                )
            }
        } else {
            viewModelScope.launch {
                try {
                    val defaults = inferGlucoseDefaultsUseCase()
                    _uiState.update {
                        it.copy(
                            relationToMeal = defaults.relationToMeal,
                            glucoseMealType = defaults.glucoseMealType,
                            specimenSource = defaults.specimenSource,
                            mealTypeVisible = isMealTypeRelevant(defaults.relationToMeal),
                        )
                    }
                    persistMetadata()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to infer glucose defaults")
                }
            }
        }
    }

    fun updateValue(value: String) {
        val sanitized = sanitizeDecimalInput(value)
        savedStateHandle[KEY_EDIT_VALUE] = sanitized
        val (enabled, validationError) = validate(sanitized)
        val mmolL = parseMgDl(sanitized)?.let { computeMmolL(it) } ?: ""
        _uiState.update {
            it.copy(
                valueMgDl = sanitized,
                displayMmolL = mmolL,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    fun updateRelationToMeal(relation: RelationToMeal) {
        val mealTypeVisible = isMealTypeRelevant(relation)
        _uiState.update {
            it.copy(
                relationToMeal = relation,
                mealTypeVisible = mealTypeVisible,
                // A meal type is contradictory metadata for FASTING/GENERAL/UNKNOWN readings.
                glucoseMealType = if (mealTypeVisible) it.glucoseMealType else GlucoseMealType.UNKNOWN,
            )
        }
        persistMetadata()
    }

    fun updateGlucoseMealType(mealType: GlucoseMealType) {
        _uiState.update { it.copy(glucoseMealType = mealType) }
        persistMetadata()
    }

    fun updateSpecimenSource(source: SpecimenSource) {
        _uiState.update { it.copy(specimenSource = source) }
        persistMetadata()
    }

    fun save() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        if (!state.isSaveEnabled) return

        val mgDl = parseMgDl(state.valueMgDl)?.roundToInt() ?: return

        savingJob?.cancel()
        savingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = GlucoseReading(
                    valueMgDl = mgDl,
                    relationToMeal = state.relationToMeal,
                    glucoseMealType = if (state.mealTypeVisible) state.glucoseMealType else GlucoseMealType.UNKNOWN,
                    specimenSource = state.specimenSource,
                )
                val result = writeGlucoseReadingUseCase.invoke(reading)
                val savedMsg = "$mgDl mg/dL saved"
                when {
                    result.allSucceeded -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send(savedMsg)
                    }
                    result.healthConnectSuccess && result.foodScannerFailed -> {
                        Timber.w(result.foodScannerResult.exceptionOrNull(), "Food-scanner sync failed for glucose")
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send("$savedMsg to Health Connect, but could not sync to food-scanner")
                    }
                    !result.healthConnectSuccess && !result.foodScannerFailed -> {
                        Timber.w("Health Connect write failed for glucose (non-blocking)")
                        _uiState.update { it.copy(isSaving = false) }
                        _navigateHome.send("$savedMsg to food-scanner, but could not write to Health Connect")
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

    private fun persistMetadata() {
        val state = _uiState.value
        savedStateHandle[KEY_EDIT_RELATION] = state.relationToMeal.name
        savedStateHandle[KEY_EDIT_MEAL_TYPE] = state.glucoseMealType.name
        savedStateHandle[KEY_EDIT_SPECIMEN] = state.specimenSource.name
    }

    companion object {
        /** Nav argument: the value as read from the meter, as a Double string. */
        const val ARG_VALUE = "value"

        /** Nav argument: [GlucoseUnit.name] of the unit the meter displayed. */
        const val ARG_UNIT = "unit"

        private const val KEY_EDIT_VALUE = "edit_value"
        private const val KEY_EDIT_RELATION = "edit_relation"
        private const val KEY_EDIT_MEAL_TYPE = "edit_meal_type"
        private const val KEY_EDIT_SPECIMEN = "edit_specimen"

        private fun isMealTypeRelevant(relation: RelationToMeal): Boolean =
            relation == RelationToMeal.BEFORE_MEAL || relation == RelationToMeal.AFTER_MEAL

        /** Keeps digits and the first decimal point; drops anything else an IME or paste may insert. */
        private fun sanitizeDecimalInput(input: String): String {
            val out = StringBuilder()
            var seenDot = false
            input.forEach { c ->
                when {
                    c.isDigit() && c in '0'..'9' -> out.append(c)
                    (c == '.' || c == ',') && !seenDot -> {
                        out.append('.')
                        seenDot = true
                    }
                }
            }
            return out.toString()
        }

        private fun parseMgDl(valueStr: String): Double? =
            valueStr.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun validate(valueStr: String): Pair<Boolean, String?> {
            val value = parseMgDl(valueStr)?.roundToInt()
                ?: return Pair(false, "Please enter a valid number")

            if (value < 18 || value > 720) {
                return Pair(false, "Value must be between 18 and 720 mg/dL")
            }
            return Pair(true, null)
        }

        private fun computeMmolL(mgDl: Double): String = "%.1f".format(mgDl / 18.018)
    }
}
