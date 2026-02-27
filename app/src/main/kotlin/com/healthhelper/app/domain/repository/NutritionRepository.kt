package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.FoodLogEntry

interface NutritionRepository {
    suspend fun writeNutritionRecords(date: String, entries: List<FoodLogEntry>): Boolean
}
