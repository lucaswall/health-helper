package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.repository.HydrationRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

class HealthConnectHydrationRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : HydrationRepository {

    override suspend fun getReadings(start: Instant, end: Instant): List<HydrationReading> =
        getReadingsResult(start, end).readings

    override suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<HydrationReading> {
        if (healthConnectClient == null) {
            Timber.w("getReadings: Health Connect not available")
            return ReadingsResult(emptyList())
        }
        val allRecords = mutableListOf<HydrationRecord>()
        var truncated = false
        return try {
            val startMs = System.currentTimeMillis()
            var pageToken: String? = null
            do {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed > CUMULATIVE_TIMEOUT_MS) {
                    Timber.w(
                        "getReadings: cumulative timeout (>%ds), returning %d partial hydration records",
                        CUMULATIVE_TIMEOUT_MS / 1000,
                        allRecords.size,
                    )
                    truncated = true
                    break
                }
                val response = withTimeout(10_000L) {
                    healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            recordType = HydrationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageToken = pageToken,
                        ),
                    )
                }
                allRecords.addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
            if (!truncated) {
                Timber.d(
                    "getReadings: read %d hydration records in %dms",
                    allRecords.size,
                    System.currentTimeMillis() - startMs,
                )
            }
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToHydrationReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map hydration record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
            ReadingsResult(readings, truncated)
        } catch (e: TimeoutCancellationException) {
            Timber.w("getReadings: per-page timeout, returning %d partial hydration records", allRecords.size)
            val readings = allRecords
                .mapNotNull { record ->
                    runCatching { mapToHydrationReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map hydration record") }
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
