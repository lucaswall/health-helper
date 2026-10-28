package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.usecase.LogWaistReadingUseCase
import com.healthhelper.app.domain.usecase.WaistSaveResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WaistEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var useCase: LogWaistReadingUseCase

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        useCase = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()) = WaistEntryViewModel(handle, useCase)

    @Test
    fun `opens empty with Save disabled and no error`() = runTest {
        val state = createViewModel().uiState.value
        assertEquals("", state.valueCm)
        assertFalse(state.isSaveEnabled)
        assertNull(state.validationError)
    }

    @Test
    fun `valid value enables Save`() = runTest {
        val vm = createViewModel()
        vm.updateValue("104.5")
        assertTrue(vm.uiState.value.isSaveEnabled)
        assertNull(vm.uiState.value.validationError)
    }

    @Test
    fun `comma is accepted as decimal separator and extra decimals are dropped`() = runTest {
        val vm = createViewModel()
        vm.updateValue("104,57")
        assertEquals("104.5", vm.uiState.value.valueCm)
    }

    @Test
    fun `non-digits and a fourth integer digit are stripped`() = runTest {
        val vm = createViewModel()
        vm.updateValue("1a04 5")
        assertEquals("104", vm.uiState.value.valueCm)
    }

    @Test
    fun `out of range value shows error`() = runTest {
        val vm = createViewModel()
        vm.updateValue("30")
        assertFalse(vm.uiState.value.isSaveEnabled)
        assertEquals("Waist must be between 40 and 250 cm", vm.uiState.value.validationError)
    }

    @Test
    fun `save sends the reading and navigates home when synced`() = runTest {
        coEvery { useCase(any()) } returns WaistSaveResult.SYNCED
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.navigateHome.test {
            vm.save()
            advanceUntilIdle()
            assertEquals("Waist 104.5 cm saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { useCase(match { r: WaistReading -> r.valueCm == 104.5 && r.zoneOffset != null }) }
    }

    @Test
    fun `save while offline still navigates home and says it will sync`() = runTest {
        coEvery { useCase(any()) } returns WaistSaveResult.PENDING
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.navigateHome.test {
            vm.save()
            advanceUntilIdle()
            assertEquals("Waist 104.5 cm saved on this phone; it will sync to food-scanner when online", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save without food-scanner configured says to set it up`() = runTest {
        coEvery { useCase(any()) } returns WaistSaveResult.PENDING_NOT_CONFIGURED
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.navigateHome.test {
            vm.save()
            advanceUntilIdle()
            assertEquals("Waist 104.5 cm saved on this phone; set up food-scanner in Settings to sync it", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Save stays disabled after a successful save so a second tap can't log twice`() = runTest {
        coEvery { useCase(any()) } returns WaistSaveResult.SYNCED
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.save()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isSaving)
        coVerify(exactly = 1) { useCase(any()) }
    }

    @Test
    fun `failed local save shows an error and stays on the screen`() = runTest {
        coEvery { useCase(any()) } returns WaistSaveResult.FAILED
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.navigateHome.test {
            vm.save()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals("Could not save the reading. Please try again.", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `save while already saving is ignored`() = runTest {
        var calls = 0
        coEvery { useCase(any()) } coAnswers {
            calls++
            kotlinx.coroutines.delay(1_000)
            WaistSaveResult.SYNCED
        }
        val vm = createViewModel()
        vm.updateValue("104.5")

        vm.save()
        testDispatcher.scheduler.advanceTimeBy(100)
        vm.save()
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `typed value survives process death`() = runTest {
        val handle = SavedStateHandle()
        createViewModel(handle).updateValue("101.5")

        val restored = createViewModel(handle)
        assertEquals("101.5", restored.uiState.value.valueCm)
        assertTrue(restored.uiState.value.isSaveEnabled)
    }
}
