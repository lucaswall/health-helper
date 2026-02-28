package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import javax.inject.Inject

class SyncNutritionUseCase @Inject constructor(
    private val foodLogRepository: FoodLogRepository,
    private val nutritionRepository: NutritionRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun invoke(onProgress: (SyncProgress) -> Unit = {}): SyncResult {
        if (!settingsRepository.isConfigured()) {
            return SyncResult.NeedsConfiguration
        }

        val apiKey = settingsRepository.apiKeyFlow.first()
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val lastSyncedDate = settingsRepository.lastSyncedDateFlow.first()

        val today = LocalDate.now()
        val maxPastDate = today.minusDays(365)
        val startDate = parseSyncStartDate(lastSyncedDate, maxPastDate)

        // Build date list: today first (always re-sync), then backwards
        val dates = mutableListOf(today)
        var d = today.minusDays(1)
        while (!d.isBefore(startDate)) {
            dates.add(d)
            d = d.minusDays(1)
        }

        val totalDays = dates.size
        var completedDays = 0
        var totalRecordsSynced = 0
        var totalEntriesFetched = 0
        var successfulDays = 0
        // Track per-date success for contiguous lastSyncedDate calculation
        val pastDateResults = mutableMapOf<LocalDate, Boolean>()
        // Accumulate synced entries with their date for meal summary
        val syncedEntries = mutableListOf<Pair<String, FoodLogEntry>>()

        for (date in dates) {
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val result = foodLogRepository.getFoodLog(baseUrl, apiKey, dateStr)

            if (result.isSuccess) {
                val entries = result.getOrThrow()
                if (entries.isNotEmpty()) {
                    totalEntriesFetched += entries.size
                    val written = nutritionRepository.writeNutritionRecords(dateStr, entries)
                    if (written) {
                        totalRecordsSynced += entries.size
                        entries.forEach { entry -> syncedEntries.add(Pair(dateStr, entry)) }
                    }
                }
                successfulDays++
                if (date != today) {
                    pastDateResults[date] = true
                }
            } else {
                if (date != today) {
                    pastDateResults[date] = false
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

            // Rate limit: 500ms between API calls
            if (completedDays < totalDays) {
                delay(500)
            }
        }

        // Persist lastSyncedDate: find newest contiguous successful date from oldest end
        val pastDates = pastDateResults.keys.sorted() // oldest first
        var contiguousEnd: LocalDate? = null
        for (date in pastDates) {
            if (pastDateResults[date] == true) {
                contiguousEnd = date
            } else {
                break // gap found — stop advancing
            }
        }
        if (contiguousEnd != null) {
            settingsRepository.setLastSyncedDate(contiguousEnd.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        if (successfulDays > 0) {
            try {
                settingsRepository.setLastSyncTimestamp(System.currentTimeMillis())
            } catch (e: Exception) {
                // Do not break sync flow if timestamp save fails
            }
        }

        // Persist last 3 meals: sort by date desc, then time desc (null time sorts last)
        try {
            val summaries = syncedEntries
                .sortedWith(
                    compareByDescending<Pair<String, FoodLogEntry>> { it.first }
                        .thenByDescending { it.second.time ?: "" },
                )
                .take(3)
                .map { (_, entry) ->
                    SyncedMealSummary(
                        foodName = entry.foodName,
                        mealType = entry.mealType,
                        calories = entry.calories.roundToInt().coerceAtLeast(0),
                    )
                }
            settingsRepository.setLastSyncedMeals(summaries)
        } catch (e: Exception) {
            // Non-critical — don't fail sync on meal summary persistence error
        }

        return when {
            successfulDays == 0 -> SyncResult.Error("All sync attempts failed")
            totalEntriesFetched > 0 && totalRecordsSynced == 0 ->
                SyncResult.Error("Failed to write records to Health Connect")
            else -> SyncResult.Success(totalRecordsSynced, successfulDays)
        }
    }

    private fun parseSyncStartDate(lastSyncedDate: String, maxPastDate: LocalDate): LocalDate {
        if (lastSyncedDate.isEmpty()) return maxPastDate
        return try {
            val lastDate = LocalDate.parse(lastSyncedDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val nextDate = lastDate.plusDays(1)
            if (nextDate.isAfter(maxPastDate)) nextDate else maxPastDate
        } catch (e: DateTimeParseException) {
            // Corrupt date in DataStore — treat as unsynced and do a full re-sync from maxPastDate
            maxPastDate
        }
    }
}
