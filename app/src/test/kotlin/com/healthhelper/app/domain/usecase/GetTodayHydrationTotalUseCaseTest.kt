package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.repository.HydrationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetTodayHydrationTotalUseCaseTest {

    private lateinit var repository: HydrationRepository
    private lateinit var useCase: GetTodayHydrationTotalUseCase

    @BeforeEach
    fun setUp() {
        repository = mockk()
        useCase = GetTodayHydrationTotalUseCase(repository)
    }

    @Test
    @DisplayName("returns sum of all volumeMl values for multiple readings")
    fun returnsSumForMultipleReadings() = runTest {
        val readings = listOf(
            HydrationReading(volumeMl = 250),
            HydrationReading(volumeMl = 500),
            HydrationReading(volumeMl = 300),
        )
        coEvery { repository.getReadings(any(), any()) } returns readings
        val result = useCase.invoke()
        assertEquals(1050, result)
    }

    @Test
    @DisplayName("returns 0 when repository returns empty list")
    fun returnsZeroForEmptyList() = runTest {
        coEvery { repository.getReadings(any(), any()) } returns emptyList()
        val result = useCase.invoke()
        assertEquals(0, result)
    }

    @Test
    @DisplayName("returns 0 when repository throws exception")
    fun returnsZeroWhenRepositoryThrows() = runTest {
        coEvery { repository.getReadings(any(), any()) } throws RuntimeException("HC error")
        val result = useCase.invoke()
        assertEquals(0, result)
    }

    @Test
    @DisplayName("rethrows CancellationException")
    fun rethrowsCancellationException() = runTest {
        coEvery { repository.getReadings(any(), any()) } throws CancellationException("cancelled")
        assertThrows<CancellationException> {
            useCase.invoke()
        }
    }

    @Test
    @DisplayName("calls repository with start of today to now")
    fun callsRepositoryWithCorrectTimeRange() = runTest {
        coEvery { repository.getReadings(any(), any()) } returns emptyList()

        val beforeInvoke = Instant.now()
        useCase.invoke()
        val afterInvoke = Instant.now()

        val startSlot = slot<Instant>()
        val endSlot = slot<Instant>()
        coVerify { repository.getReadings(capture(startSlot), capture(endSlot)) }

        val expectedStart = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        assertEquals(expectedStart, startSlot.captured)
        assertTrue(endSlot.captured >= beforeInvoke && endSlot.captured <= afterInvoke)
    }
}
