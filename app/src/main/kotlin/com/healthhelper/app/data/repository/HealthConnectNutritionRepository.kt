package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.repository.NutritionRepository
import javax.inject.Inject

class HealthConnectNutritionRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient,
) : NutritionRepository {
    override suspend fun writeNutritionRecords(date: String, entries: List<FoodLogEntry>): Boolean = false
}
