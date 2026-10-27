package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.ReadingsResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant

internal const val HC_PAGE_TIMEOUT_MS = 10_000L
internal const val HC_CUMULATIVE_TIMEOUT_MS = 120_000L
private const val HC_MAX_PAGE_SIZE = 1000

/**
 * Pages through [recordType] records in [start, end) (ascending by time), mapping each record with
 * [map] and dropping records that fail to map.
 *
 * Paging stops early once more than [limit] readings have been collected and the newest collected
 * reading's timestamp differs from the [limit]-th one, so every reading tied with the [limit]-th
 * timestamp is included. When paging stops early (limit reached, cumulative timeout or per-page
 * timeout) the result is marked truncated.
 *
 * Errors: [SecurityException] and any other non-timeout failure are logged and rethrown so callers
 * can distinguish "read failed" from "no records".
 */
internal suspend fun <R : Record, T> HealthConnectClient.readReadingsPaged(
    recordType: KClass<R>,
    start: Instant,
    end: Instant,
    dataOriginFilter: Set<DataOrigin>,
    limit: Int,
    label: String,
    map: (R) -> T,
    timestamp: (T) -> Instant,
): ReadingsResult<T> {
    require(limit > 0) { "limit must be positive, was $limit" }
    val readings = mutableListOf<T>()
    val pageSize = if (limit >= HC_MAX_PAGE_SIZE) HC_MAX_PAGE_SIZE else limit + 1
    return try {
        val startMs = System.currentTimeMillis()
        var truncated = false
        var pageToken: String? = null
        do {
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed > HC_CUMULATIVE_TIMEOUT_MS) {
                Timber.w(
                    "getReadings: cumulative timeout (>%ds), returning %d partial %s readings",
                    HC_CUMULATIVE_TIMEOUT_MS / 1000,
                    readings.size,
                    label,
                )
                truncated = true
                break
            }
            val response = withTimeout(HC_PAGE_TIMEOUT_MS) {
                readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        dataOriginFilter = dataOriginFilter,
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
            }
            response.records.forEach { record ->
                runCatching { map(record) }
                    .onSuccess { readings.add(it) }
                    .onFailure { Timber.w(it, "getReadings: failed to map %s record", label) }
            }
            pageToken = response.pageToken
            if (pageToken != null && hasEnough(readings, limit, timestamp)) {
                truncated = true
                break
            }
        } while (pageToken != null)
        Timber.d(
            "getReadings: read %d %s readings in %dms (truncated=%b)",
            readings.size,
            label,
            System.currentTimeMillis() - startMs,
            truncated,
        )
        ReadingsResult(readings.sortedBy(timestamp), truncated)
    } catch (e: TimeoutCancellationException) {
        Timber.w("getReadings: per-page timeout, returning %d partial %s readings", readings.size, label)
        ReadingsResult(readings.sortedBy(timestamp), truncated = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        Timber.w(e, "getReadings: %s permission denied — rethrowing for use-case reporting", label)
        throw e
    } catch (e: Exception) {
        Timber.e(e, "getReadings: %s read failed — rethrowing", label)
        throw e
    }
}

private fun <T> hasEnough(readings: List<T>, limit: Int, timestamp: (T) -> Instant): Boolean {
    if (readings.size <= limit) return false
    // Keep paging while the newest reading still ties with the limit-th one, so the caller can
    // include every reading sharing the boundary timestamp.
    return timestamp(readings.last()) != timestamp(readings[limit - 1])
}
