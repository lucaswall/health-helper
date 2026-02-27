package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import androidx.health.connect.client.HealthConnectClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,
    val syncProgress: SyncProgress? = null,
    val isConfigured: Boolean = false,
    val lastSyncedDate: String = "",
    val healthConnectAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val healthConnectClient: HealthConnectClient?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        _uiState.update {
            it.copy(healthConnectAvailable = healthConnectClient != null)
        }

        // Observe all settings flows for UI state — isConfigured derived reactively
        viewModelScope.launch {
            combine(
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
                settingsRepository.syncIntervalFlow,
                settingsRepository.lastSyncedDateFlow,
            ) { apiKey, baseUrl, _, lastSyncedDate ->
                val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()
                Pair(configured, lastSyncedDate)
            }.collect { (configured, lastSyncedDate) ->
                _uiState.update {
                    it.copy(
                        isConfigured = configured,
                        lastSyncedDate = lastSyncedDate,
                    )
                }
            }
        }

        // Schedule periodic sync only when interval or configured status changes
        viewModelScope.launch {
            combine(
                settingsRepository.syncIntervalFlow,
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
            ) { interval, apiKey, baseUrl ->
                val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()
                Pair(interval, configured)
            }.distinctUntilChanged().collect { (interval, configured) ->
                if (configured) {
                    syncScheduler.schedulePeriodic(interval)
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
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
                )
            }
        }
    }
}
