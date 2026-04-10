package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.AuthenticationException
import com.healthhelper.app.data.api.RateLimitException
import com.healthhelper.app.data.api.ServerException
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HydrationRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { hydrationRepository.getReadings(any(), any()) } returns emptyList()
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

    // =========== WATERMARK BEHAVIOR ===========

    @Test
    @DisplayName("first run reads glucose from Instant.EPOCH when timestamp is 0")
    fun firstRunGlucoseReadsFromEpoch() = runTest {
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadings(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("first run reads BP from Instant.EPOCH when timestamp is 0")
    fun firstRunBpReadsFromEpoch() = runTest {
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(0L)

        createUseCase().invoke()

        coVerify { bloodPressureRepository.getReadings(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("subsequent run reads glucose from watermark + 1ms to exclude last processed record")
    fun subsequentRunGlucoseReadsFromWatermarkPlus1ms() = runTest {
        val savedTs = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val expectedStart = Instant.ofEpochMilli(savedTs + 1)
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(savedTs)
        coEvery { bloodGlucoseRepository.getReadings(expectedStart, any()) } returns emptyList()

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadings(expectedStart, any()) }
    }

    @Test
    @DisplayName("glucose and BP use independent watermarks")
    fun glucoseAndBpUseIndependentWatermarks() = runTest {
        val bpTs = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val bpStart = Instant.ofEpochMilli(bpTs + 1)
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(bpTs)
        coEvery { bloodPressureRepository.getReadings(bpStart, any()) } returns emptyList()

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadings(Instant.EPOCH, any()) }
        coVerify { bloodPressureRepository.getReadings(bpStart, any()) }
    }

    // =========== 100-RECORD CAP ===========

    @Test
    @DisplayName("when HC returns 150 glucose readings, only first 100 are pushed")
    fun first100Of150Pushed() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(150)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(100)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(match { it.size == 100 }) }
    }

    @Test
    @DisplayName("when HC returns 50 readings, all 50 pushed and caughtUp set to true")
    fun lessThan100AllPushedAndCaughtUpTrue() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(50)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(50)

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(match { it.size == 50 }) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(true) }
    }

    @Test
    @DisplayName("when HC returns exactly 100 readings, caughtUp is NOT set to true")
    fun exactly100NotCaughtUp() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(100)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(100)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setGlucoseSyncCaughtUp(true) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(false) }
    }

    @Test
    @DisplayName("when HC returns 0 readings, no push, caughtUp true, watermark NOT advanced")
    fun zeroReadingsNoPushCaughtUpTrueWatermarkNotAdvanced() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { settingsRepository.setGlucoseSyncCaughtUp(true) }
        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns readings
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns readings
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1, startMs = glucoseTs)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(1, startMs = bpTs)
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns readings
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(3)

        createUseCase().invoke()

        coVerify { settingsRepository.setLastGlucoseSyncTimestamp(expectedWatermark) }
    }

    @Test
    @DisplayName("glucose watermark advances independently when BP push fails")
    fun glucoseWatermarkAdvancesIndependentlyOfBp() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1, startMs = 1_000L)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(1, startMs = 2_000L)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.failure(Exception("bp failed"))

        createUseCase().invoke()

        coVerify { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastBpSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("BP watermark advances independently when glucose push fails")
    fun bpWatermarkAdvancesIndependentlyOfGlucose() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1, startMs = 1_000L)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(1, startMs = 2_000L)
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(5)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(5)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCount(5) }
    }

    @Test
    @DisplayName("sync count uses server-reported count, not local list size")
    fun syncCountUsesServerReportedCount() = runTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(10)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(100)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(95)

        createUseCase().invoke()

        coVerify { settingsRepository.setGlucoseSyncCount(95) }
    }

    @Test
    @DisplayName("glucose and BP sync counts tracked independently")
    fun glucoseBpCountsTrackedIndependently() = runTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(5)
        every { settingsRepository.bpSyncCountFlow } returns flowOf(7)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(3, startMs = 1_000L)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(2, startMs = 10_000L)
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(RateLimitException())

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("when push fails with ServerException, retries up to 3 times (4 total attempts)")
    fun retryOnServerException() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(ServerException(500))

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("when push fails with IOException, retries up to 3 times (4 total attempts)")
    fun retryOnIOException() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(IOException("network error"))

        createUseCase().invoke()

        coVerify(exactly = 4) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("after 3 retries exhausted, watermark is NOT advanced for that type")
    fun after3RetriesWatermarkNotAdvanced() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(RateLimitException())

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("on retry, delay increases: 500ms, 1s, 2s — verified with virtual time")
    fun retryDelayIncreases() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns
            Result.failure(AuthenticationException())

        createUseCase().invoke()

        coVerify(exactly = 1) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify(exactly = 0) { settingsRepository.setLastGlucoseSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("when push fails with generic non-retryable exception, no retry and watermark NOT advanced")
    fun noRetryOnGenericException() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns glucoseReadings(1)
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
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } throws SecurityException("no permission")
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }

    @Test
    @DisplayName("glucose read throws generic Exception - caught, BP still processed normally")
    fun glucoseGenericExceptionCaughtBpStillProcessed() = runTest {
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } throws RuntimeException("read failed")
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns bpReadings(1)
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

        coVerify(exactly = 0) { bloodGlucoseRepository.getReadings(any(), any()) }
        coVerify(exactly = 0) { bloodPressureRepository.getReadings(any(), any()) }
    }
}
