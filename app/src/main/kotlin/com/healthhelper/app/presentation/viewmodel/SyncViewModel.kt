package com.healthhelper.app.presentation.viewmodel

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthhelper.app.data.sync.SyncCoordinator
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.HealthPermissions
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.HealthPermissionChecker
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.GetLastBloodPressureReadingUseCase
import com.healthhelper.app.domain.usecase.GetLastGlucoseReadingUseCase
import com.healthhelper.app.domain.usecase.GetTodayHydrationTotalUseCase
import com.healthhelper.app.domain.usecase.TodayHydrationResult
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
import timber.log.Timber
import javax.inject.Inject

data class SyncUiState(
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,
    val lastSyncResultIsError: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val isConfigured: Boolean = false,
    val lastSyncedDate: String = "",
    val lastSyncTime: String = "",
    val healthConnectAvailable: Boolean = false,
    /** True once a Health Connect grant query has succeeded at least once. */
    val permissionsChecked: Boolean = false,
    val missingHealthPermissions: Set<String> = emptySet(),
    val permissionLockoutSuspected: Boolean = false,
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
    val hydrationTodayDisplay: String = "",
    val hydrationSyncStatus: String = "",
    val hydrationHistoryStatus: String = "",
    val hydrationReadPermissionMissing: Boolean = false,
) {
    /** Missing permissions that block foreground actions (sync, BP/glucose logging). */
    val missingCorePermissions: Set<String>
        get() = missingHealthPermissions intersect HealthPermissions.CORE

    /** Missing permissions that only degrade background behaviour; shown as a non-blocking hint. */
    val missingOptionalPermissions: Set<String>
        get() = missingHealthPermissions - HealthPermissions.CORE

    val permissionGranted: Boolean
        get() = healthConnectAvailable && permissionsChecked && missingCorePermissions.isEmpty()
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val syncHealthReadingsUseCase: SyncHealthReadingsUseCase,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val getLastBpReadingUseCase: GetLastBloodPressureReadingUseCase,
    private val getLastGlucoseReadingUseCase: GetLastGlucoseReadingUseCase,
    private val getTodayHydrationTotalUseCase: GetTodayHydrationTotalUseCase,
    private val permissionChecker: HealthPermissionChecker,
    healthConnectClient: HealthConnectClient?,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val healthConnectAvailable: Boolean = healthConnectClient != null

    /** Wall-clock source for relative-time strings; overridable in tests. */
    internal var currentTimeMillis: () -> Long = System::currentTimeMillis

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null
    private var hydrationLoadJob: Job? = null
    private var tickerJob: Job? = null
    private var lastSyncTimestamp = 0L
    private var lastGlucoseSyncTs = 0L
    private var lastBpSyncTs = 0L
    private var glucoseSyncCount = 0
    private var bpSyncCount = 0
    private var glucoseSyncCaughtUp = false
    private var bpSyncCaughtUp = false
    private var lastHydrationSyncTs = 0L
    private var hydrationSyncCount = 0
    private var hydrationSyncCaughtUp = false
    private var nextSyncMs: Long? = null
    private var nextSyncConfigured = false
    private var hydrationTotalDate: LocalDate? = null

    init {
        Timber.d("SyncViewModel: init, healthConnectAvailable=%b", healthConnectAvailable)
        _uiState.update {
            it.copy(healthConnectAvailable = healthConnectAvailable)
        }

        if (healthConnectAvailable) {
            refreshPermissions()
        }

        // Load last BP, glucose, and hydration readings on launch
        viewModelScope.launch {
            loadLastBpReading()
        }
        viewModelScope.launch {
            loadLastGlucoseReading()
        }
        loadTodayHydration()

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
                _uiState.update { it.copy(lastSyncTime = formatRelativeTime(ts, currentTimeMillis())) }
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
                settingsRepository.glucoseSyncRunTimestampFlow,
            ) { count, caughtUp, ts ->
                glucoseSyncCount = count
                glucoseSyncCaughtUp = caughtUp
                lastGlucoseSyncTs = ts
                formatSyncStatus(count, caughtUp, ts, currentTimeMillis())
            }.collect { status ->
                _uiState.update { it.copy(glucoseSyncStatus = status) }
            }
        }

        // Observe BP sync status flows
        viewModelScope.launch {
            combine(
                settingsRepository.bpSyncCountFlow,
                settingsRepository.bpSyncCaughtUpFlow,
                settingsRepository.bpSyncRunTimestampFlow,
            ) { count, caughtUp, ts ->
                bpSyncCount = count
                bpSyncCaughtUp = caughtUp
                lastBpSyncTs = ts
                formatSyncStatus(count, caughtUp, ts, currentTimeMillis())
            }.collect { status ->
                _uiState.update { it.copy(bpSyncStatus = status) }
            }
        }

        // Observe hydration sync status flows
        viewModelScope.launch {
            combine(
                settingsRepository.hydrationSyncCountFlow,
                settingsRepository.hydrationSyncCaughtUpFlow,
                settingsRepository.hydrationSyncRunTimestampFlow,
            ) { count, caughtUp, ts ->
                hydrationSyncCount = count
                hydrationSyncCaughtUp = caughtUp
                lastHydrationSyncTs = ts
                formatSyncStatus(count, caughtUp, ts, currentTimeMillis())
            }.collect { status ->
                _uiState.update { it.copy(hydrationSyncStatus = status) }
            }
        }

        // Observe hydration history status
        viewModelScope.launch {
            combine(
                settingsRepository.lastHydrationSyncTimestampFlow,
                settingsRepository.hydrationSyncCaughtUpFlow,
                settingsRepository.hydrationSyncRunTimestampFlow,
            ) { watermark, caughtUp, runTs ->
                formatHydrationHistoryStatus(watermark, caughtUp, runTs, currentTimeMillis())
            }.collect { status ->
                _uiState.update { it.copy(hydrationHistoryStatus = status) }
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
            ) { nextMs, apiKey, baseUrl ->
                Pair(nextMs, apiKey.isNotEmpty() && baseUrl.isNotEmpty())
            }.collect { (nextMs, configured) ->
                nextSyncMs = nextMs
                nextSyncConfigured = configured
                _uiState.update { it.copy(nextSyncTime = currentNextSyncText()) }
            }
        }
    }

    /**
     * Starts the 30s relative-time refresh. Call when the screen becomes visible (ON_START);
     * pair with [onStop] so nothing ticks while the app is backgrounded.
     */
    fun onStart() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            refreshRelativeTimes()
            while (isActive) {
                delay(30_000)
                refreshRelativeTimes()
            }
        }
    }

    fun onStop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun refreshRelativeTimes() {
        val now = currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                lastSyncTime = if (lastSyncTimestamp > 0) {
                    formatRelativeTime(lastSyncTimestamp, now)
                } else {
                    state.lastSyncTime
                },
                lastBpReadingTime = state.lastBpReading?.let {
                    formatRelativeTime(it.timestamp.toEpochMilli(), now)
                } ?: state.lastBpReadingTime,
                lastGlucoseReadingTime = state.lastGlucoseReading?.let {
                    formatRelativeTime(it.timestamp.toEpochMilli(), now)
                } ?: state.lastGlucoseReadingTime,
                hydrationSyncStatus = if (lastHydrationSyncTs > 0) {
                    formatSyncStatus(hydrationSyncCount, hydrationSyncCaughtUp, lastHydrationSyncTs, now)
                } else {
                    state.hydrationSyncStatus
                },
                glucoseSyncStatus = if (lastGlucoseSyncTs > 0) {
                    formatSyncStatus(glucoseSyncCount, glucoseSyncCaughtUp, lastGlucoseSyncTs, now)
                } else {
                    state.glucoseSyncStatus
                },
                bpSyncStatus = if (lastBpSyncTs > 0) {
                    formatSyncStatus(bpSyncCount, bpSyncCaughtUp, lastBpSyncTs, now)
                } else {
                    state.bpSyncStatus
                },
                nextSyncTime = currentNextSyncText(),
            )
        }
    }

    private fun currentNextSyncText(): String {
        val next = nextSyncMs
        return when {
            !nextSyncConfigured -> ""
            next == null -> "Sync pending..."
            else -> formatNextSyncTime(next, currentTimeMillis())
        }
    }

    private suspend fun loadLastBpReading() {
        try {
            val reading = getLastBpReadingUseCase.invoke()
            _uiState.update {
                it.copy(
                    lastBpReading = reading,
                    lastBpReadingDisplay = if (reading != null) "${reading.systolic}/${reading.diastolic} mmHg" else "",
                    lastBpReadingTime = if (reading != null) {
                        formatRelativeTime(reading.timestamp.toEpochMilli(), currentTimeMillis())
                    } else {
                        ""
                    },
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
                    lastGlucoseReadingTime = if (reading != null) {
                        formatRelativeTime(reading.timestamp.toEpochMilli(), currentTimeMillis())
                    } else {
                        ""
                    },
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

    private fun today(): LocalDate =
        Instant.ofEpochMilli(currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun loadTodayHydration() {
        hydrationLoadJob?.cancel()
        hydrationLoadJob = viewModelScope.launch {
            try {
                val requestDate = today()
                when (val result = getTodayHydrationTotalUseCase.invoke()) {
                    is TodayHydrationResult.Total -> {
                        hydrationTotalDate = requestDate
                        _uiState.update {
                            it.copy(
                                hydrationTodayDisplay = if (result.volumeMl > 0) {
                                    "${NumberFormat.getIntegerInstance().format(result.volumeMl)} mL"
                                } else {
                                    ""
                                },
                                hydrationReadPermissionMissing = false,
                            )
                        }
                    }
                    TodayHydrationResult.PermissionDenied -> {
                        hydrationTotalDate = null
                        _uiState.update {
                            it.copy(
                                hydrationTodayDisplay = "",
                                hydrationReadPermissionMissing = true,
                            )
                        }
                    }
                    TodayHydrationResult.Unavailable -> {
                        // Preserve the last-known good total only while it still belongs to
                        // today; after midnight it would be mislabelled as "today".
                        val cachedIsToday = hydrationTotalDate == requestDate
                        if (!cachedIsToday) hydrationTotalDate = null
                        _uiState.update {
                            it.copy(
                                hydrationTodayDisplay = if (cachedIsToday) it.hydrationTodayDisplay else "",
                                // Unavailable carries no evidence of denial.
                                hydrationReadPermissionMissing = false,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load today hydration total")
            }
        }
    }

    fun refreshTodayHydration() {
        loadTodayHydration()
    }

    /**
     * Queries Health Connect for permissions the app can request but doesn't hold.
     * Returns `null` when the grant state is unknown (query failed) — callers must keep
     * their previous state instead of treating that as "everything missing".
     */
    private suspend fun queryMissingPermissions(): Set<String>? = try {
        val granted = permissionChecker.getGrantedPermissions()
        if (granted == null) {
            null
        } else {
            permissionChecker.getRequestablePermissions() - granted
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "SyncViewModel: permission query failed")
        null
    }

    fun refreshPermissions() {
        if (!healthConnectAvailable) return
        viewModelScope.launch {
            val missing = queryMissingPermissions()
            if (missing == null) {
                Timber.w("SyncViewModel: refreshPermissions — grant state unknown, keeping previous state")
            } else {
                Timber.d("SyncViewModel: refreshPermissions — missing=%d", missing.size)
                _uiState.update {
                    it.copy(
                        permissionsChecked = true,
                        missingHealthPermissions = missing,
                        // Clear the lockout flag once every permission is granted; otherwise keep
                        // it — it's re-evaluated after each permission request returns.
                        permissionLockoutSuspected = if (missing.isEmpty()) false else it.permissionLockoutSuspected,
                    )
                }
            }
            loadTodayHydration()
        }
    }

    /**
     * Called when the Health Connect permission sheet returns. The sheet only reports grants
     * from the [requested] subset, so re-query the full grant state instead of trusting it.
     */
    fun onPermissionRequestReturned(requested: Set<String>) {
        if (!healthConnectAvailable) return
        viewModelScope.launch {
            val missing = queryMissingPermissions()
            if (missing == null) {
                Timber.w("SyncViewModel: permission request returned but grant state unknown")
            } else {
                val stillMissingFromRequest = missing intersect requested
                Timber.d(
                    "SyncViewModel: HC permission request returned — requested=%d, stillMissing=%d",
                    requested.size,
                    stillMissingFromRequest.size,
                )
                _uiState.update {
                    it.copy(
                        permissionsChecked = true,
                        missingHealthPermissions = missing,
                        // If the user saw the request sheet and came back still missing perms,
                        // assume HC's 2-denial lockout may be in effect. The UI offers a settings fallback.
                        permissionLockoutSuspected = stillMissingFromRequest.isNotEmpty(),
                    )
                }
            }
            loadTodayHydration()
        }
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
        Timber.d("SyncViewModel: triggerSync started")
        syncJob?.cancel()
        _uiState.update { it.copy(isSyncing = true, syncProgress = null) }
        syncJob = viewModelScope.launch {
            // Never run concurrently with the periodic SyncWorker (duplicate HC quota, watermark regressions).
            if (!syncCoordinator.tryAcquire()) {
                Timber.d("SyncViewModel: triggerSync skipped, background sync already running")
                _uiState.update {
                    it.copy(isSyncing = false, syncProgress = null, lastSyncResult = "Sync already running")
                }
                return@launch
            }
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
                _uiState.update {
                    it.copy(
                        lastSyncResult = resultMessage,
                        lastSyncResultIsError = result is SyncResult.Error,
                    )
                }

                // Health readings sync (same as SyncWorker)
                if (result !is SyncResult.NeedsConfiguration) {
                    try {
                        syncHealthReadingsUseCase()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "triggerSync: health readings sync failed (non-fatal)")
                    }
                    // Hydration may have changed as part of the readings sync.
                    loadTodayHydration()
                }
            } catch (e: CancellationException) {
                Timber.d("SyncViewModel: triggerSync cancelled")
                _uiState.update { it.copy(lastSyncResult = "Sync cancelled", lastSyncResultIsError = false) }
            } catch (e: Exception) {
                Timber.e(e, "triggerSync unexpected error")
                _uiState.update {
                    it.copy(lastSyncResult = "Sync failed. Please try again.", lastSyncResultIsError = true)
                }
            } finally {
                syncCoordinator.release()
                _uiState.update { it.copy(isSyncing = false, syncProgress = null) }
            }
        }
    }
}

internal const val SYNC_STATUS_NEVER_SYNCED = "Not synced to food-scanner"

private fun pluralize(count: Long, unit: String): String =
    if (count == 1L) "1 $unit" else "$count ${unit}s"

internal fun formatNextSyncTime(nextSyncMs: Long, now: Long = System.currentTimeMillis()): String {
    val diffMs = nextSyncMs - now
    if (diffMs <= 0) return "Sync pending..."
    val diffMinutes = diffMs / 60_000L
    return when {
        diffMinutes == 0L -> "Next sync in <1m"
        diffMinutes < 60 -> "Next sync in ~${diffMinutes}m"
        else -> {
            val localTime = Instant.ofEpochMilli(nextSyncMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            "Next sync at ${localTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
        }
    }
}

internal fun formatSyncStatus(
    count: Int,
    caughtUp: Boolean,
    runTimestampMs: Long,
    now: Long = System.currentTimeMillis(),
): String {
    if (runTimestampMs == 0L) return SYNC_STATUS_NEVER_SYNCED
    val timeStr = formatRelativeTime(runTimestampMs, now)
    return when {
        count == 0 && caughtUp -> "Up to date · $timeStr"
        count == 0 -> "Pushed 0 readings · $timeStr"
        else -> {
            val label = if (count == 1) "reading" else "readings"
            "Pushed $count $label · $timeStr"
        }
    }
}

internal fun formatHistoryAge(timestampMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (timestampMillis <= 0L) return ""
    val diffMs = now - timestampMillis
    if (diffMs < 0) return "just now"
    val diffDays = diffMs / (24 * 60 * 60 * 1000L)
    return when {
        diffDays == 0L -> "today"
        diffDays < 7 -> "${pluralize(diffDays, "day")} ago"
        diffDays < 30 -> "${pluralize(diffDays / 7, "week")} ago"
        diffDays < 365 -> "${pluralize(diffDays / 30, "month")} ago"
        else -> "${pluralize(diffDays / 365, "year")} ago"
    }
}

internal fun formatHydrationHistoryStatus(
    watermark: Long,
    caughtUp: Boolean,
    runTimestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    if (runTimestamp == 0L) return ""
    if (caughtUp) return "History: up to date"
    if (watermark == 0L) return "History: starting..."
    return "History: synced to ${formatHistoryAge(watermark, now)}"
}

internal fun formatRelativeTime(timestampMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (timestampMillis <= 0L) return ""
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
        diffDays < 7 -> "${pluralize(diffDays, "day")} ago"
        else -> {
            val instant = Instant.ofEpochMilli(timestampMillis)
            val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            date.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }
}
