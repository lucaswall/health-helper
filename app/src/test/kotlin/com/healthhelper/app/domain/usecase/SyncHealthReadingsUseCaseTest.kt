package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.AuthenticationException
import com.healthhelper.app.data.api.RateLimitException
import com.healthhelper.app.data.api.ServerException
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.HealthPermissions
import com.healthhelper.app.domain.model.HealthSyncType
import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.ReadingsResult
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HydrationRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncHealthReadingsUseCaseTest {

    private val bloodGlucoseRepository = mockk<BloodGlucoseRepository>()
    private val bloodPressureRepository = mockk<BloodPressureRepository>()
    private val hydrationRepository = mockk<HydrationRepository>()
    private val foodScannerHealthRepository = mockk<FoodScannerHealthRepository>()
    private val settingsRepository = mockk<SettingsRepository>()

    @BeforeEach
    fun setup() {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(0)
        every { settingsRepository.bpSyncCountFlow } returns flowOf(0)
        coEvery { settingsRepository.setLastGlucoseSyncTimestamp(any()) } returns Unit
        coEvery { settingsRepository.setLastBpSyncTimestamp(any()) } returns Unit
        coEvery { settingsRepository.setGlucoseSyncCount(any()) } returns Unit
        coEvery { settingsRepository.setBpSyncCount(any()) } returns Unit
        coEvery { settingsRepository.setGlucoseSyncCaughtUp(any()) } returns Unit
        coEvery { settingsRepository.setBpSyncCaughtUp(any()) } returns Unit
        coEvery { settingsRepository.setGlucoseSyncRunTimestamp(any()) } returns Unit
        coEvery { settingsRepository.setBpSyncRunTimestamp(any()) } returns Unit
        coEvery { settingsRepository.getDirectPushedGlucoseTimestamps() } returns emptySet()
        coEvery { settingsRepository.getDirectPushedBpTimestamps() } returns emptySet()
        coEvery { settingsRepository.pruneDirectPushedTimestamps(any(), any()) } returns Unit
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(0)
        coEvery { settingsRepository.setLastHydrationSyncTimestamp(any()) } returns Unit
        coEvery { settingsRepository.setHydrationSyncCount(any()) } returns Unit
        coEvery { settingsRepository.setHydrationSyncCaughtUp(any()) } returns Unit
        coEvery { settingsRepository.setHydrationSyncRunTimestamp(any()) } returns Unit
        coEvery { settingsRepository.resetHydrationWatermarkIfNeeded() } returns Unit
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(emptyList())
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(emptyList())
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns ReadingsResult(emptyList())
    }

    private fun createUseCase() = SyncHealthReadingsUseCase(
        bloodGlucoseRepository = bloodGlucoseRepository,
        bloodPressureRepository = bloodPressureRepository,
        hydrationRepository = hydrationRepository,
        foodScannerHealthRepository = foodScannerHealthRepository,
        settingsRepository = settingsRepository,
    )

    private fun glucoseReadings(count: Int, startMs: Long = 1_000L): List<GlucoseReading> =
        (0 until count).map { i ->
            GlucoseReading(
                valueMgDl = 100,
                timestamp = Instant.ofEpochMilli(startMs + i * 1000L),
            )
        }

    private fun bpReadings(count: Int, startMs: Long = 1_000L): List<BloodPressureReading> =
        (0 until count).map { i ->
            BloodPressureReading(
                systolic = 120,
                diastolic = 80,
                timestamp = Instant.ofEpochMilli(startMs + i * 1000L),
            )
        }

    private fun hydrationReadings(count: Int, startMs: Long = 1_000L): List<HydrationReading> =
        (0 until count).map { i ->
            HydrationReading(
                volumeMl = 250,
                timestamp = Instant.ofEpochMilli(startMs + i * 1000L),
            )
        }

    // =========== WATERMARK BEHAVIOR ===========

    @Test
    @DisplayName("first run reads glucose from Instant.EPOCH when timestamp is 0")
    fun firstRunGlucoseReadsFromEpoch() = runTest {
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadingsResult(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("first run reads BP from Instant.EPOCH when timestamp is 0")
    fun firstRunBpReadsFromEpoch() = runTest {
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(0L)

        createUseCase().invoke()

        coVerify { bloodPressureRepository.getReadingsResult(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("subsequent run reads glucose from watermark + 1ms to exclude last processed record")
    fun subsequentRunGlucoseReadsFromWatermarkPlus1ms() = runTest {
        val savedTs = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val expectedStart = Instant.ofEpochMilli(savedTs + 1)
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(savedTs)
        coEvery { bloodGlucoseRepository.getReadingsResult(expectedStart, any()) } returns ReadingsResult(emptyList())

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadingsResult(expectedStart, any()) }
    }

    @Test
    @DisplayName("glucose and BP use independent watermarks")
    fun glucoseAndBpUseIndependentWatermarks() = runTest {
        val bpTs = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val bpStart = Instant.ofEpochMilli(bpTs + 1)
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(bpTs)
        coEvery { bloodPressureRepository.getReadingsResult(bpStart, any()) } returns ReadingsResult(emptyList())

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadingsResult(Instant.EPOCH, any()) }
        coVerify { bloodPressureRepository.getReadingsResult(bpStart, any()) }
    }

    // =========== 100-RECORD CAP ===========

    @Test
    @DisplayName("when HC returns 150 glucose readings, only first 100 are pushed")
    fun first100Of150Pushed() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(150))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(100)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(match { it.size == 100 }) }
    }

    @Test
    @DisplayName("when HC returns 50 readings, all 50 pushed and caughtUp set to true")
    fun lessThan100AllPushedAndCaughtUpTrue() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(50))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(50)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(match { it.size == 50 }) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(true) }
    }

    @Test
    @DisplayName("when HC returns exactly 100 readings, caughtUp is NOT set to true")
    fun exactly100NotCaughtUp() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(100))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(100)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setGlucoseSyncCaughtUp(true) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(false) }
    }

    @Test
    @DisplayName("when HC returns 0 readings, no push, caughtUp true, watermark NOT advanced")
    fun zeroReadingsNoPushCaughtUpTrueWatermarkNotAdvanced() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(emptyList())

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(true) }
        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    // =========== TRUNCATION HANDLING ===========

    @Test
    @DisplayName("when result is truncated with fewer than 100 readings, caughtUp is NOT set to true")
    fun truncatedResultNotCaughtUp() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(glucoseReadings(50), truncated = true)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(50)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setGlucoseSyncCaughtUp(true) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(false) }
    }

    @Test
    @DisplayName("when result is NOT truncated with fewer than 100 readings, caughtUp is true")
    fun nonTruncatedResultCaughtUp() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(glucoseReadings(50), truncated = false)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(50)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCaughtUp(true) }
    }

    @Test
    @DisplayName("truncated hydration result does not mark caught up even with few readings")
    fun truncatedHydrationNotCaughtUp() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(hydrationReadings(10), truncated = true)
        coEvery { foodScannerHealthRepository.pushHydrationReadings(any()) } returns Result.success(10)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setHydrationSyncCaughtUp(true) }
        coVerify { settingsRepository.setHydrationSyncCaughtUp(false) }
    }

    // =========== TRUNCATED EMPTY BATCH ===========

    @Test
    @DisplayName("syncType does not setCaughtUp when hydration read returns empty + truncated")
    fun hydrationEmptyTruncatedDoesNotSetCaughtUp() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(emptyList(), truncated = true)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setHydrationSyncCaughtUp(any()) }
        coVerify { settingsRepository.setHydrationSyncRunTimestamp(any()) }
        coVerify { settingsRepository.setHydrationSyncCount(0) }
    }

    @Test
    @DisplayName("syncType does not setCaughtUp when glucose read returns empty + truncated")
    fun glucoseEmptyTruncatedDoesNotSetCaughtUp() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(emptyList(), truncated = true)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setGlucoseSyncCaughtUp(any()) }
        coVerify { settingsRepository.setGlucoseSyncRunTimestamp(any()) }
        coVerify { settingsRepository.setGlucoseSyncCount(0) }
    }

    @Test
    @DisplayName("syncType does not setCaughtUp when BP read returns empty + truncated")
    fun bpEmptyTruncatedDoesNotSetCaughtUp() = runTest {
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(emptyList(), truncated = true)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setBpSyncCaughtUp(any()) }
        coVerify { settingsRepository.setBpSyncRunTimestamp(any()) }
        coVerify { settingsRepository.setBpSyncCount(0) }
    }

    @Test
    @DisplayName("syncType sets caughtUp=true when batch is empty and not truncated (hydration regression)")
    fun hydrationEmptyNotTruncatedSetsCaughtUpTrue() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns
            ReadingsResult(emptyList(), truncated = false)

        createUseCase().invoke()

        coVerify { settingsRepository.setHydrationSyncCaughtUp(true) }
    }

    // =========== LEDGER FILTERING ===========

    @Test
    @DisplayName("readings whose timestamp is in the already-pushed ledger are excluded before pushing")
    fun ledgerReadingsExcludedBeforePushing() = runTest {
        val ts1 = 1_000L
        val ts2 = 2_000L
        val ts3 = 3_000L
        val readings = listOf(
            GlucoseReading(valueMgDl = 100, timestamp = Instant.ofEpochMilli(ts1)),
            GlucoseReading(valueMgDl = 100, timestamp = Instant.ofEpochMilli(ts2)),
            GlucoseReading(valueMgDl = 100, timestamp = Instant.ofEpochMilli(ts3)),
        )
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(readings)
        coEvery { settingsRepository.getDirectPushedGlucoseTimestamps() } returns setOf(ts2)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(2)

        createUseCase().invoke()

        coVerify {
            foodScannerHealthRepository.pushGlucoseReadings(
                match { list -> list.size == 2 && list.none { it.timestamp.toEpochMilli() == ts2 } }
            )
        }
    }

    @Test
    @DisplayName("if all 100 readings in ledger, no push but watermark advances to last record timestamp")
    fun allInLedgerNoPushWatermarkAdvances() = runTest {
        val readings = glucoseReadings(100, startMs = 1_000L)
        val allTimestamps = readings.map { it.timestamp.toEpochMilli() }.toSet()
        val lastTimestamp = readings.last().timestamp.toEpochMilli()
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(readings)
        coEvery { settingsRepository.getDirectPushedGlucoseTimestamps() } returns allTimestamps

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { settingsRepository.setLastGlucoseSyncTimestamp(lastTimestamp) }
    }

    @Test
    @DisplayName("ledger is pruned after watermark advances with new watermark values")
    fun ledgerPrunedAfterWatermarkAdvances() = runTest {
        val glucoseTs = 5_000L
        val bpTs = 6_000L
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1, startMs = glucoseTs))
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1, startMs = bpTs))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify { settingsRepository.pruneDirectPushedTimestamps(glucoseTs, bpTs) }
    }

    // =========== WATERMARK ADVANCEMENT ===========

    @Test
    @DisplayName("on successful push, watermark set to last pushed record's timestamp, not Instant.now()")
    fun watermarkSetToLastPushedRecordTimestamp() = runTest {
        val readings = glucoseReadings(3, startMs = 1_000L)
        val expectedWatermark = readings.last().timestamp.toEpochMilli()
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(readings)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(3)

        createUseCase().invoke()

        coVerify { settingsRepository.setLastGlucoseSyncTimestamp(expectedWatermark) }
    }

    @Test
    @DisplayName("glucose watermark advances independently when BP push fails")
    fun glucoseWatermarkAdvancesIndependentlyOfBp() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1, startMs = 1_000L))
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1, startMs = 2_000L))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.failure(Exception("bp failed"))

        createUseCase().invoke()

        coVerify { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastBpSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("BP watermark advances independently when glucose push fails")
    fun bpWatermarkAdvancesIndependentlyOfGlucose() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1, startMs = 1_000L))
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1, startMs = 2_000L))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.failure(Exception("glucose failed"))
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
        coVerify { settingsRepository.setLastBpSyncTimestamp(any()) }
    }

    // =========== SYNC COUNT TRACKING ===========

    @Test
    @DisplayName("on successful push of N records, sync count set to last batch size")
    fun syncCountSetToLastBatch() = runTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(10)
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(5))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(5)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCount(5) }
    }

    @Test
    @DisplayName("sync count uses server-reported count, not local list size")
    fun syncCountUsesServerReportedCount() = runTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(10)
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(100))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(95)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCount(95) }
    }

    @Test
    @DisplayName("glucose and BP sync counts tracked independently")
    fun glucoseBpCountsTrackedIndependently() = runTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(5)
        every { settingsRepository.bpSyncCountFlow } returns flowOf(7)
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(3, startMs = 1_000L))
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(2, startMs = 10_000L))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(3)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(2)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCount(3) }
        coVerify { settingsRepository.setBpSyncCount(2) }
    }

    // =========== RETRY WITH BACKOFF ===========

    @Test
    @DisplayName("when push fails with RateLimitException, retries up to 3 times (4 total attempts)")
    fun retryOnRateLimitException() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(RateLimitException())

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("when push fails with ServerException, retries up to 3 times (4 total attempts)")
    fun retryOnServerException() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(ServerException(500))

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("when push fails with IOException, retries up to 3 times (4 total attempts)")
    fun retryOnIOException() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(IOException("network error"))

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("after 3 retries exhausted, watermark is NOT advanced for that type")
    fun after3RetriesWatermarkNotAdvanced() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(RateLimitException())

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("on retry, delay increases: 500ms, 1s, 2s — verified with virtual time")
    fun retryDelayIncreases() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(RateLimitException())

        createUseCase().invoke()

        // Total virtual time elapsed must equal 500 + 1000 + 2000 = 3500ms for glucose retries
        assertTrue(testScheduler.currentTime >= 3500L,
            "Expected at least 3500ms of virtual time, got ${testScheduler.currentTime}ms",
        )
    }

    // =========== PERMANENT ERRORS (NO RETRY) ===========

    @Test
    @DisplayName("when push fails with AuthenticationException, no retry and watermark NOT advanced")
    fun noRetryOnAuthenticationException() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(AuthenticationException())

        createUseCase().invoke()

        coVerify(exactly = 1) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("when push fails with generic non-retryable exception, no retry and watermark NOT advanced")
    fun noRetryOnGenericException() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(Exception("unexpected error"))

        createUseCase().invoke()

        coVerify(exactly = 1) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    // =========== ERROR ISOLATION ===========

    @Test
    @DisplayName("glucose read throws SecurityException - caught, BP still processed normally")
    fun glucoseSecurityExceptionCaughtBpStillProcessed() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } throws SecurityException("no permission")
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1))
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }

    @Test
    @DisplayName("glucose read throws generic Exception - caught, BP still processed normally")
    fun glucoseGenericExceptionCaughtBpStillProcessed() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } throws RuntimeException("read failed")
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1))
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }

    @Test
    @DisplayName("CancellationException propagates from any operation")
    fun cancellationExceptionPropagates() = runTest {
        coEvery { settingsRepository.isConfigured() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            createUseCase().invoke()
        }
    }

    @Test
    @DisplayName("returns early without error when settings not configured")
    fun returnsEarlyWhenNotConfigured() = runTest {
        coEvery { settingsRepository.isConfigured() } returns false

        createUseCase().invoke()

        coVerify(exactly = 0) { bloodGlucoseRepository.getReadingsResult(any(), any()) }
        coVerify(exactly = 0) { bloodPressureRepository.getReadingsResult(any(), any()) }
    }

    // =========== HYDRATION SYNC ===========

    @Test
    @DisplayName("successful hydration push calls pushHydrationReadings and advances watermark")
    fun hydrationPushAdvancesWatermark() = runTest {
        val readings = hydrationReadings(3, startMs = 1_000L)
        val expectedWatermark = readings.last().timestamp.toEpochMilli()
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns ReadingsResult(readings)
        coEvery { foodScannerHealthRepository.pushHydrationReadings(any()) } returns Result.success(3)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushHydrationReadings(match { it.size == 3 }) }
        coVerify { settingsRepository.setLastHydrationSyncTimestamp(expectedWatermark) }
        coVerify { settingsRepository.setHydrationSyncCount(3) }
    }

    @Test
    @DisplayName("hydration push failure does NOT advance watermark")
    fun hydrationPushFailureDoesNotAdvanceWatermark() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns ReadingsResult(hydrationReadings(1))
        coEvery { foodScannerHealthRepository.pushHydrationReadings(any()) } returns
            Result.failure(Exception("push failed"))

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastHydrationSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("hydration exception does not block glucose or BP sync")
    fun hydrationExceptionDoesNotBlockOtherSyncs() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } throws RuntimeException("hydration read failed")
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } returns ReadingsResult(glucoseReadings(1, startMs = 1_000L))
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1, startMs = 2_000L))
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }

    @Test
    @DisplayName("hydration caughtUp set to true when fewer than 100 readings returned")
    fun hydrationCaughtUpWhenFewerThan100() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns ReadingsResult(hydrationReadings(50))
        coEvery { foodScannerHealthRepository.pushHydrationReadings(any()) } returns Result.success(50)

        createUseCase().invoke()

        coVerify { settingsRepository.setHydrationSyncCaughtUp(true) }
    }

    @Test
    @DisplayName("hydration caughtUp set to false when exactly 100 readings returned")
    fun hydrationNotCaughtUpWhenExactly100() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } returns ReadingsResult(hydrationReadings(100))
        coEvery { foodScannerHealthRepository.pushHydrationReadings(any()) } returns Result.success(100)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setHydrationSyncCaughtUp(true) }
        coVerify { settingsRepository.setHydrationSyncCaughtUp(false) }
    }

    // =========== PERMISSION HANDLING (via SecurityException from repo reads) ===========

    @Test
    @DisplayName("hydration read throws SecurityException: hydration skipped, no caughtUp=true, reported as missing")
    fun hydrationSecurityExceptionSkipsAndReports() = runTest {
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } throws SecurityException("denied")

        val report = createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setHydrationSyncCaughtUp(true) }
        assertTrue(HealthPermissions.READ_HYDRATION in report.missingReadPermissions)
        assertTrue(HealthSyncType.HYDRATION in report.skippedTypes)
    }

    @Test
    @DisplayName("glucose SecurityException: glucose skipped but BP and hydration still run")
    fun glucoseSecurityExceptionDoesNotBlockOthers() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } throws SecurityException("denied")
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } returns ReadingsResult(bpReadings(1))
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        val report = createUseCase().invoke()

        coVerify { bloodPressureRepository.getReadingsResult(any(), any()) }
        coVerify { hydrationRepository.getReadingsResult(any(), any()) }
        assertEquals(setOf(HealthPermissions.READ_BLOOD_GLUCOSE), report.missingReadPermissions)
    }

    // =========== HYDRATION FIRST-RUN WINDOW ===========

    @Test
    @DisplayName("syncType hydration first run reads from ~90 days ago, not EPOCH")
    fun hydrationFirstRunReadsFromNDaysAgo() = runTest {
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(0L)
        val startSlot = slot<Instant>()
        coEvery { hydrationRepository.getReadingsResult(capture(startSlot), any()) } returns ReadingsResult(emptyList())

        createUseCase().invoke()

        val captured = startSlot.captured
        val lower = Instant.now().minus(java.time.Duration.ofDays(91))
        val upper = Instant.now().minus(java.time.Duration.ofDays(89))
        assertTrue(captured.isAfter(lower), "Expected start after $lower but was $captured")
        assertTrue(captured.isBefore(upper), "Expected start before $upper but was $captured")
    }

    @Test
    @DisplayName("syncType hydration subsequent run reads from watermark+1ms")
    fun hydrationSubsequentRunReadsFromWatermarkPlus1ms() = runTest {
        val watermark = 1700000000000L
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(watermark)
        val startSlot = slot<Instant>()
        coEvery { hydrationRepository.getReadingsResult(capture(startSlot), any()) } returns ReadingsResult(emptyList())

        createUseCase().invoke()

        assertEquals(Instant.ofEpochMilli(watermark + 1), startSlot.captured)
    }

    @Test
    @DisplayName("syncType glucose first run still reads from EPOCH (unchanged)")
    fun glucoseFirstRunStillReadsFromEpoch() = runTest {
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadingsResult(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("when all reads succeed and nothing to sync, report has no missing permissions")
    fun allGrantedEmptyReport() = runTest {
        val report = createUseCase().invoke()

        assertTrue(report.missingReadPermissions.isEmpty())
        assertTrue(report.skippedTypes.isEmpty())
        assertFalse(report.hasMissingPermissions)
    }

    @Test
    @DisplayName("all three reads throw SecurityException: all skipped, watermark NOT advanced")
    fun allSecurityExceptionsAllSkipped() = runTest {
        coEvery { bloodGlucoseRepository.getReadingsResult(any(), any()) } throws SecurityException("denied")
        coEvery { bloodPressureRepository.getReadingsResult(any(), any()) } throws SecurityException("denied")
        coEvery { hydrationRepository.getReadingsResult(any(), any()) } throws SecurityException("denied")

        val report = createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastBpSyncTimestamp(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastHydrationSyncTimestamp(any()) }
        assertEquals(3, report.skippedTypes.size)
    }
}
