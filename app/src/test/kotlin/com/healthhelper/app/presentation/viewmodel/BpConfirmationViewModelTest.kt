package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.usecase.WriteBloodPressureReadingUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BpConfirmationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var useCase: WriteBloodPressureReadingUseCase
    private lateinit var viewModel: BpConfirmationViewModel

    private fun createSavedStateHandle(systolic: Int = 120, diastolic: Int = 80) =
        SavedStateHandle(mapOf("systolic" to systolic, "diastolic" to diastolic))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        useCase = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        systolic: Int = 120,
        diastolic: Int = 80,
    ) = BpConfirmationViewModel(createSavedStateHandle(systolic, diastolic), useCase)

    @Test
    fun `initial state populated from constructor args`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("120", state.systolic)
            assertEquals("80", state.diastolic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state has SITTING_DOWN body position`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(BodyPosition.SITTING_DOWN, state.bodyPosition)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state has LEFT_UPPER_ARM measurement location`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(MeasurementLocation.LEFT_UPPER_ARM, state.measurementLocation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateSystolic updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateSystolic("130")

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("130", state.systolic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateDiastolic updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateDiastolic("85")

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("85", state.diastolic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled false when systolic out of range below`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateSystolic("50") // below 60

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled false when systolic out of range above`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateSystolic("310") // above 300

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled false when diastolic out of range below`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateDiastolic("25") // below 30

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled false when diastolic out of range above`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateDiastolic("210") // above 200

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled false when systolic less than or equal to diastolic`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateSystolic("80")
        viewModel.updateDiastolic("80")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSaveEnabled true when all validations pass`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateBodyPosition updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateBodyPosition(BodyPosition.SITTING_DOWN)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(BodyPosition.SITTING_DOWN, state.bodyPosition)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateMeasurementLocation updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateMeasurementLocation(MeasurementLocation.LEFT_UPPER_ARM)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(MeasurementLocation.LEFT_UPPER_ARM, state.measurementLocation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save calls WriteBloodPressureReadingUseCase with correct domain model`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            useCase.invoke(
                match { reading: BloodPressureReading ->
                    reading.systolic == 120 && reading.diastolic == 80
                },
            )
        }
    }

    @Test
    fun `save on success emits navigateHome event`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertTrue(msg.contains("120"))
            assertTrue(msg.contains("80"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with both succeed clears isSaving and no error`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaving)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with HC fail and FS success navigates home with warning`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertNotNull(msg)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.warning)
            assertNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with HC success and FS fail navigates home with warning`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertNotNull(msg)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.warning)
            assertNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with both fail sets error and does not navigate`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save on failure sets error message`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save while already saving is ignored`() = runTest {
        var callCount = 0
        coEvery { useCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            HealthDataWriteResult(
                healthConnectSuccess = true,
                foodScannerResult = Result.success(Unit),
            )
        }
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        testDispatcher.scheduler.advanceTimeBy(100)
        viewModel.save() // second call should be ignored
        testDispatcher.scheduler.advanceTimeBy(100)

        assertEquals(1, callCount)
    }

    @Test
    fun `isSaveEnabled false when systolic is not a valid integer`() = runTest {
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.updateSystolic("abc")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save exception sets generic error message`() = runTest {
        coEvery { useCase.invoke(any()) } throws RuntimeException("Write failed")
        viewModel = createViewModel(120, 80)
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
