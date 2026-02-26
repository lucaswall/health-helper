package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.HealthRecord
import java.time.Instant
import javax.inject.Inject

interface HealthConnectRepository {
    suspend fun readSteps(start: Instant, end: Instant): List<HealthRecord>
}

class HealthConnectRepositoryImpl @Inject constructor(
    private val client: HealthConnectClient,
) : HealthConnectRepository {

    override suspend fun readSteps(start: Instant, end: Instant): List<HealthRecord> {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
            response.records.map { record ->
                HealthRecord(
                    type = "Steps",
                    value = record.count.toDouble(),
                    startTime = record.startTime,
                    endTime = record.endTime,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
