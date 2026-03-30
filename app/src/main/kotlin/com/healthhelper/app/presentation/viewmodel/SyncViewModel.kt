package com.healthhelper.app.presentation.viewmodel

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.GetLastBloodPressureReadingUseCase
import com.healthhelper.app.domain.usecase.GetLastGlucoseReadingUseCase
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
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
import kotlinx.coroutines.flow.first
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
    val cameraPermissionGranted: Boolean = false,
    val lastSyncedMeals: List<SyncedMealSummary> = emptyList(),
    val nextSyncTime: String = "",
    val lastBpReading: BloodPressureReading? = null,
    val lastBpReadingDisplay: String = "",
    val lastBpReadingTime: String = "",
    val lastGlucoseReading: GlucoseReading? = null,
    val lastGlucoseReadingDisplay: String = "",
    val lastGlucoseReadingTime: String = "",
    val glucoseSyncStatus: String = "",
    val bpSyncStatus: String = "",
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val syncHealthReadingsUseCase: SyncHealthReadingsUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val getLastBpReadingUseCase: GetLastBloodPressureReadingUseCase,
    private val getLastGlucoseReadingUseCase: GetLastGlucoseReadingUseCase,
    private val healthConnectClient: HealthConnectClient?,
) : ViewModel() {

    companion object {
        val REQUIRED_HC_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getWritePermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
        )
    }

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null
    private var lastSyncTimestamp = 0L
    private var lastGlucoseSyncTs = 0L
    private var lastBpSyncTs = 0L
    private var glucoseSyncCount = 0
    private var bpSyncCount = 0
    private var glucoseSyncCaughtUp = false
    private var bpSyncCaughtUp = false

    init {
        Timber.d("SyncViewModel: init, healthConnectAvailable=%b", healthConnectClient != null)
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
                    val hasAll = granted.containsAll(REQUIRED_HC_PERMISSIONS)
                    Timber.d("SyncViewModel: HC permissions granted=%b (have %d/%d)", hasAll, granted.size, REQUIRED_HC_PERMISSIONS.size)
                    _uiState.update { it.copy(permissionGranted = hasAll) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to check Health Connect permissions")
                    // Leave permissionGranted = false
                }
            }
        }

        // Load last BP and glucose readings on launch
        viewModelScope.launch {
            loadLastBpReading()
        }
        viewModelScope.launch {
            loadLastGlucoseReading()
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
                // Also refresh BP and glucose reading time displays
                val currentReading = _uiState.value.lastBpReading
                if (currentReading != null) {
                    _uiState.update {
                        it.copy(lastBpReadingTime = formatRelativeTime(currentReading.timestamp.toEpochMilli()))
                    }
                }
                val currentGlucose = _uiState.value.lastGlucoseReading
                if (currentGlucose != null) {
                    _uiState.update {
                        it.copy(lastGlucoseReadingTime = formatRelativeTime(currentGlucose.timestamp.toEpochMilli()))
                    }
                }
                // Refresh sync status strings that contain relative timestamps
                if (lastGlucoseSyncTs > 0 && glucoseSyncCount > 0 && !glucoseSyncCaughtUp) {
                    _uiState.update {
                        it.copy(glucoseSyncStatus = formatSyncStatus(glucoseSyncCount, glucoseSyncCaughtUp, lastGlucoseSyncTs))
                    }
                }
                if (lastBpSyncTs > 0 && bpSyncCount > 0 && !bpSyncCaughtUp) {
                    _uiState.update {
                        it.copy(bpSyncStatus = formatSyncStatus(bpSyncCount, bpSyncCaughtUp, lastBpSyncTs))
                    }
                }
            }
        }

        // Collect lastSyncedMealsFlow separately
        viewModelScope.launch {
            settingsRepository.lastSyncedMealsFlow.collect { meals ->
                _uiState.update { it.copy(lastSyncedMeals = meals) }
            }
        }

        // Observe glucose sync status flows
        viewModelScope.launch {
            combine(
                settingsRepository.glucoseSyncCountFlow,
                settingsRepository.glucoseSyncCaughtUpFlow,
                settingsRepository.lastGlucoseSyncTimestampFlow,
            ) { count, caughtUp, ts ->
                glucoseSyncCount = count
                glucoseSyncCaughtUp = caughtUp
                lastGlucoseSyncTs = ts
                formatSyncStatus(count, caughtUp, ts)
            }.collect { status ->
                _uiState.update { it.copy(glucoseSyncStatus = status) }
            }
        }

        // Observe BP sync status flows
        viewModelScope.launch {
            combine(
                settingsRepository.bpSyncCountFlow,
                settingsRepository.bpSyncCaughtUpFlow,
                settingsRepository.lastBpSyncTimestampFlow,
            ) { count, caughtUp, ts ->
                bpSyncCount = count
                bpSyncCaughtUp = caughtUp
                lastBpSyncTs = ts
                formatSyncStatus(count, caughtUp, ts)
            }.collect { status ->
                _uiState.update { it.copy(bpSyncStatus = status) }
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
                Timber.d("SyncViewModel: config changed — configured=%b, interval=%d min", configured, interval)
                if (configured) {
                    syncScheduler.schedulePeriodic(interval)
                } else {
                    syncScheduler.cancelSync()
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

    private suspend fun loadLastBpReading() {
        try {
            val reading = getLastBpReadingUseCase.invoke()
            _uiState.update {
                it.copy(
                    lastBpReading = reading,
                    lastBpReadingDisplay = if (reading != null) "${reading.systolic}/${reading.diastolic} mmHg" else "",
                    lastBpReadingTime = if (reading != null) formatRelativeTime(reading.timestamp.toEpochMilli()) else "",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to load last BP reading")
            // Leave lastBpReading = null
        }
    }

    fun refreshLastBpReading() {
        viewModelScope.launch {
            loadLastBpReading()
        }
    }

    private suspend fun loadLastGlucoseReading() {
        try {
            val reading = getLastGlucoseReadingUseCase.invoke()
            _uiState.update {
                it.copy(
                    lastGlucoseReading = reading,
                    lastGlucoseReadingDisplay = if (reading != null) {
                        "${reading.valueMgDl} mg/dL (${"%.1f".format(reading.toMmolL())} mmol/L)"
                    } else {
                        ""
                    },
                    lastGlucoseReadingTime = if (reading != null) formatRelativeTime(reading.timestamp.toEpochMilli()) else "",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to load last glucose reading")
        }
    }

    fun refreshLastGlucoseReading() {
        viewModelScope.launch {
            loadLastGlucoseReading()
        }
    }

    private fun formatNextSyncTime(nextSyncMs: Long): String {
        val diffMs = nextSyncMs - System.currentTimeMillis()
        if (diffMs <= 0) return "Sync pending..."
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
        Timber.d("SyncViewModel: HC permission result=%b", granted)
        _uiState.update { it.copy(permissionGranted = granted) }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        Timber.d("SyncViewModel: camera permission result=%b", granted)
        _uiState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun cancelSync() {
        val job = syncJob ?: return
        if (!job.isActive) return
        Timber.d("SyncViewModel: cancelSync requested by user")
        job.cancel()
    }

    fun triggerSync() {
        if (_uiState.value.isSyncing) {
            Timber.d("SyncViewModel: triggerSync ignored, already syncing")
            return
        }
        Timber.d("SyncViewModel: triggerSync started, cancelling background worker")
        syncScheduler.cancelSync()
        syncJob?.cancel()
        _uiState.update { it.copy(isSyncing = true, syncProgress = null) }
        syncJob = viewModelScope.launch {
            try {
                val result = syncNutritionUseCase.invoke { progress ->
                    _uiState.update { it.copy(syncProgress = progress) }
                }
                val resultMessage = when (result) {
                    is SyncResult.Success -> {
                        val msg = when {
                            result.recordsSynced == 0 -> "No new meals"
                            result.daysProcessed == 1 -> "Synced ${result.recordsSynced} meals across 1 day"
                            else -> "Synced ${result.recordsSynced} meals across ${result.daysProcessed} days"
                        }
                        Timber.d("SyncViewModel: sync completed — %s", msg)
                        msg
                    }
                    is SyncResult.Error -> {
                        Timber.e("SyncViewModel: sync error — %s", result.message)
                        result.message
                    }
                    is SyncResult.NeedsConfiguration -> {
                        Timber.w("SyncViewModel: sync skipped — needs configuration")
                        "Please configure API settings"
                    }
                }
                _uiState.update { it.copy(lastSyncResult = resultMessage) }

                // Fire-and-forget health readings sync (same as SyncWorker)
                if (result !is SyncResult.NeedsConfiguration) {
                    try {
                        syncHealthReadingsUseCase()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "triggerSync: health readings sync failed (non-fatal)")
                    }
                }
            } catch (e: CancellationException) {
                Timber.d("SyncViewModel: triggerSync cancelled")
                _uiState.update { it.copy(lastSyncResult = "Sync cancelled") }
            } catch (e: Exception) {
                Timber.e(e, "triggerSync unexpected error")
                _uiState.update { it.copy(lastSyncResult = "Sync failed. Please try again.") }
            } finally {
                _uiState.update { it.copy(isSyncing = false, syncProgress = null) }
                // Reschedule background worker so the timer resets from now
                try {
                    val interval = settingsRepository.syncIntervalFlow.first()
                    syncScheduler.schedulePeriodic(interval)
                } catch (_: Exception) { }
            }
        }
    }
}

internal fun formatSyncStatus(count: Int, caughtUp: Boolean, timestampMs: Long): String =
    when {
        count == 0 && !caughtUp -> "Not synced to food-scanner"
        count == 0 && caughtUp -> "No readings to sync"
        else -> {
            val timeStr = formatRelativeTime(timestampMs)
            val label = if (count == 1) "reading" else "readings"
            if (timeStr.isNotEmpty()) {
                "Pushed $count $label · $timeStr"
            } else {
                "Pushed $count $label"
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
