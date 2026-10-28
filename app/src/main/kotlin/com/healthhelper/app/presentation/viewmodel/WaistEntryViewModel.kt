package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.usecase.LogWaistReadingUseCase
import com.healthhelper.app.domain.usecase.WaistSaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

data class WaistEntryUiState(
    val valueCm: String = "",
    val isSaveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
)

@HiltViewModel
class WaistEntryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val logWaistReadingUseCase: LogWaistReadingUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        savedStateHandle.get<String>(KEY_EDIT_VALUE).orEmpty().let { value ->
            val (enabled, validationError) = validate(value)
            WaistEntryUiState(valueCm = value, isSaveEnabled = enabled, validationError = validationError)
        },
    )
    val uiState: StateFlow<WaistEntryUiState> = _uiState.asStateFlow()

    // Channel buffers the one-shot event until a collector attaches (e.g. across Activity recreation).
    private val _navigateHome = Channel<String>(Channel.BUFFERED)
    val navigateHome: Flow<String> = _navigateHome.receiveAsFlow()

    fun updateValue(value: String) {
        val sanitized = sanitizeDecimalInput(value)
        savedStateHandle[KEY_EDIT_VALUE] = sanitized
        val (enabled, validationError) = validate(sanitized)
        _uiState.update { it.copy(valueCm = sanitized, isSaveEnabled = enabled, validationError = validationError) }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || !state.isSaveEnabled) return
        val valueCm = parseCm(state.valueCm) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val reading = WaistReading(
                    valueCm = valueCm,
                    zoneOffset = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()),
                )
                val display = "%.1f".format(valueCm)
                // isSaving stays true on success so a quick second tap can't log the reading twice.
                when (logWaistReadingUseCase(reading)) {
                    WaistSaveResult.SYNCED -> _navigateHome.send("Waist $display cm saved")
                    WaistSaveResult.PENDING ->
                        _navigateHome.send("Waist $display cm saved on this phone; it will sync to food-scanner when online")
                    WaistSaveResult.PENDING_NOT_CONFIGURED ->
                        _navigateHome.send("Waist $display cm saved on this phone; set up food-scanner in Settings to sync it")
                    WaistSaveResult.FAILED -> _uiState.update {
                        it.copy(isSaving = false, error = "Could not save the reading. Please try again.")
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error saving waist reading")
                _uiState.update { it.copy(isSaving = false, error = "Something went wrong. Please try again.") }
            }
        }
    }

    private companion object {
        const val KEY_EDIT_VALUE = "edit_waist_cm"
        const val MAX_INTEGER_DIGITS = 3

        /** Digits plus one decimal separator (',' or '.'), at most one decimal digit. */
        fun sanitizeDecimalInput(input: String): String {
            val out = StringBuilder()
            var seenDot = false
            var decimals = 0
            var integerDigits = 0
            input.forEach { c ->
                when {
                    c in '0'..'9' && !seenDot && integerDigits < MAX_INTEGER_DIGITS -> {
                        out.append(c)
                        integerDigits++
                    }
                    c in '0'..'9' && seenDot && decimals < 1 -> {
                        out.append(c)
                        decimals++
                    }
                    (c == '.' || c == ',') && !seenDot -> {
                        out.append('.')
                        seenDot = true
                    }
                }
            }
            return out.toString()
        }

        fun parseCm(value: String): Double? =
            value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(WaistReading::roundToTenth)

        /** A blank field just keeps Save disabled (the form starts empty); typed values get a message. */
        fun validate(value: String): Pair<Boolean, String?> {
            if (value.isEmpty()) return Pair(false, null)
            val cm = parseCm(value) ?: return Pair(false, "Please enter a valid number")
            val range = WaistReading.VALUE_RANGE_CM
            if (cm !in range) {
                return Pair(false, "Waist must be between ${range.start.toInt()} and ${range.endInclusive.toInt()} cm")
            }
            return Pair(true, null)
        }
    }
}
