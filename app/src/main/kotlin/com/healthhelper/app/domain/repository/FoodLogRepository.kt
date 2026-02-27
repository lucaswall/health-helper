package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.FoodLogEntry

interface FoodLogRepository {
    suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
    ): Result<List<FoodLogEntry>>
}
