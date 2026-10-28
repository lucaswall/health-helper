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
import kotlin.math.roundToInt

data class GlucoseEntryUiState(
    val valueMgDl: String = "",
    val displayMmolL: String = "",
    /** e.g. "Converted from 5.6 mmol/L" after scanning a mmol/L meter; cleared once the value is edited. */
    val conversionNote: String? = null,
    val relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN,
    val glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN,
    val specimenSource: SpecimenSource = SpecimenSource.UNKNOWN,
    val mealTypeVisible: Boolean = false,
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val isScanning: Boolean = false,
    /** Set after a successful scan so the user knows to double-check the filled value. */
    val filledFromPhoto: Boolean = false,
    val scanError: String? = null,
    val error: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class GlucoseEntryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val writeGlucoseReadingUseCase: WriteGlucoseReadingUseCase,
    private val inferGlucoseDefaultsUseCase: InferGlucoseDefaultsUseCase,
    private val readingImageScanner: ReadingImageScanner,
) : ViewModel() {

    private val initialValue: String = savedStateHandle.get<String>(KEY_EDIT_VALUE).orEmpty()
    private val restoredRelation: RelationToMeal? = savedStateHandle.get<String>(KEY_EDIT_RELATION)
        ?.let { name -> RelationToMeal.entries.firstOrNull { it.name == name } }

    private val _uiState = MutableStateFlow(
        validate(initialValue).let { (enabled, validationError) ->
            GlucoseEntryUiState(
                valueMgDl = initialValue,
                displayMmolL = parseMgDl(initialValue)?.let { computeMmolL(it) } ?: "",
                conversionNote = savedStateHandle.get<String>(KEY_CONVERSION_NOTE),
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        },
    )
    val uiState: StateFlow<GlucoseEntryUiState> = _uiState.asStateFlow()

    // Channel buffers the one-shot event until a collector attaches (e.g. across Activity recreation).
    private val _navigateHome = Channel<String>(Channel.BUFFERED)
    val navigateHome: Flow<String> = _navigateHome.receiveAsFlow()

    private var savingJob: Job? = null

    init {
        consumeSharedImage()
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
        // While a scan runs its result is about to replace the value, so edits are ignored (the
        // field is also disabled) rather than silently overwritten a moment later.
        if (_uiState.value.isScanning) return
        val sanitized = sanitizeDecimalInput(value)
        if (sanitized != _uiState.value.valueMgDl) {
            // The note describes the scanned value; it no longer applies once the user changes it.
            setConversionNote(null)
        }
        setValue(sanitized)
    }

    /** Scans a photo taken with the camera and fills the value with what was read. */
    fun onPhotoCaptured(imageBytes: ByteArray) {
        startScan { imageBytes }
    }

    /** Reports a camera/capture problem that happened before any bytes reached the ViewModel. */
    fun onCaptureError(message: String) {
        _uiState.update { it.copy(scanError = message) }
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
        val state = _uiState.value
        if (state.isSaving || state.isScanning || !state.isSaveEnabled) return

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
                when (val outcome = readingImageScanner.scanGlucose(bytes)) {
                    is ScanOutcome.Success -> {
                        val scanned = outcome.value
                        val mgDl = when (scanned.detectedUnit) {
                            GlucoseUnit.MG_DL -> scanned.value.roundToInt()
                            GlucoseUnit.MMOL_L -> GlucoseReading.fromMmolL(scanned.value)
                        }
                        _uiState.update { it.copy(isScanning = false, filledFromPhoto = true) }
                        setValue(mgDl.toString())
                        setConversionNote(
                            if (scanned.detectedUnit == GlucoseUnit.MMOL_L) {
                                "Converted from ${scanned.value} mmol/L"
                            } else {
                                null
                            },
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

    private fun setValue(valueMgDl: String) {
        savedStateHandle[KEY_EDIT_VALUE] = valueMgDl
        val (enabled, validationError) = validate(valueMgDl)
        val mmolL = parseMgDl(valueMgDl)?.let { computeMmolL(it) } ?: ""
        _uiState.update {
            it.copy(
                valueMgDl = valueMgDl,
                displayMmolL = mmolL,
                isSaveEnabled = enabled,
                validationError = validationError,
            )
        }
    }

    private fun setConversionNote(note: String?) {
        savedStateHandle[KEY_CONVERSION_NOTE] = note
        _uiState.update { it.copy(conversionNote = note) }
    }

    private fun persistMetadata() {
        val state = _uiState.value
        savedStateHandle[KEY_EDIT_RELATION] = state.relationToMeal.name
        savedStateHandle[KEY_EDIT_MEAL_TYPE] = state.glucoseMealType.name
        savedStateHandle[KEY_EDIT_SPECIMEN] = state.specimenSource.name
    }

    companion object {
        /** Optional nav argument: path of a shared image (copied into the cache) to scan on open. */
        const val ARG_SHARED_PATH = "sharedPath"

        private const val KEY_EDIT_VALUE = "edit_value"
        private const val KEY_CONVERSION_NOTE = "conversion_note"
        private const val KEY_SHARED_IMAGE_CONSUMED = "shared_image_consumed"
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

        /** A blank value just keeps Save disabled (the form starts empty); typed values get a message. */
        private fun validate(valueStr: String): Pair<Boolean, String?> {
            if (valueStr.isEmpty()) return Pair(false, null)
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
