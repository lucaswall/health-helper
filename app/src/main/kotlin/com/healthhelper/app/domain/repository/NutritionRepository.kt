package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.FoodLogEntry

interface NutritionRepository {
    suspend fun writeNutritionRecords(date: String, entries: List<FoodLogEntry>): Boolean

    /**
     * Deletes this app's food-scanner nutrition records for [date] whose entry id is not in
     * [currentEntries] (entries deleted or moved on the server). Returns false if the existing
     * records could not be read or deleted; nothing is deleted when the read fails.
     */
    suspend fun deleteStaleNutritionRecords(date: String, currentEntries: List<FoodLogEntry>): Boolean
}
