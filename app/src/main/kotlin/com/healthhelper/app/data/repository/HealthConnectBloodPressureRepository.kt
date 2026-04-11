package com.healthhelper.app.data.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.ReadingsResult
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
    @ApplicationContext private val context: Context,
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

    override suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading> =
        getReadingsResult(start, end).readings

    override suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<BloodPressureReading> {
        if (healthConnectClient == null) {
            Timber.w("getReadings: Health Connect not available")
            return ReadingsResult(emptyList())
        }
        val allRecords = mutableListOf<BloodPressureRecord>()
        var truncated = false
        return try {
            val startMs = System.currentTimeMillis()
            var pageToken: String? = null
            do {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed > CUMULATIVE_TIMEOUT_MS) {
                    Timber.w(
                        "getReadings: cumulative timeout (>%ds), returning %d partial BP records",
                        CUMULATIVE_TIMEOUT_MS / 1000,
                        allRecords.size,
                    )
                    truncated = true
                    break
                }
                val response = withTimeout(10_000L) {
                    healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            recordType = BloodPressureRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            dataOriginFilter = setOf(DataOrigin(context.packageName)),
                            pageToken = pageToken,
                        ),
                    )
                }
                allRecords.addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
            if (!truncated) {
                Timber.d(
                    "getReadings: read %d BP records in %dms",
                    allRecords.size,
                    System.currentTimeMillis() - startMs,
                )
            }
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToBloodPressureReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map BP record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
            ReadingsResult(readings, truncated)
        } catch (e: TimeoutCancellationException) {
            Timber.w("getReadings: per-page timeout, returning %d partial BP records", allRecords.size)
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToBloodPressureReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map BP record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
            ReadingsResult(readings, truncated = true)
        } catch (e: SecurityException) {
            Timber.e(e, "getReadings: permission denied")
            ReadingsResult(emptyList())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getReadings: failed")
            ReadingsResult(emptyList())
        }
    }

    companion object {
        private const val CUMULATIVE_TIMEOUT_MS = 120_000L
    }
}
