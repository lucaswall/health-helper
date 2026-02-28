package com.healthhelper.app.presentation.viewmodel

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

data class SyncUiState(
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,
    val syncProgress: SyncProgress? = null,
    val isConfigured: Boolean = false,
    val lastSyncedDate: String = "",
    val lastSyncTime: String = "",
    val healthConnectAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val lastSyncedMeals: List<SyncedMealSummary> = emptyList(),
    val nextSyncTime: String = "",
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val healthConnectClient: HealthConnectClient?,
) : ViewModel() {

    companion object {
        val WRITE_NUTRITION_PERMISSION = HealthPermission.getWritePermission(NutritionRecord::class)
    }

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null
    private var lastSyncTimestamp = 0L

    init {
        _uiState.update {
            it.copy(healthConnectAvailable = healthConnectClient != null)
        }

        // Check Health Connect permission on launch
        if (healthConnectClient != null) {
            viewModelScope.launch {
                try {
                    val startTime = System.currentTimeMillis()
                    val granted = withTimeout(10_000L) {
                        healthConnectClient.permissionController.getGrantedPermissions()
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    Timber.d("getGrantedPermissions() took ${elapsed}ms")
                    _uiState.update { it.copy(permissionGranted = granted.contains(WRITE_NUTRITION_PERMISSION)) }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to check Health Connect permissions")
                    // Leave permissionGranted = false
                }
            }
        }

        // Observe all settings flows for UI state — isConfigured derived reactively
        viewModelScope.launch {
            combine(
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
                settingsRepository.lastSyncedDateFlow,
            ) { apiKey, baseUrl, lastSyncedDate ->
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

        // Collect last sync timestamp and format as relative time
        viewModelScope.launch {
            settingsRepository.lastSyncTimestampFlow.collect { ts ->
                lastSyncTimestamp = ts
                _uiState.update { it.copy(lastSyncTime = formatRelativeTime(ts)) }
            }
        }

        // Periodically refresh relative time string
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (lastSyncTimestamp > 0) {
                    _uiState.update { it.copy(lastSyncTime = formatRelativeTime(lastSyncTimestamp)) }
                }
            }
        }

        // Collect lastSyncedMealsFlow separately
        viewModelScope.launch {
            settingsRepository.lastSyncedMealsFlow.collect { meals ->
                _uiState.update { it.copy(lastSyncedMeals = meals) }
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

        // Observe next sync time from WorkManager
        viewModelScope.launch {
            combine(
                syncScheduler.getNextSyncTimeFlow(),
                settingsRepository.apiKeyFlow,
                settingsRepository.baseUrlFlow,
            ) { nextSyncMs, apiKey, baseUrl ->
                val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()
                when {
                    !configured -> ""
                    nextSyncMs == null -> "Sync pending..."
                    else -> formatNextSyncTime(nextSyncMs)
                }
            }.collect { nextSyncText ->
                _uiState.update { it.copy(nextSyncTime = nextSyncText) }
            }
        }
    }

    private fun formatNextSyncTime(nextSyncMs: Long): String {
        val diffMs = nextSyncMs - System.currentTimeMillis()
        val diffMinutes = diffMs / 60_000L
        return if (diffMinutes < 60) {
            "Next sync in ~${diffMinutes}m"
        } else {
            val localTime = Instant.ofEpochMilli(nextSyncMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            "Next sync at ${localTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
    }

    fun triggerSync() {
        if (_uiState.value.isSyncing) return
        syncJob?.cancel()
        _uiState.update { it.copy(isSyncing = true, syncProgress = null) }
        syncJob = viewModelScope.launch {
            try {
                val result = syncNutritionUseCase.invoke { progress ->
                    _uiState.update { it.copy(syncProgress = progress) }
                }
                val resultMessage = when (result) {
                    is SyncResult.Success -> when {
                        result.recordsSynced == 0 -> "No new meals"
                        result.daysProcessed == 1 -> "Synced ${result.recordsSynced} meals across 1 day"
                        else -> "Synced ${result.recordsSynced} meals across ${result.daysProcessed} days"
                    }
                    is SyncResult.Error -> "Error: ${result.message}"
                    is SyncResult.NeedsConfiguration -> "Please configure API settings"
                }
                _uiState.update { it.copy(lastSyncResult = resultMessage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "triggerSync unexpected error")
                _uiState.update { it.copy(lastSyncResult = "Sync failed. Please try again.") }
            } finally {
                _uiState.update { it.copy(isSyncing = false, syncProgress = null) }
            }
        }
    }
}

internal fun formatRelativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    val diffMs = now - timestampMillis
    if (diffMs < 0) return "Just now" // future timestamp — clock skew
    val diffSec = diffMs / 1_000
    val diffMin = diffSec / 60
    val diffHour = diffMin / 60
    val diffDays = diffHour / 24
    return when {
        diffSec < 60 -> "Just now"
        diffMin < 60 -> "${diffMin} min ago"
        diffHour < 24 -> "${diffHour} hr ago"
        diffDays < 7 -> "${diffDays} days ago"
        else -> {
            val instant = Instant.ofEpochMilli(timestampMillis)
            val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            date.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }
}
