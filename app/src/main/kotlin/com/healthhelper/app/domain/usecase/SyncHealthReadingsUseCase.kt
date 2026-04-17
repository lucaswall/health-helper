package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.RateLimitException
import com.healthhelper.app.data.api.ServerException
import com.healthhelper.app.domain.model.HealthReadingsSyncReport
import com.healthhelper.app.domain.model.HealthSyncType
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.model.readPermission
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HydrationRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import java.io.IOException
import java.time.Duration
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
    private val hydrationRepository: HydrationRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(): HealthReadingsSyncReport {
        if (!settingsRepository.isConfigured()) return HealthReadingsSyncReport()

        val missing = mutableSetOf<String>()
        val skipped = mutableSetOf<HealthSyncType>()

        val glucoseWatermark = syncType(
            type = HealthSyncType.GLUCOSE,
            missingSink = missing,
            skippedSink = skipped,
            getReadings = bloodGlucoseRepository::getReadingsResult,
            pushReadings = foodScannerHealthRepository::pushGlucoseReadings,
            watermarkFlow = settingsRepository.lastGlucoseSyncTimestampFlow,
            setWatermark = settingsRepository::setLastGlucoseSyncTimestamp,
            setCount = settingsRepository::setGlucoseSyncCount,
            setCaughtUp = settingsRepository::setGlucoseSyncCaughtUp,
            setRunTimestamp = settingsRepository::setGlucoseSyncRunTimestamp,
            getLedger = settingsRepository::getDirectPushedGlucoseTimestamps,
            getTimestamp = { it.timestamp.toEpochMilli() },
        )

        val bpWatermark = syncType(
            type = HealthSyncType.BLOOD_PRESSURE,
            missingSink = missing,
            skippedSink = skipped,
            getReadings = bloodPressureRepository::getReadingsResult,
            pushReadings = foodScannerHealthRepository::pushBloodPressureReadings,
            watermarkFlow = settingsRepository.lastBpSyncTimestampFlow,
            setWatermark = settingsRepository::setLastBpSyncTimestamp,
            setCount = settingsRepository::setBpSyncCount,
            setCaughtUp = settingsRepository::setBpSyncCaughtUp,
            setRunTimestamp = settingsRepository::setBpSyncRunTimestamp,
            getLedger = settingsRepository::getDirectPushedBpTimestamps,
            getTimestamp = { it.timestamp.toEpochMilli() },
        )

        settingsRepository.resetHydrationWatermarkIfNeeded()

        syncType(
            type = HealthSyncType.HYDRATION,
            missingSink = missing,
            skippedSink = skipped,
            getReadings = hydrationRepository::getReadingsResult,
            pushReadings = foodScannerHealthRepository::pushHydrationReadings,
            watermarkFlow = settingsRepository.lastHydrationSyncTimestampFlow,
            setWatermark = settingsRepository::setLastHydrationSyncTimestamp,
            setCount = settingsRepository::setHydrationSyncCount,
            setCaughtUp = settingsRepository::setHydrationSyncCaughtUp,
            setRunTimestamp = settingsRepository::setHydrationSyncRunTimestamp,
            getLedger = { emptySet() },
            getTimestamp = { it.timestamp.toEpochMilli() },
            firstRunStart = Instant.now().minus(Duration.ofDays(FIRST_RUN_LOOKBACK_DAYS)),
        )

        settingsRepository.pruneDirectPushedTimestamps(glucoseWatermark, bpWatermark)

        return HealthReadingsSyncReport(
            missingReadPermissions = missing.toSet(),
            skippedTypes = skipped.toSet(),
        )
    }

    private suspend fun <T> syncType(
        type: HealthSyncType,
        missingSink: MutableSet<String>,
        skippedSink: MutableSet<HealthSyncType>,
        getReadings: suspend (Instant, Instant) -> ReadingsResult<T>,
        pushReadings: suspend (List<T>) -> Result<Int>,
        watermarkFlow: Flow<Long>,
        setWatermark: suspend (Long) -> Unit,
        setCount: suspend (Int) -> Unit,
        setCaughtUp: suspend (Boolean) -> Unit,
        setRunTimestamp: suspend (Long) -> Unit,
        getLedger: suspend () -> Set<Long>,
        getTimestamp: (T) -> Long,
        firstRunStart: Instant = Instant.EPOCH,
    ): Long {
        val watermark = watermarkFlow.first()
        val requiredPermission = type.readPermission

        return try {
            val start = if (watermark == 0L) firstRunStart else Instant.ofEpochMilli(watermark + 1)
            val end = Instant.now()

            val result = getReadings(start, end)
            val readings = result.readings
            val batch = readings.take(MAX_READINGS_PER_RUN)

            if (batch.isEmpty()) {
                setCount(0)
                if (!result.truncated) setCaughtUp(true)
                setRunTimestamp(System.currentTimeMillis())
                return watermark
            }

            val ledger = getLedger()
            val toPublish = batch.filter { getTimestamp(it) !in ledger }
            val lastBatchTimestamp = getTimestamp(batch.last())
            val caughtUp = readings.size < MAX_READINGS_PER_RUN && !result.truncated

            if (toPublish.isNotEmpty()) {
                val pushResult = pushWithRetry { pushReadings(toPublish) }
                if (pushResult.isFailure) return watermark
                setCount(pushResult.getOrNull() ?: toPublish.size)
            } else {
                setCount(0)
            }

            setWatermark(lastBatchTimestamp)
            setCaughtUp(caughtUp)
            setRunTimestamp(System.currentTimeMillis())
            lastBatchTimestamp
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.w(e, "syncType(%s): SecurityException from read — treating as missing permission", type)
            missingSink += requiredPermission
            skippedSink += type
            watermark
        } catch (e: Exception) {
            Timber.w(e, "syncType(%s) failed, keeping watermark at %d", type, watermark)
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

            Timber.w(
                ex,
                "pushWithRetry: attempt %d failed (%s), retrying after %dms",
                attempt + 1,
                ex?.javaClass?.simpleName,
                delayMs,
            )
            delay(delayMs)
            delayMs *= 2
            attempt++
        }
    }

    companion object {
        const val MAX_READINGS_PER_RUN = 100
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 500L
        const val FIRST_RUN_LOOKBACK_DAYS = 90L
    }
}
