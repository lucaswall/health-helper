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
    @param:ApplicationContext private val context: Context,
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
            Timber.w(e, "writeBloodPressureRecord: permission denied")
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
            // Newest first: the default ascending order would return the oldest page of the
            // 30-day window. Reads from all origins on purpose so a reading logged by another
            // app (e.g. a meter's companion app) still shows as the latest one.
            val response = withTimeout(10_000L) {
                healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = BloodPressureRecord::class,
                        timeRangeFilter = TimeRangeFilter.after(
                            Instant.now().minus(30, ChronoUnit.DAYS),
                        ),
                        ascendingOrder = false,
                        pageSize = LAST_READING_PAGE_SIZE,
                    ),
                )
            }
            Timber.d(
                "getLastReading: read %d BP records in %dms",
                response.records.size,
                System.currentTimeMillis() - startMs,
            )
            response.records
                .sortedByDescending { it.time }
                .firstNotNullOfOrNull { record ->
                    runCatching { mapToBloodPressureReading(record) }
                        .onFailure { Timber.w(it, "getLastReading: skipping unmappable BP record") }
                        .getOrNull()
                }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getLastReading: timed out after 10s")
            null
        } catch (e: SecurityException) {
            Timber.w(e, "getLastReading: BP permission denied")
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getLastReading: failed")
            null
        }
    }

    override suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading> =
        getReadingsResult(start, end).readings

    override suspend fun getReadingsResult(
        start: Instant,
        end: Instant,
        limit: Int,
    ): ReadingsResult<BloodPressureReading> {
        val client = healthConnectClient ?: run {
            Timber.w("getReadings: Health Connect not available")
            throw IllegalStateException("Health Connect not available")
        }
        // Only this app's own records are synced; other apps' BP data is not pushed.
        return client.readReadingsPaged(
            recordType = BloodPressureRecord::class,
            start = start,
            end = end,
            dataOriginFilter = setOf(DataOrigin(context.packageName)),
            limit = limit,
            label = "BP",
            map = ::mapToBloodPressureReading,
            timestamp = { it.timestamp },
        )
    }

    private companion object {
        const val LAST_READING_PAGE_SIZE = 20
    }
}
