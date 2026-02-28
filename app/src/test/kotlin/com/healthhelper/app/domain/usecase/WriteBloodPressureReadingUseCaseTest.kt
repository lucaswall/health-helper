package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.repository.BloodPressureRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteBloodPressureReadingUseCaseTest {

    private lateinit var repository: BloodPressureRepository
    private lateinit var useCase: WriteBloodPressureReadingUseCase

    private val testReading = BloodPressureReading(
        systolic = 120,
        diastolic = 80,
    )

    @BeforeEach
    fun setUp() {
        repository = mockk()
        useCase = WriteBloodPressureReadingUseCase(repository)
    }

    @Test
    @DisplayName("returns true when repository write succeeds")
    fun returnsTrueOnSuccess() = runTest {
        coEvery { repository.writeBloodPressureRecord(any()) } returns true
        val result = useCase.invoke(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("returns false when repository write fails")
    fun returnsFalseOnFailure() = runTest {
        coEvery { repository.writeBloodPressureRecord(any()) } returns false
        val result = useCase.invoke(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("passes the BloodPressureReading to repository")
    fun passesReadingToRepository() = runTest {
        coEvery { repository.writeBloodPressureRecord(any()) } returns true
        useCase.invoke(testReading)
        coVerify { repository.writeBloodPressureRecord(testReading) }
    }

    @Test
    @DisplayName("works with STANDING_UP body position")
    fun worksWithStandingUp() = runTest {
        val reading = BloodPressureReading(systolic = 130, diastolic = 85, bodyPosition = BodyPosition.STANDING_UP)
        coEvery { repository.writeBloodPressureRecord(any()) } returns true
        val result = useCase.invoke(reading)
        assertTrue(result)
        coVerify { repository.writeBloodPressureRecord(reading) }
    }

    @Test
    @DisplayName("works with RIGHT_WRIST measurement location")
    fun worksWithRightWrist() = runTest {
        val reading = BloodPressureReading(
            systolic = 118,
            diastolic = 76,
            measurementLocation = MeasurementLocation.RIGHT_WRIST,
        )
        coEvery { repository.writeBloodPressureRecord(any()) } returns true
        val result = useCase.invoke(reading)
        assertTrue(result)
        coVerify { repository.writeBloodPressureRecord(reading) }
    }
}
