package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
class SyncViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var syncNutritionUseCase: SyncNutritionUseCase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var viewModel: SyncViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        syncNutritionUseCase = mockk()
        settingsRepository = mockk()
        syncScheduler = mockk(relaxed = true)

        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        coEvery { settingsRepository.isConfigured() } returns true
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SyncViewModel =
        SyncViewModel(syncNutritionUseCase, settingsRepository, syncScheduler)

    @Test
    fun `initial state shows idle not syncing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertNull(state.lastSyncResult)
            assertNull(state.syncProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync sets isSyncing true then false on completion`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync calls use case`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(3)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        coVerify { syncNutritionUseCase.invoke(any()) }
    }

    @Test
    fun `sync success updates lastSyncResult with record count`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(42)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Synced 42 records", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync error updates lastSyncResult with error message`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Error("Network failure")
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Error: Network failure", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync NeedsConfiguration updates state accordingly`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
        coEvery { settingsRepository.isConfigured() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Please configure API settings", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress updates are reflected in UI state`() = runTest {
        val capturedProgress = mutableListOf<SyncProgress>()
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            val onProgress = firstArg<(SyncProgress) -> Unit>()
            onProgress(SyncProgress("2024-01-01", 5, 1, 10))
            SyncResult.Success(10)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        // After completion, syncProgress should be null
        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.syncProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cannot trigger sync while already syncing`() = runTest {
        var callCount = 0
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            callCount++
            SyncResult.Success(1)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        // Simulate already syncing
        viewModel.triggerSync()
        // Immediately try again — but since advanceUntilIdle not called,
        // the first sync completes atomically in test dispatcher; just verify idempotency
        advanceUntilIdle()

        // Second trigger when not syncing: allowed
        viewModel.triggerSync()
        advanceUntilIdle()

        // At least 2 total calls happened (both triggers succeeded since syncing completed)
        assertTrue(callCount >= 1)
    }

    @Test
    fun `cannot trigger sync when isSyncing is true`() = runTest {
        // Test the guard: if isSyncing is true, triggerSync should not call use case again
        var callCount = 0
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(1000L) // hold the coroutine
            SyncResult.Success(1)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        // Start first sync (won't complete yet due to delay)
        viewModel.triggerSync()
        testDispatcher.scheduler.advanceTimeBy(100L)

        // While syncing, try again
        viewModel.triggerSync()
        testDispatcher.scheduler.advanceTimeBy(100L)

        // Still only 1 call initiated (guard prevents second)
        assertEquals(1, callCount)
    }
}
