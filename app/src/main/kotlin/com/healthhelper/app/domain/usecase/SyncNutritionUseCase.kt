package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class SyncNutritionUseCase @Inject constructor(
    private val apiClient: FoodScannerApiClient,
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
        val startDate = if (lastSyncedDate.isNotEmpty()) {
            val lastDate = LocalDate.parse(lastSyncedDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val nextDate = lastDate.plusDays(1)
            if (nextDate.isAfter(maxPastDate)) nextDate else maxPastDate
        } else {
            maxPastDate
        }

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

        for (date in dates) {
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val result = apiClient.getFoodLog(baseUrl, apiKey, dateStr)

            if (result.isSuccess) {
                val entries = result.getOrThrow()
                if (entries.isNotEmpty()) {
                    totalEntriesFetched += entries.size
                    val written = nutritionRepository.writeNutritionRecords(dateStr, entries)
                    if (written) {
                        totalRecordsSynced += entries.size
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

        return when {
            successfulDays == 0 -> SyncResult.Error("All sync attempts failed")
            totalEntriesFetched > 0 && totalRecordsSynced == 0 ->
                SyncResult.Error("Failed to write records to Health Connect")
            else -> SyncResult.Success(totalRecordsSynced)
        }
    }
}
