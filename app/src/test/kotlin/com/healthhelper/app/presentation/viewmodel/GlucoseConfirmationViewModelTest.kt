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
class GlucoseConfirmationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var writeUseCase: WriteGlucoseReadingUseCase
    private lateinit var inferDefaultsUseCase: InferGlucoseDefaultsUseCase
    private lateinit var viewModel: GlucoseConfirmationViewModel

    private val unknownDefaults = GlucoseDefaults(
        relationToMeal = RelationToMeal.UNKNOWN,
        glucoseMealType = GlucoseMealType.UNKNOWN,
        specimenSource = SpecimenSource.CAPILLARY_BLOOD,
    )

    private fun createSavedStateHandle(
        value: Float = 5.6f,
        unit: String = "mmol/L",
        detectedUnit: String = "mmol/L",
    ) = SavedStateHandle(mapOf("value" to value, "unit" to unit, "detectedUnit" to detectedUnit))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        writeUseCase = mockk()
        inferDefaultsUseCase = mockk()
        coEvery { inferDefaultsUseCase.invoke(any()) } returns unknownDefaults
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        value: Float = 5.6f,
        unit: String = "mmol/L",
        detectedUnit: String = "mmol/L",
    ) = GlucoseConfirmationViewModel(
        createSavedStateHandle(value, unit, detectedUnit),
        writeUseCase,
        inferDefaultsUseCase,
    )

    @Test
    fun `initial state populated from SavedStateHandle in mmol_L`() = runTest {
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("101", state.valueMgDl)
            assertEquals("5.6", state.displayMmolL)
            assertEquals(GlucoseUnit.MMOL_L, state.detectedUnit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state with mg_dL input uses value directly`() = runTest {
        viewModel = createViewModel(101f, "mg/dL", "mg/dL")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("101", state.valueMgDl)
            assertEquals("5.6", state.displayMmolL)
            assertEquals(GlucoseUnit.MG_DL, state.detectedUnit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state displays correct mmol_L for 5_6 mmol_L input`() = runTest {
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("5.6", state.displayMmolL)
            cancelAndIgnoreRemainingEvents()
        }
    }

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
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
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
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
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
        coEvery { writeUseCase.invoke(any()) } returns HealthDataWriteResult(
            healthConnectSuccess = true,
            foodScannerResult = Result.failure(RuntimeException("sync failed")),
        )
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
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
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
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
}
