package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class HealthConnectBloodPressureRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : BloodPressureRepository {

    override suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean {
        if (healthConnectClient == null) {
            Timber.w("writeBloodPressureRecord: Health Connect not available")
            return false
        }
        return try {
            val startMs = System.currentTimeMillis()
            val record = mapToBloodPressureRecord(reading)
            withTimeout(10_000L) {
                healthConnectClient.insertRecords(listOf(record))
            }
            Timber.d(
                "writeBloodPressureRecord: wrote BP reading in %dms",
                System.currentTimeMillis() - startMs,
            )
            true
        } catch (e: TimeoutCancellationException) {
            Timber.e("writeBloodPressureRecord: timed out after 10s")
            false
        } catch (e: SecurityException) {
            Timber.e(e, "writeBloodPressureRecord: permission denied")
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "writeBloodPressureRecord: failed")
            false
        }
    }

    override suspend fun getLastReading(): BloodPressureReading? {
        if (healthConnectClient == null) {
            Timber.w("getLastReading: Health Connect not available")
            return null
        }
        return try {
            val startMs = System.currentTimeMillis()
            val response = withTimeout(10_000L) {
                healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = BloodPressureRecord::class,
                        timeRangeFilter = TimeRangeFilter.after(
                            Instant.now().minus(30, ChronoUnit.DAYS),
                        ),
                    ),
                )
            }
            Timber.d(
                "getLastReading: read %d records in %dms",
                response.records.size,
                System.currentTimeMillis() - startMs,
            )
            response.records.maxByOrNull { it.time }?.let { mapToBloodPressureReading(it) }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getLastReading: timed out after 10s")
            null
        } catch (e: SecurityException) {
            Timber.e(e, "getLastReading: permission denied")
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getLastReading: failed")
            null
        }
    }
}
