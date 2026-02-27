package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,
    val syncProgress: SyncProgress? = null,
    val isConfigured: Boolean = false,
    val lastSyncedDate: String = "",
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.syncIntervalFlow,
                settingsRepository.lastSyncedDateFlow,
            ) { syncInterval, lastSyncedDate ->
                Pair(syncInterval, lastSyncedDate)
            }.collect { (syncInterval, lastSyncedDate) ->
                val configured = settingsRepository.isConfigured()
                _uiState.update {
                    it.copy(
                        isConfigured = configured,
                        lastSyncedDate = lastSyncedDate,
                    )
                }
                if (configured) {
                    syncScheduler.schedulePeriodic(syncInterval)
                }
            }
        }
    }

    fun triggerSync() {
        if (_uiState.value.isSyncing) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncProgress = null) }
            val result = syncNutritionUseCase.invoke { progress ->
                _uiState.update { it.copy(syncProgress = progress) }
            }
            val resultMessage = when (result) {
                is SyncResult.Success -> "Synced ${result.recordsSynced} records"
                is SyncResult.Error -> "Error: ${result.message}"
                is SyncResult.NeedsConfiguration -> "Please configure API settings"
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncResult = resultMessage,
                    syncProgress = null,
                    isConfigured = result !is SyncResult.NeedsConfiguration || it.isConfigured,
                )
            }
        }
    }
}
