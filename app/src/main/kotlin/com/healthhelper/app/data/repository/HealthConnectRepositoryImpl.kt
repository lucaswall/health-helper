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
import com.healthhelper.app.domain.model.StepsErrorType
import com.healthhelper.app.domain.model.StepsResult
import com.healthhelper.app.domain.repository.HealthConnectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTimedValue

class HealthConnectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statusProvider: HealthConnectStatusProvider,
) : HealthConnectRepository {

    override suspend fun readSteps(start: Instant, end: Instant): StepsResult {
        val status = statusProvider.getStatus()
        if (status != HealthConnectStatus.Available) {
            return StepsResult.Error(
                StepsErrorType.ServiceUnavailable,
                "Health Connect is not available: $status",
            )
        }
        val client = HealthConnectClient.getOrCreate(context)
        Timber.d("Reading steps from %s to %s", start, end)
        return try {
            val (response, duration) = measureTimedValue {
                withTimeout(30_000L) {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                        ),
                    )
                }
            }
            Timber.d("Loaded %d step records in %s", response.records.size, duration)
            StepsResult.Success(
                response.records.map { record ->
                    HealthRecord(
                        id = record.metadata.id,
                        type = HealthRecordType.Steps,
                        value = record.count.toDouble(),
                        startTime = record.startTime,
                        endTime = record.endTime,
                    )
                },
            )
        } catch (e: TimeoutCancellationException) {
            Timber.e(e, "Timeout reading steps")
            StepsResult.Error(StepsErrorType.Timeout, "Request timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied reading steps")
            StepsResult.Error(StepsErrorType.PermissionDenied, "Permission denied")
        } catch (e: java.io.IOException) {
            Timber.e(e, "IO error reading steps")
            StepsResult.Error(StepsErrorType.ServiceUnavailable, "Service temporarily unavailable")
        } catch (e: Exception) {
            Timber.e(e, "Error reading steps")
            StepsResult.Error(StepsErrorType.Unknown, "Failed to load steps")
        }
    }
}
