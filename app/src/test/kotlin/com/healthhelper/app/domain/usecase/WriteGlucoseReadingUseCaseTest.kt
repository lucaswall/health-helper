package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteGlucoseReadingUseCaseTest {

    private lateinit var repository: BloodGlucoseRepository
    private lateinit var useCase: WriteGlucoseReadingUseCase

    private val testReading = GlucoseReading(valueMgDl = 101)

    @BeforeEach
    fun setUp() {
        repository = mockk()
        useCase = WriteGlucoseReadingUseCase(repository)
    }

    @Test
    @DisplayName("returns true when repository write succeeds")
    fun returnsTrueOnSuccess() = runTest {
        coEvery { repository.writeBloodGlucoseRecord(any()) } returns true
        val result = useCase.invoke(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("returns false when repository write fails")
    fun returnsFalseOnFailure() = runTest {
        coEvery { repository.writeBloodGlucoseRecord(any()) } returns false
        val result = useCase.invoke(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("passes the GlucoseReading to repository")
    fun passesReadingToRepository() = runTest {
        coEvery { repository.writeBloodGlucoseRecord(any()) } returns true
        useCase.invoke(testReading)
        coVerify { repository.writeBloodGlucoseRecord(testReading) }
    }
}
