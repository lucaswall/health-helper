package com.healthhelper.app.data.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class HealthConnectBloodGlucoseRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
    @ApplicationContext private val context: Context,
) : BloodGlucoseRepository {

    override suspend fun writeBloodGlucoseRecord(reading: GlucoseReading): Boolean {
        if (healthConnectClient == null) {
            Timber.w("writeBloodGlucoseRecord: Health Connect not available")
            return false
        }
        return try {
            val startMs = System.currentTimeMillis()
            val record = mapToBloodGlucoseRecord(reading)
            withTimeout(10_000L) {
                healthConnectClient.insertRecords(listOf(record))
            }
            Timber.d(
                "writeBloodGlucoseRecord: wrote glucose reading in %dms",
                System.currentTimeMillis() - startMs,
            )
            true
        } catch (e: TimeoutCancellationException) {
            Timber.e("writeBloodGlucoseRecord: timed out after 10s")
            false
        } catch (e: SecurityException) {
            Timber.w(e, "writeBloodGlucoseRecord: permission denied")
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "writeBloodGlucoseRecord: failed")
            false
        }
    }

    override suspend fun getLastReading(): GlucoseReading? {
        if (healthConnectClient == null) {
            Timber.w("getLastReading: Health Connect not available")
            return null
        }
        return try {
            val startMs = System.currentTimeMillis()
            val response = withTimeout(10_000L) {
                healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = BloodGlucoseRecord::class,
                        timeRangeFilter = TimeRangeFilter.after(
                            Instant.now().minus(30, ChronoUnit.DAYS),
                        ),
                    ),
                )
            }
            Timber.d(
                "getLastReading: read %d glucose records in %dms",
                response.records.size,
                System.currentTimeMillis() - startMs,
            )
            response.records
                .sortedByDescending { it.time }
                .firstNotNullOfOrNull { record ->
                    runCatching { mapToGlucoseReading(record) }.getOrNull()
                }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getLastReading: timed out after 10s")
            null
        } catch (e: SecurityException) {
            Timber.w(e, "getLastReading: glucose permission denied")
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getLastReading: failed")
            null
        }
    }

    override suspend fun getReadings(start: Instant, end: Instant): List<GlucoseReading> =
        getReadingsResult(start, end).readings

    override suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<GlucoseReading> {
        if (healthConnectClient == null) {
            Timber.w("getReadings: Health Connect not available")
            return ReadingsResult(emptyList())
        }
        val allRecords = mutableListOf<BloodGlucoseRecord>()
        var truncated = false
        return try {
            val startMs = System.currentTimeMillis()
            var pageToken: String? = null
            do {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed > CUMULATIVE_TIMEOUT_MS) {
                    Timber.w(
                        "getReadings: cumulative timeout (>%ds), returning %d partial glucose records",
                        CUMULATIVE_TIMEOUT_MS / 1000,
                        allRecords.size,
                    )
                    truncated = true
                    break
                }
                val response = withTimeout(10_000L) {
                    healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            recordType = BloodGlucoseRecord::class,
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
                    "getReadings: read %d glucose records in %dms",
                    allRecords.size,
                    System.currentTimeMillis() - startMs,
                )
            }
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToGlucoseReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map glucose record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
            ReadingsResult(readings, truncated)
        } catch (e: TimeoutCancellationException) {
            Timber.w("getReadings: per-page timeout, returning %d partial glucose records", allRecords.size)
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToGlucoseReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map glucose record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
            ReadingsResult(readings, truncated = true)
        } catch (e: SecurityException) {
            Timber.w(e, "getReadings: glucose permission denied — rethrowing for use-case reporting")
            throw e
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
