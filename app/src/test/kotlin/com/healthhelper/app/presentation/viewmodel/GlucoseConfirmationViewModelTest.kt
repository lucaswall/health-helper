package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.SpecimenSource
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
    private lateinit var useCase: WriteGlucoseReadingUseCase
    private lateinit var viewModel: GlucoseConfirmationViewModel

    private fun createSavedStateHandle(
        value: Float = 5.6f,
        unit: String = "mmol/L",
        detectedUnit: String = "mmol/L",
    ) = SavedStateHandle(mapOf("value" to value, "unit" to unit, "detectedUnit" to detectedUnit))

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
        value: Float = 5.6f,
        unit: String = "mmol/L",
        detectedUnit: String = "mmol/L",
    ) = GlucoseConfirmationViewModel(createSavedStateHandle(value, unit, detectedUnit), useCase)

    @Test
    fun `initial state populated from SavedStateHandle in mmol_L`() = runTest {
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("5.6", state.valueMmolL)
            assertEquals("101", state.displayMgDl)
            assertEquals(GlucoseUnit.MMOL_L, state.detectedUnit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state converts mg_dL value to mmol_L`() = runTest {
        viewModel = createViewModel(101f, "mg/dL", "mg/dL")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("5.6", state.valueMmolL)
            assertEquals("101", state.displayMgDl)
            assertEquals(GlucoseUnit.MG_DL, state.detectedUnit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dual-unit display shows correct mg_dL for 5_6 mmol_L`() = runTest {
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("101", state.displayMgDl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateValue updates both mmol_L and mg_dL displays`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("6.0")

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("6.0", state.valueMmolL)
            assertEquals("108", state.displayMgDl)
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
    fun `validation error when value below 1_0 mmol_L`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("0.5")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSaveEnabled)
            assertNotNull(state.validationError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `validation error when value above 40_0 mmol_L`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.updateValue("41.0")

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
    fun `save success emits navigateHome event with mmol_L value`() = runTest {
        coEvery { useCase.invoke(any()) } returns true
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.navigateHome.test {
            viewModel.save()
            advanceUntilIdle()
            val msg = awaitItem()
            assertTrue(msg.contains("5.6"))
            assertTrue(msg.contains("mmol/L"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save failure sets error message`() = runTest {
        coEvery { useCase.invoke(any()) } returns false
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
        coEvery { useCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            true
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
        coEvery { useCase.invoke(any()) } throws RuntimeException("Write failed")
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
        coEvery { useCase.invoke(any()) } returns true
        viewModel = createViewModel(5.6f, "mmol/L", "mmol/L")
        advanceTimeBy(1_000)

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            useCase.invoke(
                match { reading ->
                    reading.valueMmolL in 5.59..5.61
                },
            )
        }
    }
}
