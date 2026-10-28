package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.domain.model.HeartRateReading
import com.healthhelper.app.domain.repository.HeartRateRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

class HealthConnectHeartRateRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : HeartRateRepository {

    override suspend fun writeHeartRateRecord(reading: HeartRateReading): Boolean {
        if (healthConnectClient == null) {
            Timber.w("writeHeartRateRecord: Health Connect not available")
            return false
        }
        return try {
            val startMs = System.currentTimeMillis()
            val record = mapToHeartRateRecord(reading)
            withTimeout(10_000L) {
                healthConnectClient.insertRecords(listOf(record))
            }
            Timber.d(
                "writeHeartRateRecord: wrote heart rate in %dms",
                System.currentTimeMillis() - startMs,
            )
            true
        } catch (e: TimeoutCancellationException) {
            Timber.e("writeHeartRateRecord: timed out after 10s")
            false
        } catch (e: SecurityException) {
            Timber.w(e, "writeHeartRateRecord: permission denied")
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "writeHeartRateRecord: failed")
            false
        }
    }
}
