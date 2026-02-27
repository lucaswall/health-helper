package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.repository.NutritionRepository
import timber.log.Timber
import javax.inject.Inject

class HealthConnectNutritionRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient,
) : NutritionRepository {

    override suspend fun writeNutritionRecords(
        date: String,
        entries: List<FoodLogEntry>,
    ): Boolean = try {
        val records = entries.map { mapToNutritionRecord(it, date) }
        healthConnectClient.insertRecords(records)
        Timber.d("writeNutritionRecords(%s): wrote %d records", date, records.size)
        true
    } catch (e: SecurityException) {
        Timber.e(e, "writeNutritionRecords(%s): permission denied", date)
        false
    } catch (e: Exception) {
        Timber.e(e, "writeNutritionRecords(%s): failed", date)
        false
    }
}
