package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SyncHealthReadingsUseCaseTest {

    private val bloodGlucoseRepository = mockk<BloodGlucoseRepository>()
    private val bloodPressureRepository = mockk<BloodPressureRepository>()
    private val foodScannerHealthRepository = mockk<FoodScannerHealthRepository>()
    private val settingsRepository = mockk<SettingsRepository>()

    private val glucoseReading = GlucoseReading(valueMgDl = 100, timestamp = Instant.now())
    private val bpReading = BloodPressureReading(systolic = 120, diastolic = 80, timestamp = Instant.now())

    private fun createUseCase() = SyncHealthReadingsUseCase(
        bloodGlucoseRepository = bloodGlucoseRepository,
        bloodPressureRepository = bloodPressureRepository,
        foodScannerHealthRepository = foodScannerHealthRepository,
        settingsRepository = settingsRepository,
    )

    @Test
    @DisplayName("returns early without error when settings not configured")
    fun returnsEarlyWhenNotConfigured() = runTest {
        coEvery { settingsRepository.isConfigured() } returns false

        createUseCase().invoke()

        coVerify(exactly = 0) { bloodGlucoseRepository.getReadings(any(), any()) }
        coVerify(exactly = 0) { bloodPressureRepository.getReadings(any(), any()) }
    }

    @Test
    @DisplayName("first sync uses Instant.EPOCH as start")
    fun firstSyncUsesEpochAsStart() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(Instant.EPOCH, any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(Instant.EPOCH, any()) } returns emptyList()
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadings(Instant.EPOCH, any()) }
        coVerify { bloodPressureRepository.getReadings(Instant.EPOCH, any()) }
    }

    @Test
    @DisplayName("subsequent sync uses last timestamp minus 1 day as start")
    fun subsequentSyncUsesTimestampMinusOneDay() = runTest {
        val lastTimestamp = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
        val expectedStart = Instant.ofEpochMilli(lastTimestamp).minus(1, ChronoUnit.DAYS)

        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(lastTimestamp)
        coEvery { bloodGlucoseRepository.getReadings(expectedStart, any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(expectedStart, any()) } returns emptyList()
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { bloodGlucoseRepository.getReadings(expectedStart, any()) }
        coVerify { bloodPressureRepository.getReadings(expectedStart, any()) }
    }

    @Test
    @DisplayName("glucose readings pushed successfully updates sync timestamp")
    fun glucosePushedSuccessfullyUpdatesTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns listOf(glucoseReading)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("BP readings pushed successfully updates sync timestamp")
    fun bpPushedSuccessfullyUpdatesTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns listOf(bpReading)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("mixed glucose and BP readings both pushed and timestamp updated")
    fun mixedReadingsBothPushedUpdatesTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns listOf(glucoseReading)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns listOf(bpReading)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
        coVerify { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("no readings found still updates sync timestamp")
    fun noReadingsFoundUpdatesTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify(exactly = 0) { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }

    @Test
    @DisplayName("glucose push fails but BP push succeeds - does not update sync timestamp")
    fun glucosePushFailsDoesNotUpdateTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns listOf(glucoseReading)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns listOf(bpReading)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.failure(Exception("push failed"))
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("BP push fails but glucose push succeeds - does not update sync timestamp")
    fun bpPushFailsDoesNotUpdateTimestamp() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns listOf(glucoseReading)
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns listOf(bpReading)
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.failure(Exception("push failed"))

        createUseCase().invoke()

        coVerify(exactly = 0) { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("Health Connect SecurityException on glucose read - caught, BP still processed, timestamp updated")
    fun glucoseSecurityExceptionCaughtBpStillProcessed() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } throws SecurityException("no permission")
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns listOf(bpReading)
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
        coVerify { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("Health Connect empty list - no API call made for that type")
    fun emptyListMakesNoApiCall() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        coVerify(exactly = 0) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
        coVerify(exactly = 0) { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
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
    @DisplayName("large glucose batch chunked into batches of 1000")
    fun largeGlucoseBatchChunked() = runTest {
        val readings = (1..1500).map { GlucoseReading(valueMgDl = 100) }

        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns readings
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { foodScannerHealthRepository.pushGlucoseReadings(any()) } returns Result.success(1000)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        // 1500 readings → 2 chunks (1000 + 500)
        coVerify(exactly = 2) { foodScannerHealthRepository.pushGlucoseReadings(any()) }
    }

    @Test
    @DisplayName("large BP batch chunked into batches of 1000")
    fun largeBpBatchChunked() = runTest {
        val readings = (1..2000).map { BloodPressureReading(systolic = 120, diastolic = 80) }

        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.lastHealthReadingsSyncTimestampFlow } returns flowOf(0L)
        coEvery { bloodGlucoseRepository.getReadings(any(), any()) } returns emptyList()
        coEvery { bloodPressureRepository.getReadings(any(), any()) } returns readings
        coEvery { foodScannerHealthRepository.pushBloodPressureReadings(any()) } returns Result.success(1000)
        coEvery { settingsRepository.setLastHealthReadingsSyncTimestamp(any()) } returns Unit

        createUseCase().invoke()

        // 2000 readings → 2 chunks (1000 + 1000)
        coVerify(exactly = 2) { foodScannerHealthRepository.pushBloodPressureReadings(any()) }
    }
}
