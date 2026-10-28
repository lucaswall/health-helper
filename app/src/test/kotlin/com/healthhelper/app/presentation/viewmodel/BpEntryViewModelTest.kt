package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.usecase.WriteBloodPressureReadingUseCase
import com.healthhelper.app.presentation.image.ImageTooLargeException
import com.healthhelper.app.presentation.image.ReadingImageScanner
import com.healthhelper.app.presentation.image.ScanOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class BpEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var useCase: WriteBloodPressureReadingUseCase
    private lateinit var scanner: ReadingImageScanner
    private lateinit var viewModel: BpEntryViewModel

    private val imageBytes = byteArrayOf(1, 2, 3)
    private val allSucceeded = HealthDataWriteResult(
        healthConnectSuccess = true,
        foodScannerResult = Result.success(Unit),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        useCase = mockk()
        scanner = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()) =
        BpEntryViewModel(handle, useCase, scanner)

    private fun BpEntryViewModel.enter(systolic: String, diastolic: String, heartRate: String = "") {
        updateSystolic(systolic)
        updateDiastolic(diastolic)
        updateHeartRate(heartRate)
    }

    // --- initial state / manual entry ---

    @Test
    fun `opens with an empty form, Save disabled and no validation error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.systolic)
        assertEquals("", state.diastolic)
        assertEquals("", state.heartRate)
        assertFalse(state.isSaveEnabled)
        assertNull(state.validationError)
        assertFalse(state.isScanning)
        assertEquals(BodyPosition.SITTING_DOWN, state.bodyPosition)
        assertEquals(MeasurementLocation.LEFT_UPPER_ARM, state.measurementLocation)
    }

    @Test
    fun `systolic alone keeps Save disabled without an error`() = runTest {
        viewModel = createViewModel()
        viewModel.updateSystolic("120")

        val state = viewModel.uiState.value
        assertFalse(state.isSaveEnabled)
        assertNull(state.validationError)
    }

    @Test
    fun `valid systolic and diastolic enable Save with heart rate left blank`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        assertTrue(viewModel.uiState.value.isSaveEnabled)
        assertNull(viewModel.uiState.value.validationError)
    }

    @Test
    fun `systolic out of range shows error`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("201", "80")

        assertFalse(viewModel.uiState.value.isSaveEnabled)
        assertEquals("Systolic must be between 60 and 200", viewModel.uiState.value.validationError)
    }

    @Test
    fun `diastolic out of range shows error`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("190", "181")

        assertFalse(viewModel.uiState.value.isSaveEnabled)
        assertEquals("Diastolic must be between 30 and 180", viewModel.uiState.value.validationError)
    }

    @Test
    fun `systolic not greater than diastolic shows error`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("80", "120")

        assertFalse(viewModel.uiState.value.isSaveEnabled)
        assertEquals("Systolic must be greater than diastolic", viewModel.uiState.value.validationError)
    }

    @Test
    fun `heart rate out of range shows error and disables Save`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("120", "80", heartRate = "251")

        assertFalse(viewModel.uiState.value.isSaveEnabled)
        assertEquals("Heart rate must be between 30 and 250", viewModel.uiState.value.validationError)
    }

    @Test
    fun `valid heart rate keeps Save enabled`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("120", "80", heartRate = "72")

        assertTrue(viewModel.uiState.value.isSaveEnabled)
        assertEquals("72", viewModel.uiState.value.heartRate)
    }

    @Test
    fun `non-digit characters are stripped from numeric fields`() = runTest {
        viewModel = createViewModel()
        viewModel.enter("1 2-0", "8.0", heartRate = "7,2")

        val state = viewModel.uiState.value
        assertEquals("120", state.systolic)
        assertEquals("80", state.diastolic)
        assertEquals("72", state.heartRate)
    }

    @Test
    fun `updateBodyPosition and updateMeasurementLocation update state`() = runTest {
        viewModel = createViewModel()
        viewModel.updateBodyPosition(BodyPosition.STANDING_UP)
        viewModel.updateMeasurementLocation(MeasurementLocation.RIGHT_WRIST)

        assertEquals(BodyPosition.STANDING_UP, viewModel.uiState.value.bodyPosition)
        assertEquals(MeasurementLocation.RIGHT_WRIST, viewModel.uiState.value.measurementLocation)
    }

    @Test
    fun `edits survive process death via SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        viewModel = createViewModel(handle)
        viewModel.enter("135", "85", heartRate = "64")
        viewModel.updateBodyPosition(BodyPosition.STANDING_UP)
        viewModel.updateMeasurementLocation(MeasurementLocation.RIGHT_WRIST)

        val restored = createViewModel(handle)
        advanceUntilIdle()

        val state = restored.uiState.value
        assertEquals("135", state.systolic)
        assertEquals("85", state.diastolic)
        assertEquals("64", state.heartRate)
        assertEquals(BodyPosition.STANDING_UP, state.bodyPosition)
        assertEquals(MeasurementLocation.RIGHT_WRIST, state.measurementLocation)
        assertTrue(state.isSaveEnabled)
    }

    // --- save ---

    @Test
    fun `save passes the reading including heart rate to the use case`() = runTest {
        coEvery { useCase.invoke(any()) } returns allSucceeded
        viewModel = createViewModel()
        viewModel.enter("120", "80", heartRate = "72")
        viewModel.updateBodyPosition(BodyPosition.LYING_DOWN)

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            useCase.invoke(
                match { reading: BloodPressureReading ->
                    reading.systolic == 120 && reading.diastolic == 80 && reading.heartRate == 72 &&
                        reading.bodyPosition == BodyPosition.LYING_DOWN
                },
            )
        }
    }

    @Test
    fun `save without heart rate passes null heart rate`() = runTest {
        coEvery { useCase.invoke(any()) } returns allSucceeded
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.save()
        advanceUntilIdle()

        coVerify { useCase.invoke(match { reading: BloodPressureReading -> reading.heartRate == null }) }
    }

    @Test
    fun `save on success navigates home with the saved values`() = runTest {
        coEvery { useCase.invoke(any()) } returns allSucceeded
        viewModel = createViewModel()
        viewModel.enter("120", "80", heartRate = "72")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            assertEquals("120/80 mmHg, 72 bpm saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.uiState.value.isSaving)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `save when heart rate write failed warns but still navigates home`() = runTest {
        coEvery { useCase.invoke(any()) } returns allSucceeded.copy(heartRateSuccess = false)
        viewModel = createViewModel()
        viewModel.enter("120", "80", heartRate = "72")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            assertEquals(
                "120/80 mmHg, 72 bpm saved; heart rate could not be written to Health Connect",
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with HC fail and FS success navigates home with warning`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            assertEquals("120/80 mmHg saved to food-scanner, but could not write to Health Connect", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `save with HC success and FS fail navigates home with warning`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            assertEquals("120/80 mmHg saved to Health Connect, but could not sync to food-scanner", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with both fail sets error and does not navigate`() = runTest {
        coEvery { useCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals("Failed to save reading. Please try again.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `save exception sets generic error message`() = runTest {
        coEvery { useCase.invoke(any()) } throws RuntimeException("Write failed")
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.save()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `save while already saving is ignored`() = runTest {
        var callCount = 0
        coEvery { useCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            allSucceeded
        }
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.save()
        testDispatcher.scheduler.advanceTimeBy(100)
        viewModel.save()
        testDispatcher.scheduler.advanceTimeBy(100)

        assertEquals(1, callCount)
    }

    @Test
    fun `save with an incomplete form does nothing`() = runTest {
        viewModel = createViewModel()
        viewModel.updateSystolic("120")

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { useCase.invoke(any()) }
    }

    @Test
    fun `navigateHome event is buffered until a collector subscribes`() = runTest {
        coEvery { useCase.invoke(any()) } returns allSucceeded
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.save()
        advanceUntilIdle()

        viewModel.navigateHome.test {
            assertEquals("120/80 mmHg saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- scanning ---

    @Test
    fun `scanned photo fills systolic, diastolic and heart rate`() = runTest {
        coEvery { scanner.scanBloodPressure(imageBytes) } returns
            ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 132, diastolic = 84, heartRate = 68))
        viewModel = createViewModel()

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("132", state.systolic)
        assertEquals("84", state.diastolic)
        assertEquals("68", state.heartRate)
        assertTrue(state.filledFromPhoto)
        assertTrue(state.isSaveEnabled)
        assertFalse(state.isScanning)
        assertNull(state.scanError)
    }

    @Test
    fun `scan without heart rate keeps a heart rate the user already typed`() = runTest {
        coEvery { scanner.scanBloodPressure(imageBytes) } returns
            ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 132, diastolic = 84, heartRate = null))
        viewModel = createViewModel()
        viewModel.updateHeartRate("70")

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()

        assertEquals("70", viewModel.uiState.value.heartRate)
    }

    @Test
    fun `scan failure shows the message and leaves the form untouched`() = runTest {
        coEvery { scanner.scanBloodPressure(imageBytes) } returns ScanOutcome.Failure("No connection.")
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("No connection.", state.scanError)
        assertEquals("120", state.systolic)
        assertFalse(state.filledFromPhoto)
        assertFalse(state.isScanning)
    }

    @Test
    fun `isScanning is true while the scan runs and Save is blocked`() = runTest {
        val gate = CompletableDeferred<ScanOutcome<BloodPressureParseResult.Success>>()
        coEvery { scanner.scanBloodPressure(imageBytes) } coAnswers { gate.await() }
        coEvery { useCase.invoke(any()) } returns allSucceeded
        viewModel = createViewModel()
        viewModel.enter("120", "80")

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.save()
        advanceUntilIdle()
        coVerify(exactly = 0) { useCase.invoke(any()) }

        gate.complete(ScanOutcome.Failure("x"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isScanning)
    }

    @Test
    fun `second photo while scanning is ignored`() = runTest {
        val gate = CompletableDeferred<ScanOutcome<BloodPressureParseResult.Success>>()
        coEvery { scanner.scanBloodPressure(any()) } coAnswers { gate.await() }
        viewModel = createViewModel()

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()
        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()
        gate.complete(ScanOutcome.Failure("x"))
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.scanBloodPressure(any()) }
    }

    @Test
    fun `a new scan clears the previous scan error`() = runTest {
        coEvery { scanner.scanBloodPressure(imageBytes) } returnsMany listOf(
            ScanOutcome.Failure("Service busy"),
            ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 120, diastolic = 80)),
        )
        viewModel = createViewModel()

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()
        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.scanError)
        assertTrue(viewModel.uiState.value.filledFromPhoto)
    }

    @Test
    fun `onCaptureError shows the camera error`() = runTest {
        viewModel = createViewModel()

        viewModel.onCaptureError("Could not open camera.")

        assertEquals("Could not open camera.", viewModel.uiState.value.scanError)
    }

    // --- shared image ---

    @Test
    fun `shared image path is read and scanned on open`() = runTest {
        coEvery { scanner.readSharedImage("/cache/shared.jpg") } returns imageBytes
        coEvery { scanner.scanBloodPressure(imageBytes) } returns
            ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 118, diastolic = 76, heartRate = 60))

        viewModel = createViewModel(SavedStateHandle(mapOf(BpEntryViewModel.ARG_SHARED_PATH to "/cache/shared.jpg")))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("118", state.systolic)
        assertEquals("76", state.diastolic)
        assertEquals("60", state.heartRate)
    }

    @Test
    fun `shared image is scanned only once across ViewModel recreation`() = runTest {
        coEvery { scanner.readSharedImage(any()) } returns imageBytes
        coEvery { scanner.scanBloodPressure(imageBytes) } returns
            ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 118, diastolic = 76))
        val handle = SavedStateHandle(mapOf(BpEntryViewModel.ARG_SHARED_PATH to "/cache/shared.jpg"))

        createViewModel(handle)
        advanceUntilIdle()
        createViewModel(handle)
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.readSharedImage(any()) }
    }

    @Test
    fun `shared image that is too large shows a scan error`() = runTest {
        coEvery { scanner.readSharedImage(any()) } throws ImageTooLargeException(20 * 1024 * 1024)

        viewModel = createViewModel(SavedStateHandle(mapOf(BpEntryViewModel.ARG_SHARED_PATH to "/cache/big.jpg")))
        advanceUntilIdle()

        assertEquals("Image is too large. Please choose a smaller photo.", viewModel.uiState.value.scanError)
        assertFalse(viewModel.uiState.value.isScanning)
        coVerify(exactly = 0) { scanner.scanBloodPressure(any()) }
    }

    @Test
    fun `unreadable shared image shows a scan error`() = runTest {
        coEvery { scanner.readSharedImage(any()) } throws java.io.FileNotFoundException("gone")

        viewModel = createViewModel(SavedStateHandle(mapOf(BpEntryViewModel.ARG_SHARED_PATH to "/cache/gone.jpg")))
        advanceUntilIdle()

        assertEquals("Could not read shared image.", viewModel.uiState.value.scanError)
    }

    @Test
    fun `typing while a scan runs is ignored so the result cannot silently overwrite it`() = runTest {
        val gate = CompletableDeferred<ScanOutcome<BloodPressureParseResult.Success>>()
        coEvery { scanner.scanBloodPressure(imageBytes) } coAnswers { gate.await() }
        viewModel = createViewModel()

        viewModel.onPhotoCaptured(imageBytes)
        advanceUntilIdle()
        viewModel.enter("128", "82", heartRate = "70")
        assertEquals("", viewModel.uiState.value.systolic)

        gate.complete(ScanOutcome.Success(BloodPressureParseResult.Success(systolic = 132, diastolic = 84)))
        advanceUntilIdle()
        assertEquals("132", viewModel.uiState.value.systolic)
        assertEquals("84", viewModel.uiState.value.diastolic)
    }
}
