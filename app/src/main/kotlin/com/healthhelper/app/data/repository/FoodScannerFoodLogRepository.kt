package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.repository.FoodLogRepository
import javax.inject.Inject

class FoodScannerFoodLogRepository @Inject constructor(
    private val apiClient: FoodScannerApiClient,
) : FoodLogRepository {

    override suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
    ): Result<List<FoodLogEntry>> = apiClient.getFoodLog(baseUrl, apiKey, date)
}
