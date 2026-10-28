package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.healthhelper.app.domain.model.GlucoseDefaults
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.usecase.InferGlucoseDefaultsUseCase
import com.healthhelper.app.domain.usecase.WriteGlucoseReadingUseCase
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.presentation.image.ReadingImageScanner
import com.healthhelper.app.presentation.image.ScanOutcome
import kotlinx.coroutines.CompletableDeferred
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
class GlucoseEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var writeUseCase: WriteGlucoseReadingUseCase
    private lateinit var inferDefaultsUseCase: InferGlucoseDefaultsUseCase
    private lateinit var scanner: ReadingImageScanner
    private lateinit var viewModel: GlucoseEntryViewModel

    private val unknownDefaults = GlucoseDefaults(
        relationToMeal = RelationToMeal.UNKNOWN,
        glucoseMealType = GlucoseMealType.UNKNOWN,
        specimenSource = SpecimenSource.CAPILLARY_BLOOD,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        writeUseCase = mockk()
        inferDefaultsUseCase = mockk()
        coEvery { inferDefaultsUseCase.invoke(any()) } returns unknownDefaults
        scanner = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Creates the ViewModel and, unless [initialMgDl] is null, types that value as the user would. */
    private fun createViewModel(
        initialMgDl: String? = "101",
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = GlucoseEntryViewModel(
        savedStateHandle,
        writeUseCase,
        inferDefaultsUseCase,
        scanner,
    ).also { vm -> initialMgDl?.let(vm::updateValue) }

    @Test
    fun `updateValue updates both mg_dL and mmol_L displays`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("108")

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("108", state.valueMgDl)
            assertEquals("6.0", state.displayMmolL)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateValue with invalid input disables save`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("abc")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateValue with empty input disables save`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `validation error when value below 18 mg_dL`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("17")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            assertNotNull(state.validationError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `validation error when value above 720 mg_dL`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("721")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            assertNotNull(state.validationError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateRelationToMeal updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.FASTING)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.FASTING, state.relationToMeal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateGlucoseMealType updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateGlucoseMealType(GlucoseMealType.BREAKFAST)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(GlucoseMealType.BREAKFAST, state.glucoseMealType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateSpecimenSource updates state`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateSpecimenSource(SpecimenSource.CAPILLARY_BLOOD)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(SpecimenSource.CAPILLARY_BLOOD, state.specimenSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mealTypeVisible true when relation is BEFORE_MEAL`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.BEFORE_MEAL)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mealTypeVisible true when relation is AFTER_MEAL`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.AFTER_MEAL)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mealTypeVisible false when relation is GENERAL`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.GENERAL)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save success emits navigateHome event with mg_dL value`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertTrue(msg.contains("101"))
            assertTrue(msg.contains("mg/dL"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with both succeed clears isSaving and no error`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
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
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertEquals("101 mg/dL saved to food-scanner, but could not write to Health Connect", msg)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with HC success and FS fail navigates home with warning`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertEquals("101 mg/dL saved to Health Connect, but could not sync to food-scanner", msg)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.error)
            assertFalse(state.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save with both fail sets error and does not navigate`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel()
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
    fun `save failure sets error message`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = false,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel()
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
        coEvery { writeUseCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            HealthDataWriteResult(
                healthConnectSuccess = true,
                foodScannerResult = Result.success(Unit),
            )
        }
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.save()
        testDispatcher.scheduler.advanceTimeBy(100)
        viewModel.save()
        testDispatcher.scheduler.advanceTimeBy(100)

        assertEquals(1, callCount)
    }

    @Test
    fun `save exception sets generic error message`() = runTest {
        coEvery { writeUseCase.invoke(any()) } throws RuntimeException("Write failed")
        viewModel = createViewModel()
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
    fun `save calls use case with correct GlucoseReading`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            writeUseCase.invoke(
                match { reading ->
                    reading.valueMgDl == 101
                },
            )
        }
    }

    // --- Smart defaults tests ---

    @Test
    fun `initial state reflects AFTER_MEAL defaults when meal is recent`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns GlucoseDefaults(
            relationToMeal = RelationToMeal.AFTER_MEAL,
            glucoseMealType = GlucoseMealType.BREAKFAST,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.AFTER_MEAL, state.relationToMeal)
            assertEquals(GlucoseMealType.BREAKFAST, state.glucoseMealType)
            assertEquals(SpecimenSource.CAPILLARY_BLOOD, state.specimenSource)
            assertTrue(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state shows FASTING when meal is old`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns GlucoseDefaults(
            relationToMeal = RelationToMeal.FASTING,
            glucoseMealType = GlucoseMealType.UNKNOWN,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.FASTING, state.relationToMeal)
            assertEquals(SpecimenSource.CAPILLARY_BLOOD, state.specimenSource)
            assertFalse(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state shows UNKNOWN when no meals or middle window`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns unknownDefaults
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.UNKNOWN, state.relationToMeal)
            assertEquals(GlucoseMealType.UNKNOWN, state.glucoseMealType)
            assertEquals(SpecimenSource.CAPILLARY_BLOOD, state.specimenSource)
            assertFalse(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user can override inferred defaults`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns GlucoseDefaults(
            relationToMeal = RelationToMeal.AFTER_MEAL,
            glucoseMealType = GlucoseMealType.BREAKFAST,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.FASTING)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.FASTING, state.relationToMeal)
            assertFalse(state.mealTypeVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `infer defaults exception keeps UNKNOWN defaults`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } throws RuntimeException("error")
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(RelationToMeal.UNKNOWN, state.relationToMeal)
            assertEquals(GlucoseMealType.UNKNOWN, state.glucoseMealType)
            assertEquals(SpecimenSource.UNKNOWN, state.specimenSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `out-of-range value shows validation error`() = runTest {
        viewModel = createViewModel("1000")
        advanceTimeBy(1_000)

        val state = viewModel.uiState.value
        assertFalse(state.isSaveEnabled)
        assertEquals("Value must be between 18 and 720 mg/dL", state.validationError)
    }

    @Test
    fun `updateValue with NaN does not crash and disables save`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("NaN")

        // Letters are stripped, leaving a blank field: Save is disabled without an error message.
        val state = viewModel.uiState.value
        assertEquals("", state.valueMgDl)
        assertFalse(state.isSaveEnabled)
    }

    @Test
    fun `updateValue strips non-numeric characters`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("1e2d")

        assertEquals("12", viewModel.uiState.value.valueMgDl)
    }

    @Test
    fun `save message uses rounded saved value not raw input`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)
        viewModel.updateValue("100.6")

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            assertEquals("101 mg/dL saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching relation to FASTING resets meal type and saves UNKNOWN meal type`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns GlucoseDefaults(
            relationToMeal = RelationToMeal.AFTER_MEAL,
            glucoseMealType = GlucoseMealType.LUNCH,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateRelationToMeal(RelationToMeal.FASTING)
        assertEquals(GlucoseMealType.UNKNOWN, viewModel.uiState.value.glucoseMealType)

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            writeUseCase.invoke(
                match { reading ->
                    reading.relationToMeal == RelationToMeal.FASTING &&
                        reading.glucoseMealType == GlucoseMealType.UNKNOWN
                },
            )
        }
    }

    @Test
    fun `edits survive process death via SavedStateHandle`() = runTest {
        coEvery { inferDefaultsUseCase.invoke(any()) } returns unknownDefaults
        val handle = SavedStateHandle()
        viewModel = createViewModel(savedStateHandle = handle)
        advanceTimeBy(1_000)

        viewModel.updateValue("130")
        viewModel.updateRelationToMeal(RelationToMeal.BEFORE_MEAL)
        viewModel.updateGlucoseMealType(GlucoseMealType.DINNER)
        viewModel.updateSpecimenSource(SpecimenSource.INTERSTITIAL_FLUID)

        // Recreate with the same handle (simulates restore after process death)
        coEvery { inferDefaultsUseCase.invoke(any()) } returns GlucoseDefaults(
            relationToMeal = RelationToMeal.FASTING,
            glucoseMealType = GlucoseMealType.UNKNOWN,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
        val restored = createViewModel(initialMgDl = null, savedStateHandle = handle)
        advanceTimeBy(1_000)

        val state = restored.uiState.value
        assertEquals("130", state.valueMgDl)
        assertEquals(RelationToMeal.BEFORE_MEAL, state.relationToMeal)
        assertEquals(GlucoseMealType.DINNER, state.glucoseMealType)
        assertEquals(SpecimenSource.INTERSTITIAL_FLUID, state.specimenSource)
        assertTrue(state.mealTypeVisible)
    }

    @Test
    fun `navigateHome event is buffered until a collector subscribes`() = runTest {
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.success(Unit),
        )
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        viewModel.navigateHome.test {
            assertEquals("101 mg/dL saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- entry screen / scanning ---

    @Test
    fun `opens with an empty value, Save disabled and no validation error`() = runTest {
        viewModel = createViewModel(initialMgDl = null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.valueMgDl)
        assertEquals("", state.displayMmolL)
        assertFalse(state.isSaveEnabled)
        assertNull(state.validationError)
        assertNull(state.conversionNote)
    }

    @Test
    fun `typed mg_dL value shows the mmol_L equivalent`() = runTest {
        viewModel = createViewModel("101")

        assertEquals("5.6", viewModel.uiState.value.displayMmolL)
        assertTrue(viewModel.uiState.value.isSaveEnabled)
    }

    @Test
    fun `scanned mmol_L reading is converted to mg_dL with a conversion note`() = runTest {
        coEvery { scanner.scanGlucose(any()) } returns
            ScanOutcome.Success(GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L))
        viewModel = createViewModel(initialMgDl = null)

        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("101", state.valueMgDl)
        assertEquals("Converted from 5.6 mmol/L", state.conversionNote)
        assertTrue(state.filledFromPhoto)
        assertTrue(state.isSaveEnabled)
        assertFalse(state.isScanning)
    }

    @Test
    fun `scanned mg_dL reading has no conversion note`() = runTest {
        coEvery { scanner.scanGlucose(any()) } returns
            ScanOutcome.Success(GlucoseParseResult.Success(value = 98.0, detectedUnit = GlucoseUnit.MG_DL))
        viewModel = createViewModel(initialMgDl = null)

        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()

        assertEquals("98", viewModel.uiState.value.valueMgDl)
        assertNull(viewModel.uiState.value.conversionNote)
    }

    @Test
    fun `editing the scanned value clears the conversion note`() = runTest {
        coEvery { scanner.scanGlucose(any()) } returns
            ScanOutcome.Success(GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L))
        viewModel = createViewModel(initialMgDl = null)
        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()

        viewModel.updateValue("105")

        assertNull(viewModel.uiState.value.conversionNote)
    }

    @Test
    fun `conversion note survives process death`() = runTest {
        coEvery { scanner.scanGlucose(any()) } returns
            ScanOutcome.Success(GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L))
        val handle = SavedStateHandle()
        viewModel = createViewModel(initialMgDl = null, savedStateHandle = handle)
        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()

        val restored = createViewModel(initialMgDl = null, savedStateHandle = handle)
        advanceUntilIdle()

        assertEquals("101", restored.uiState.value.valueMgDl)
        assertEquals("Converted from 5.6 mmol/L", restored.uiState.value.conversionNote)
    }

    @Test
    fun `scan failure shows the message and keeps the typed value`() = runTest {
        coEvery { scanner.scanGlucose(any()) } returns ScanOutcome.Failure("Service busy, try again shortly.")
        viewModel = createViewModel("110")

        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Service busy, try again shortly.", state.scanError)
        assertEquals("110", state.valueMgDl)
        assertFalse(state.isScanning)
    }

    @Test
    fun `Save is blocked while a scan is running`() = runTest {
        val gate = CompletableDeferred<ScanOutcome<GlucoseParseResult.Success>>()
        coEvery { scanner.scanGlucose(any()) } coAnswers { gate.await() }
        viewModel = createViewModel("110")

        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isScanning)
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { writeUseCase.invoke(any()) }
        gate.complete(ScanOutcome.Failure("x"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isScanning)
    }

    @Test
    fun `onCaptureError shows the camera error`() = runTest {
        viewModel = createViewModel()

        viewModel.onCaptureError("Could not open camera.")

        assertEquals("Could not open camera.", viewModel.uiState.value.scanError)
    }

    @Test
    fun `shared image is read and scanned once on open`() = runTest {
        coEvery { scanner.readSharedImage("/cache/shared.jpg") } returns byteArrayOf(9)
        coEvery { scanner.scanGlucose(any()) } returns
            ScanOutcome.Success(GlucoseParseResult.Success(value = 120.0, detectedUnit = GlucoseUnit.MG_DL))
        val handle = SavedStateHandle(mapOf(GlucoseEntryViewModel.ARG_SHARED_PATH to "/cache/shared.jpg"))

        viewModel = createViewModel(initialMgDl = null, savedStateHandle = handle)
        advanceUntilIdle()
        createViewModel(initialMgDl = null, savedStateHandle = handle)
        advanceUntilIdle()

        assertEquals("120", viewModel.uiState.value.valueMgDl)
        coVerify(exactly = 1) { scanner.readSharedImage(any()) }
    }

    @Test
    fun `unreadable shared image shows a scan error`() = runTest {
        coEvery { scanner.readSharedImage(any()) } throws java.io.FileNotFoundException("gone")
        val handle = SavedStateHandle(mapOf(GlucoseEntryViewModel.ARG_SHARED_PATH to "/cache/gone.jpg"))

        viewModel = createViewModel(initialMgDl = null, savedStateHandle = handle)
        advanceUntilIdle()

        assertEquals("Could not read shared image.", viewModel.uiState.value.scanError)
        coVerify(exactly = 0) { scanner.scanGlucose(any()) }
    }

    @Test
    fun `typing while a scan runs is ignored`() = runTest {
        val gate = CompletableDeferred<ScanOutcome<GlucoseParseResult.Success>>()
        coEvery { scanner.scanGlucose(any()) } coAnswers { gate.await() }
        viewModel = createViewModel(initialMgDl = null)

        viewModel.onPhotoCaptured(byteArrayOf(1))
        advanceUntilIdle()
        viewModel.updateValue("130")

        assertEquals("", viewModel.uiState.value.valueMgDl)
        gate.complete(ScanOutcome.Failure("x"))
        advanceUntilIdle()
    }
}
