package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.FoodLogEntry
import javax.inject.Inject

class FoodScannerApiClient @Inject constructor() {
    suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
    ): Result<List<FoodLogEntry>> {
        return Result.success(emptyList())
    }
}
