package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUiState(
    val isLoading: Boolean = false,
    val records: List<HealthRecord> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val readStepsUseCase: ReadStepsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        loadSteps()
    }

    fun loadSteps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val records = readStepsUseCase()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    records = records,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load steps",
                )
            }
        }
    }
}
