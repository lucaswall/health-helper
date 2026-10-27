package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.FoodLogResult
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

class SyncNutritionUseCase internal constructor(
    private val foodLogRepository: FoodLogRepository,
    private val nutritionRepository: NutritionRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) {
    @Inject
    constructor(
        foodLogRepository: FoodLogRepository,
        nutritionRepository: NutritionRepository,
        settingsRepository: SettingsRepository,
    ) : this(foodLogRepository, nutritionRepository, settingsRepository, Clock.systemDefaultZone())

    suspend fun invoke(onProgress: (SyncProgress) -> Unit = {}): SyncResult {
        val apiKey: String
        val baseUrl: String
        val lastSyncedDate: String
        try {
            if (!settingsRepository.isConfigured()) {
                return SyncResult.NeedsConfiguration
            }
            apiKey = settingsRepository.apiKeyFlow.first()
            baseUrl = settingsRepository.baseUrlFlow.first()
            lastSyncedDate = settingsRepository.lastSyncedDateFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to read sync settings")
            return SyncResult.Error(e.message ?: "Failed to read settings")
        }

        val today = LocalDate.now(clock)
        val maxPastDate = today.minusDays(MAX_PAST_DAYS)
        // Always re-check a trailing window so meals added/edited/deleted on a past day after its
        // first fetch still reach Health Connect. Unchanged days are cheap 304s thanks to the
        // stored ETags (the window fits inside the ETag retention period).
        val startDate = minOf(
            parseSyncStartDate(lastSyncedDate, maxPastDate),
            today.minusDays(RECHECK_WINDOW_DAYS),
        )
        // Watermark: every past date up to and including it has been synced at least once.
        var watermark = parseLastSyncedDate(lastSyncedDate)

        // Today first (what the user cares about most), then past dates oldest → newest so the
        // watermark can be checkpointed after every day and an interrupted backfill resumes.
        val dates = mutableListOf(today)
        var d = startDate
        while (d.isBefore(today)) {
            dates.add(d)
            d = d.plusDays(1)
        }

        val totalDays = dates.size
        Timber.d(
            "SyncNutrition: starting sync of %d days (%s → %s), lastSyncedDate=%s",
            totalDays,
            startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            lastSyncedDate.ifEmpty { "(none)" },
        )
        var completedDays = 0
        var totalRecordsSynced = 0
        var totalEntriesFetched = 0
        var successfulDays = 0
        // Once an unsynced past date fails, the watermark must not advance past it
        var gapSeen = false
        // Accumulate synced entries with their date for meal summary
        val syncedEntries = mutableListOf<Pair<String, FoodLogEntry>>()
        // Consecutive failure tracking for abort/backoff
        var consecutiveFailures = 0
        var currentDelay = INITIAL_DELAY_MS
        var lastErrorMessage: String? = null

        for (date in dates) {
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val result = foodLogRepository.getFoodLog(baseUrl, apiKey, dateStr)

            val dayOk = if (result.isSuccess) {
                when (val foodLogResult = result.getOrThrow()) {
                    is FoodLogResult.Data -> {
                        val entries = foodLogResult.entries
                        val written = if (entries.isNotEmpty()) {
                            totalEntriesFetched += entries.size
                            nutritionRepository.writeNutritionRecords(dateStr, entries)
                        } else {
                            true
                        }
                        if (written) {
                            if (entries.isNotEmpty()) {
                                totalRecordsSynced += entries.size
                                entries.forEach { entry -> syncedEntries.add(Pair(dateStr, entry)) }
                                Timber.d("SyncNutrition: %s → wrote %d entries to HC", dateStr, entries.size)
                            } else {
                                Timber.d("SyncNutrition: %s → no entries from API", dateStr)
                            }
                            // Remove records for entries deleted/moved on the server. Best effort:
                            // a failure here does not fail the day (it would otherwise re-download
                            // and re-insert the day on every run if HC reads keep failing).
                            if (!nutritionRepository.deleteStaleNutritionRecords(dateStr, entries)) {
                                Timber.w("SyncNutrition: %s → failed to remove stale HC records", dateStr)
                            }
                            // Commit the ETag only now that the day is in Health Connect, so a
                            // failed write is re-fetched (not 304'd) on the next sync.
                            foodLogResult.etag?.let { commitETag(dateStr, it) }
                            true
                        } else {
                            lastErrorMessage = HC_WRITE_FAILED_MESSAGE
                            Timber.w("SyncNutrition: %s → HC write failed for %d entries", dateStr, entries.size)
                            false
                        }
                    }
                    is FoodLogResult.NotModified -> {
                        Timber.d("SyncNutrition: %s → ETag match, skipping HC write", dateStr)
                        true
                    }
                }
            } else {
                lastErrorMessage = result.exceptionOrNull()?.message
                Timber.w("SyncNutrition: %s → API error: %s", dateStr, lastErrorMessage)
                false
            }

            if (dayOk) {
                successfulDays++
                consecutiveFailures = 0
                currentDelay = INITIAL_DELAY_MS
            } else {
                consecutiveFailures++
            }

            // Checkpoint the watermark after each contiguous unsynced past date succeeds.
            // Re-checked dates at or before the watermark neither advance nor block it.
            val currentWatermark = watermark
            if (date != today && (currentWatermark == null || date.isAfter(currentWatermark))) {
                if (!dayOk) {
                    gapSeen = true
                } else if (!gapSeen) {
                    saveLastSyncedDate(dateStr)
                    watermark = date
                }
            }

            completedDays++
            onProgress(
                SyncProgress(
                    currentDate = dateStr,
                    totalDays = totalDays,
                    completedDays = completedDays,
                    recordsSynced = totalRecordsSynced,
                ),
            )

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Timber.w("SyncNutrition: aborting after %d consecutive failures", consecutiveFailures)
                break
            }

            // Rate limit with exponential backoff on consecutive failures
            if (completedDays < totalDays) {
                delay(currentDelay)
                if (consecutiveFailures > 0) {
                    currentDelay = min(currentDelay * 2, MAX_DELAY_MS)
                }
            }
        }

        val syncResult = when {
            successfulDays == 0 -> SyncResult.Error(lastErrorMessage ?: "Sync failed")
            totalEntriesFetched > 0 && totalRecordsSynced == 0 ->
                SyncResult.Error(HC_WRITE_FAILED_MESSAGE)
            else -> SyncResult.Success(totalRecordsSynced, successfulDays)
        }

        // "Last synced" reflects the last healthy sync, even when it brought no new meals
        if (syncResult is SyncResult.Success) {
            try {
                settingsRepository.setLastSyncTimestamp(clock.millis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to save last sync timestamp")
            }
        }

        // Persist last 3 meals: sort by date desc, then time desc (null time sorts last)
        // Only update if we have new entries — don't overwrite with empty list when all dates are NotModified
        if (syncedEntries.isNotEmpty()) {
            try {
                val summaries = syncedEntries
                    .sortedWith(
                        compareByDescending<Pair<String, FoodLogEntry>> { it.first }
                            .thenByDescending { it.second.time ?: "" },
                    )
                    .take(3)
                    .map { (dateStr, entry) ->
                        val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                        val time = try {
                            entry.time?.let { LocalTime.parse(it) } ?: LocalTime.NOON
                        } catch (_: DateTimeParseException) {
                            LocalTime.NOON
                        }
                        val timestamp = date.atTime(time).atZone(clock.zone).toInstant()
                        SyncedMealSummary(
                            foodName = entry.foodName,
                            mealType = entry.mealType,
                            calories = entry.calories.roundToInt().coerceAtLeast(0),
                            timestamp = timestamp,
                        )
                    }
                settingsRepository.setLastSyncedMeals(summaries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to persist synced meal summaries")
            }
        }

        Timber.d(
            "SyncNutrition: finished — %d/%d days ok, %d entries fetched, %d records written, result=%s",
            successfulDays,
            totalDays,
            totalEntriesFetched,
            totalRecordsSynced,
            syncResult::class.simpleName,
        )
        return syncResult
    }

    private suspend fun commitETag(date: String, etag: String) {
        try {
            settingsRepository.setETag(date, etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist ETag for %s, will re-fetch next sync", date)
        }
    }

    private suspend fun saveLastSyncedDate(date: String) {
        try {
            settingsRepository.setLastSyncedDate(date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to save lastSyncedDate %s", date)
        }
    }

    private fun parseLastSyncedDate(lastSyncedDate: String): LocalDate? {
        if (lastSyncedDate.isEmpty()) return null
        return try {
            LocalDate.parse(lastSyncedDate, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun parseSyncStartDate(lastSyncedDate: String, maxPastDate: LocalDate): LocalDate {
        // Corrupt or missing date in DataStore — treat as unsynced and do a full re-sync from maxPastDate
        val lastDate = parseLastSyncedDate(lastSyncedDate) ?: return maxPastDate
        val nextDate = lastDate.plusDays(1)
        return if (nextDate.isAfter(maxPastDate)) nextDate else maxPastDate
    }

    private companion object {
        const val MAX_PAST_DAYS = 365L
        const val RECHECK_WINDOW_DAYS = 7L
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val INITIAL_DELAY_MS = 500L
        const val MAX_DELAY_MS = 8000L
        const val HC_WRITE_FAILED_MESSAGE = "Failed to write records to Health Connect"
    }
}
