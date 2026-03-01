package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetLastGlucoseReadingUseCaseTest {

    private lateinit var repository: BloodGlucoseRepository
    private lateinit var useCase: GetLastGlucoseReadingUseCase

    private val testReading = GlucoseReading(valueMmolL = 5.6)

    @BeforeEach
    fun setUp() {
        repository = mockk()
        useCase = GetLastGlucoseReadingUseCase(repository)
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
