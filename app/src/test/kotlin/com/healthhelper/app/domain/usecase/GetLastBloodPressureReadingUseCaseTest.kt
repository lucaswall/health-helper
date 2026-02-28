package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetLastBloodPressureReadingUseCaseTest {

    private lateinit var repository: BloodPressureRepository
    private lateinit var useCase: GetLastBloodPressureReadingUseCase

    private val testReading = BloodPressureReading(
        systolic = 120,
        diastolic = 80,
    )

    @BeforeEach
    fun setUp() {
        repository = mockk()
        useCase = GetLastBloodPressureReadingUseCase(repository)
    }

    @Test
    @DisplayName("returns reading when repository has data")
    fun returnsReadingWhenDataExists() = runTest {
        coEvery { repository.getLastReading() } returns testReading
        val result = useCase.invoke()
        assertEquals(testReading, result)
    }

    @Test
    @DisplayName("returns null when repository returns null")
    fun returnsNullWhenRepositoryReturnsNull() = runTest {
        coEvery { repository.getLastReading() } returns null
        val result = useCase.invoke()
        assertNull(result)
    }

    @Test
    @DisplayName("returns null when repository throws (does not propagate exception)")
    fun returnsNullWhenRepositoryThrows() = runTest {
        coEvery { repository.getLastReading() } throws RuntimeException("Unexpected error")
        val result = useCase.invoke()
        assertNull(result)
    }
}
