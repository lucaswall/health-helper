package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.repository.HealthConnectRepository
import com.healthhelper.app.domain.model.HealthRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
    fun defaultTimeRange() = runBlocking {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns emptyList()

        useCase(now = now)

        coVerify(exactly = 1) { repository.readSteps(expectedStart, now) }
    }

    @Test
    @DisplayName("reads steps with custom days back")
    fun customDaysBack() = runBlocking {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(14, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns emptyList()

        useCase(now = now, daysBack = 14)

        coVerify(exactly = 1) { repository.readSteps(expectedStart, now) }
    }

    @Test
    @DisplayName("returns records from repository")
    fun returnsRecords() = runBlocking {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        val records = listOf(
            HealthRecord(
                type = "Steps",
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
            HealthRecord(
                type = "Steps",
                value = 3000.0,
                startTime = Instant.parse("2026-02-18T10:00:00Z"),
                endTime = Instant.parse("2026-02-18T11:00:00Z"),
            ),
        )
        coEvery { repository.readSteps(expectedStart, now) } returns records

        val result = useCase(now = now)

        assertEquals(2, result.size)
        assertEquals(5000.0, result[0].value)
        assertEquals(3000.0, result[1].value)
    }

    @Test
    @DisplayName("returns empty list when no records found")
    fun emptyResults() = runBlocking {
        val now = Instant.parse("2026-02-20T12:00:00Z")
        val expectedStart = now.minus(7, ChronoUnit.DAYS)
        coEvery { repository.readSteps(expectedStart, now) } returns emptyList()

        val result = useCase(now = now)

        assertTrue(result.isEmpty())
    }
}
