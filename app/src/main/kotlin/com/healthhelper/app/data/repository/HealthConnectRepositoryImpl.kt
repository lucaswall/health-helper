package com.healthhelper.app.data.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.data.HealthConnectStatusProvider
import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.HealthRecordType
import com.healthhelper.app.domain.repository.HealthConnectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

class HealthConnectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statusProvider: HealthConnectStatusProvider,
) : HealthConnectRepository {

    override suspend fun readSteps(start: Instant, end: Instant): List<HealthRecord> {
        val status = statusProvider.getStatus()
        if (status != HealthConnectStatus.Available) {
            throw IllegalStateException("Health Connect is not available: $status")
        }
        val client = HealthConnectClient.getOrCreate(context)
        Timber.d("Reading steps from %s to %s", start, end)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        Timber.d("Loaded %d step records", response.records.size)
        return response.records.map { record ->
            HealthRecord(
                id = record.metadata.id,
                type = HealthRecordType.Steps,
                value = record.count.toDouble(),
                startTime = record.startTime,
                endTime = record.endTime,
            )
        }
    }
}
