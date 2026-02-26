package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.HealthRecordType
import com.healthhelper.app.domain.model.StepsErrorType
import com.healthhelper.app.domain.model.StepsResult
import com.healthhelper.app.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ReadStepsUseCaseTest {

    private lateinit var repository: HealthConnectRepository
    private lateinit var useCase: ReadStepsUseCase

    @BeforeEach
    fun setup() {
        repository = mockk()
        useCase = ReadStepsUseCase(repository)
    }

    @Test
    @DisplayName("reads steps with default 7-day window")
    fun defaultTimeRange() = runTest {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns StepsResult.Success(emptyList())

        useCase(now = now)

        coVerify(exactly = 1) { repository.readSteps(expectedStart, now) }
    }

    @Test
    @DisplayName("reads steps with custom days back")
    fun customDaysBack() = runTest {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(14, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns StepsResult.Success(emptyList())

        useCase(now = now, daysBack = 14)

        coVerify(exactly = 1) { repository.readSteps(expectedStart, now) }
    }

    @Test
    @DisplayName("returns records from repository")
    fun returnsRecords() = runTest {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        val records = listOf(
            HealthRecord(
                id = "record-1",
                type = HealthRecordType.Steps,
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
            HealthRecord(
                id = "record-2",
                type = HealthRecordType.Steps,
                value = 3000.0,
                startTime = Instant.parse("2026-02-18T10:00:00Z"),
                endTime = Instant.parse("2026-02-18T11:00:00Z"),
            ),
        )
        coEvery { repository.readSteps(expectedStart, now) } returns StepsResult.Success(records)

        val result = useCase(now = now)

        assertTrue(result is StepsResult.Success)
        val success = result as StepsResult.Success
        assertEquals(2, success.records.size)
        assertEquals(5000.0, success.records[0].value)
        assertEquals(3000.0, success.records[1].value)
    }

    @Test
    @DisplayName("propagates error results from repository")
    fun propagatesErrors() = runTest {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        val error = StepsResult.Error(
            StepsErrorType.PermissionDenied,
            "Permission denied",
        )
        coEvery { repository.readSteps(expectedStart, now) } returns error

        val result = useCase(now = now)

        assertTrue(result is StepsResult.Error)
        assertEquals(error, result)
    }

    @Test
    @DisplayName("returns empty list when no records found")
    fun emptyResults() = runTest {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns StepsResult.Success(emptyList())

        val result = useCase(now = now)

        assertTrue(result is StepsResult.Success)
        assertTrue((result as StepsResult.Success).records.isEmpty())
    }

    @Test
    @DisplayName("throws IllegalArgumentException for daysBack = 0")
    fun daysBackZero() = runTest {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            useCase(daysBack = 0)
        }
    }

    @Test
    @DisplayName("throws IllegalArgumentException for negative daysBack")
    fun daysBackNegative() = runTest {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            useCase(daysBack = -1)
        }
    }
}
