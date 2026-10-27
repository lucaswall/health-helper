package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.repository.HydrationRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
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

    override suspend fun getReadingsResult(
        start: Instant,
        end: Instant,
        limit: Int,
    ): ReadingsResult<HydrationReading> {
        val client = healthConnectClient ?: run {
            Timber.w("getReadings: Health Connect not available")
            throw IllegalStateException("Health Connect not available")
        }
        // Hydration is read from every data origin (other apps log water intake).
        return client.readReadingsPaged(
            recordType = HydrationRecord::class,
            start = start,
            end = end,
            dataOriginFilter = emptySet(),
            limit = limit,
            label = "hydration",
            map = ::mapToHydrationReading,
            timestamp = { it.timestamp },
        )
    }

    override suspend fun getTotalVolumeMl(start: Instant, end: Instant): Int? {
        val client = healthConnectClient
        if (client == null) {
            Timber.w("getTotalVolumeMl: Health Connect not available")
            return null
        }
        return try {
            val result = withTimeout(HC_PAGE_TIMEOUT_MS) {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(HydrationRecord.VOLUME_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                    ),
                )
            }
            result[HydrationRecord.VOLUME_TOTAL]?.inMilliliters?.roundToInt() ?: 0
        } catch (e: TimeoutCancellationException) {
            Timber.w("getTotalVolumeMl: timed out after %dms", HC_PAGE_TIMEOUT_MS)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.w(e, "getTotalVolumeMl: hydration permission denied")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "getTotalVolumeMl: failed")
            null
        }
    }
}
