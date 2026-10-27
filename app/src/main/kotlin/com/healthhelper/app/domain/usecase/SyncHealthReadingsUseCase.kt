package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.HealthPermissions
import com.healthhelper.app.domain.model.HealthReadingsSyncReport
import com.healthhelper.app.domain.model.HealthSyncType
import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.RateLimitException
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.model.ServerException
import com.healthhelper.app.domain.model.readPermission
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HealthPermissionChecker
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
    private val permissionChecker: HealthPermissionChecker,
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
            getLateReadings = ::findLateHydrationReadings,
        )

        settingsRepository.pruneDirectPushedTimestamps(glucoseWatermark, bpWatermark)

        return HealthReadingsSyncReport(
            missingReadPermissions = refineMissingPermissions(missing),
            skippedTypes = skipped.toSet(),
        )
    }

    private suspend fun <T> syncType(
        type: HealthSyncType,
        missingSink: MutableSet<String>,
        skippedSink: MutableSet<HealthSyncType>,
        getReadings: suspend (Instant, Instant, Int) -> ReadingsResult<T>,
        pushReadings: suspend (List<T>) -> Result<Int>,
        watermarkFlow: Flow<Long>,
        setWatermark: suspend (Long) -> Unit,
        setCount: suspend (Int) -> Unit,
        setCaughtUp: suspend (Boolean) -> Unit,
        setRunTimestamp: suspend (Long) -> Unit,
        getLedger: suspend () -> Set<Long>,
        getTimestamp: (T) -> Long,
        firstRunStart: Instant = Instant.EPOCH,
        getLateReadings: suspend (watermark: Long) -> List<T> = { emptyList() },
    ): Long {
        val watermark = watermarkFlow.first()
        val requiredPermission = type.readPermission

        return try {
            val start = if (watermark == 0L) firstRunStart else Instant.ofEpochMilli(watermark + 1)
            val end = Instant.now()

            // Read failures throw (caught below) so the watermark and status stay unchanged.
            val result = getReadings(start, end, MAX_READINGS_PER_RUN)
            val readings = result.readings
            val batch = selectBatch(readings, result.truncated, getTimestamp)
            val late = getLateReadings(watermark)

            if (batch.isEmpty() && late.isEmpty()) {
                setCount(0)
                if (!result.truncated) setCaughtUp(true)
                setRunTimestamp(System.currentTimeMillis())
                return watermark
            }

            val ledger = getLedger()
            val toPublish = late + batch.filter { getTimestamp(it) !in ledger }
            val caughtUp = batch.size == readings.size && !result.truncated

            if (toPublish.isNotEmpty()) {
                var pushed = 0
                for (chunk in toPublish.chunked(MAX_READINGS_PER_RUN)) {
                    val pushResult = pushWithRetry { pushReadings(chunk) }
                    if (pushResult.isFailure) return watermark
                    pushed += pushResult.getOrNull() ?: chunk.size
                }
                setCount(pushed)
            } else {
                setCount(0)
            }

            val newWatermark = if (batch.isEmpty()) watermark else getTimestamp(batch.last())
            if (batch.isNotEmpty()) setWatermark(newWatermark)
            if (batch.isNotEmpty() || !result.truncated) setCaughtUp(caughtUp)
            setRunTimestamp(System.currentTimeMillis())
            newWatermark
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

    /**
     * Picks at most [MAX_READINGS_PER_RUN] readings to push, never splitting readings that share a
     * timestamp across runs: the next run starts at watermark + 1ms, so a tie left behind would be
     * skipped forever. If every candidate shares the boundary timestamp, the whole tie group is
     * pushed instead so the sync cannot stall.
     *
     * A truncated result with no more than [MAX_READINGS_PER_RUN] readings was cut short by a
     * timeout, so its trailing tie group may be incomplete: leave it for the next run (unless it is
     * the whole result, to avoid stalling).
     */
    private fun <T> selectBatch(readings: List<T>, truncated: Boolean, getTimestamp: (T) -> Long): List<T> {
        if (readings.size <= MAX_READINGS_PER_RUN) {
            if (!truncated || readings.isEmpty()) return readings
            val lastTs = getTimestamp(readings.last())
            return readings.dropLastWhile { getTimestamp(it) == lastTs }.ifEmpty { readings }
        }
        val boundary = getTimestamp(readings[MAX_READINGS_PER_RUN])
        val beforeBoundary = readings.takeWhile { getTimestamp(it) < boundary }
        return beforeBoundary.ifEmpty { readings.takeWhile { getTimestamp(it) <= boundary } }
    }

    /**
     * Other apps can write hydration records late with a past timestamp (a watch batch-syncing
     * hours later, a backdated manual entry) or edit existing ones. Those land behind the
     * watermark, so re-read a trailing window and pick records inserted/modified since the
     * previous successful run. The server upserts by measurement time, so re-pushing is harmless.
     */
    private suspend fun findLateHydrationReadings(watermark: Long): List<HydrationReading> {
        if (watermark == 0L) return emptyList()
        val previousRun = settingsRepository.hydrationSyncRunTimestampFlow.first()
        if (previousRun == 0L) return emptyList()
        val windowStart = Instant.ofEpochMilli(watermark).minus(LATE_ARRIVAL_WINDOW)
        val windowEnd = Instant.ofEpochMilli(watermark + 1)
        val modifiedAfter = Instant.ofEpochMilli(previousRun).minus(LATE_ARRIVAL_MARGIN)
        val late = hydrationRepository.getReadingsResult(windowStart, windowEnd).readings
            .filter { it.lastModified?.isAfter(modifiedAfter) == true }
        if (late.isNotEmpty()) {
            Timber.d("findLateHydrationReadings: %d late/edited hydration readings behind watermark", late.size)
        }
        return late
    }

    /**
     * A read SecurityException is attributed to the per-type read permission, but in the
     * background the usual cause is a missing READ_HEALTH_DATA_IN_BACKGROUND. Report what is
     * actually missing so the user is asked for the right grant; fall back to the reported set
     * when the granted set cannot be determined.
     */
    private suspend fun refineMissingPermissions(reported: Set<String>): Set<String> {
        if (reported.isEmpty()) return emptySet()
        val granted = try {
            permissionChecker.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "refineMissingPermissions: could not read granted permissions")
            return reported.toSet()
        }
        if (granted.isEmpty()) return reported.toSet()
        val actuallyMissing = reported.filterTo(mutableSetOf()) { it !in granted }
        if (HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            actuallyMissing += HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND
        }
        return actuallyMissing.ifEmpty { reported.toSet() }
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
        val LATE_ARRIVAL_WINDOW: Duration = Duration.ofHours(48)
        val LATE_ARRIVAL_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
