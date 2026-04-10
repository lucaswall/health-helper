package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.HydrationReading
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

    override suspend fun getReadings(start: Instant, end: Instant): List<HydrationReading> {
        if (healthConnectClient == null) {
            Timber.w("getReadings: Health Connect not available")
            return emptyList()
        }
        val allRecords = mutableListOf<HydrationRecord>()
        var paginationTruncated = false
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
                    paginationTruncated = true
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
            if (!paginationTruncated) {
                Timber.d(
                    "getReadings: read %d hydration records in %dms",
                    allRecords.size,
                    System.currentTimeMillis() - startMs,
                )
            }
            allRecords
                .mapNotNull { record ->
                    runCatching { mapToHydrationReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map hydration record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getReadings: per-page timeout, returning %d partial hydration records", allRecords.size)
            allRecords
                .mapNotNull { record ->
                    runCatching { mapToHydrationReading(record) }
                        .onFailure { Timber.w(it, "getReadings: failed to map hydration record") }
                        .getOrNull()
                }
                .sortedBy { it.timestamp }
        } catch (e: SecurityException) {
            Timber.e(e, "getReadings: permission denied")
            emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getReadings: failed")
            emptyList()
        }
    }

    companion object {
        private const val CUMULATIVE_TIMEOUT_MS = 120_000L
    }
}
