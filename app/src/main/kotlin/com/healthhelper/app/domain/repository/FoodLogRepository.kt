package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.FoodLogResult

interface FoodLogRepository {
    /**
     * Fetches the food log for [date], sending the stored ETag (if any). A fresh response's
     * ETag is returned in [FoodLogResult.Data.etag] and is not persisted here.
     */
    suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
    ): Result<FoodLogResult>
}
