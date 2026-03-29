package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
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

    private lateinit var bloodPressureRepository: BloodPressureRepository
    private lateinit var foodScannerRepository: FoodScannerHealthRepository
    private lateinit var useCase: WriteBloodPressureReadingUseCase

    private val testReading = BloodPressureReading(
        systolic = 120,
        diastolic = 80,
    )

    @BeforeEach
    fun setUp() {
        bloodPressureRepository = mockk()
        foodScannerRepository = mockk()
        useCase = WriteBloodPressureReadingUseCase(bloodPressureRepository, foodScannerRepository)
    }

    @Test
    @DisplayName("both succeed: healthConnectSuccess=true and foodScannerResult=success")
    fun bothSucceed() = runTest {
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } returns true
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } returns Result.success(Unit)

        val result = useCase.invoke(testReading)

        assertTrue(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isSuccess)
        assertTrue(result.allSucceeded)
        assertFalse(result.foodScannerFailed)
    }

    @Test
    @DisplayName("HC fails, FS succeeds: healthConnectSuccess=false, foodScannerResult=success")
    fun hcFailsFsSucceeds() = runTest {
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } returns false
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } returns Result.success(Unit)

        val result = useCase.invoke(testReading)

        assertFalse(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isSuccess)
        assertFalse(result.allSucceeded)
        assertFalse(result.foodScannerFailed)
    }

    @Test
    @DisplayName("HC succeeds, FS fails: healthConnectSuccess=true, foodScannerResult=failure")
    fun hcSucceedsFsFails() = runTest {
        val exception = RuntimeException("FS error")
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } returns true
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } returns Result.failure(exception)

        val result = useCase.invoke(testReading)

        assertTrue(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isFailure)
        assertTrue(result.foodScannerFailed)
        assertFalse(result.allSucceeded)
    }

    @Test
    @DisplayName("both fail: healthConnectSuccess=false, foodScannerResult=failure")
    fun bothFail() = runTest {
        val exception = RuntimeException("FS error")
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } returns false
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } returns Result.failure(exception)

        val result = useCase.invoke(testReading)

        assertFalse(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isFailure)
        assertTrue(result.foodScannerFailed)
        assertFalse(result.allSucceeded)
    }

    @Test
    @DisplayName("HC exception does not prevent FS attempt")
    fun hcExceptionDoesNotPreventFsAttempt() = runTest {
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } throws RuntimeException("HC error")
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } returns Result.success(Unit)

        val result = useCase.invoke(testReading)

        assertFalse(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isSuccess)
        coVerify { foodScannerRepository.pushBloodPressureReading(any()) }
    }

    @Test
    @DisplayName("FS exception does not prevent HC attempt")
    fun fsExceptionDoesNotPreventHcAttempt() = runTest {
        coEvery { bloodPressureRepository.writeBloodPressureRecord(any()) } returns true
        coEvery { foodScannerRepository.pushBloodPressureReading(any()) } throws RuntimeException("FS error")

        val result = useCase.invoke(testReading)

        assertTrue(result.healthConnectSuccess)
        assertTrue(result.foodScannerResult.isFailure)
        coVerify { bloodPressureRepository.writeBloodPressureRecord(any()) }
    }
}
