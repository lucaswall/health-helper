package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.AuthenticationException
import com.healthhelper.app.data.api.RateLimitException
import com.healthhelper.app.data.api.ServerException
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber

class SyncHealthReadingsUseCase @Inject constructor(
    private val bloodGlucoseRepository: BloodGlucoseRepository,
    private val bloodPressureRepository: BloodPressureRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke() {
        if (!settingsRepository.isConfigured()) return

        val glucoseWatermark = syncType(
            getReadings = bloodGlucoseRepository::getReadings,
            pushReadings = foodScannerHealthRepository::pushGlucoseReadings,
            watermarkFlow = settingsRepository.lastGlucoseSyncTimestampFlow,
            setWatermark = settingsRepository::setLastGlucoseSyncTimestamp,
            countFlow = settingsRepository.glucoseSyncCountFlow,
            setCount = settingsRepository::setGlucoseSyncCount,
            setCaughtUp = settingsRepository::setGlucoseSyncCaughtUp,
            getLedger = settingsRepository::getDirectPushedGlucoseTimestamps,
            getTimestamp = { it.timestamp.toEpochMilli() },
        )

        val bpWatermark = syncType(
            getReadings = bloodPressureRepository::getReadings,
            pushReadings = foodScannerHealthRepository::pushBloodPressureReadings,
            watermarkFlow = settingsRepository.lastBpSyncTimestampFlow,
            setWatermark = settingsRepository::setLastBpSyncTimestamp,
            countFlow = settingsRepository.bpSyncCountFlow,
            setCount = settingsRepository::setBpSyncCount,
            setCaughtUp = settingsRepository::setBpSyncCaughtUp,
            getLedger = settingsRepository::getDirectPushedBpTimestamps,
            getTimestamp = { it.timestamp.toEpochMilli() },
        )

        settingsRepository.pruneDirectPushedTimestamps(glucoseWatermark, bpWatermark)
    }

    private suspend fun <T> syncType(
        getReadings: suspend (Instant, Instant) -> List<T>,
        pushReadings: suspend (List<T>) -> Result<Int>,
        watermarkFlow: Flow<Long>,
        setWatermark: suspend (Long) -> Unit,
        countFlow: Flow<Int>,
        setCount: suspend (Int) -> Unit,
        setCaughtUp: suspend (Boolean) -> Unit,
        getLedger: suspend () -> Set<Long>,
        getTimestamp: (T) -> Long,
    ): Long {
        val watermark = watermarkFlow.first()
        return try {
            val start = if (watermark == 0L) Instant.EPOCH else Instant.ofEpochMilli(watermark)
            val end = Instant.now()

            val readings = getReadings(start, end)
            val batch = readings.take(MAX_READINGS_PER_RUN)

            if (batch.isEmpty()) {
                setCaughtUp(true)
                return watermark
            }

            val ledger = getLedger()
            val toPublish = batch.filter { getTimestamp(it) !in ledger }
            val lastBatchTimestamp = getTimestamp(batch.last())
            val caughtUp = readings.size < MAX_READINGS_PER_RUN

            if (toPublish.isNotEmpty()) {
                val result = pushWithRetry { pushReadings(toPublish) }
                if (result.isFailure) return watermark
                val currentCount = countFlow.first()
                setCount(currentCount + (result.getOrNull() ?: toPublish.size))
            }

            setWatermark(lastBatchTimestamp)
            setCaughtUp(caughtUp)
            lastBatchTimestamp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "syncType failed, keeping watermark at %d", watermark)
            watermark
        }
    }

    private suspend fun pushWithRetry(push: suspend () -> Result<Int>): Result<Int> {
        var delayMs = INITIAL_RETRY_DELAY_MS
        var attempt = 0
        while (true) {
            val result = try {
                push()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (result.isSuccess) return result

            val ex = result.exceptionOrNull()
            val retryable = ex is RateLimitException || ex is ServerException || ex is IOException

            if (!retryable || attempt >= MAX_RETRIES) return result

            delay(delayMs)
            delayMs *= 2
            attempt++
        }
    }

    companion object {
        const val MAX_READINGS_PER_RUN = 100
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 500L
    }
}
