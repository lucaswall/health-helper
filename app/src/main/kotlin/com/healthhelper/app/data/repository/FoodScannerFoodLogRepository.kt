package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.FoodLogResult
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import timber.log.Timber
import javax.inject.Inject

class FoodScannerFoodLogRepository @Inject constructor(
    private val apiClient: FoodScannerApiClient,
    private val settingsRepository: SettingsRepository,
) : FoodLogRepository {

    override suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
    ): Result<FoodLogResult> {
        val etag = try {
            settingsRepository.getETag(date)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ETag for %s, proceeding without", date)
            null
        }
        Timber.d("FoodLogRepo: fetching %s, etag=%s", date, etag ?: "(none)")
        val result = apiClient.getFoodLog(baseUrl, apiKey, date, etag)
        return result.map { apiResponse ->
            if (apiResponse.notModified) {
                Timber.d("FoodLogRepo: %s → 304 Not Modified", date)
                FoodLogResult.NotModified
            } else {
                Timber.d(
                    "FoodLogRepo: %s → %d entries, newEtag=%s",
                    date,
                    apiResponse.entries.size,
                    apiResponse.etag ?: "(none)",
                )
                if (apiResponse.etag != null) {
                    try {
                        settingsRepository.setETag(date, apiResponse.etag)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to persist ETag for %s, will re-fetch next sync", date)
                    }
                }
                FoodLogResult.Data(apiResponse.entries)
            }
        }
    }
}
