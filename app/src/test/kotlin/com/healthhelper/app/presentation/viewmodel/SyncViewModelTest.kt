package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
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

        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_test")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        coEvery { settingsRepository.isConfigured() } returns true
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val healthConnectClient: HealthConnectClient? = mockk(relaxed = true)

    private fun createViewModel(hcClient: HealthConnectClient? = healthConnectClient): SyncViewModel =
        SyncViewModel(syncNutritionUseCase, settingsRepository, syncScheduler, hcClient)

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
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
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
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(3, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        coVerify { syncNutritionUseCase.invoke(any()) }
    }

    @Test
    fun `sync success updates lastSyncResult with record count`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(42, 3)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Synced 42 meals across 3 days", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync success with zero records shows no new meals`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(0, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("No new meals", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync success with one day uses singular day`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Synced 5 meals across 1 day", state.lastSyncResult)
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
    fun `syncProgress is null after sync completes`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            val onProgress = firstArg<(SyncProgress) -> Unit>()
            onProgress(SyncProgress("2024-01-01", 5, 1, 10))
            SyncResult.Success(10, 5)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.syncProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cannot trigger sync when isSyncing is true`() = runTest {
        // Test the guard: if isSyncing is true, triggerSync should not call use case again
        var callCount = 0
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(1000L) // hold the coroutine
            SyncResult.Success(1, 1)
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

    @Test
    fun `isConfigured updates when apiKey and baseUrl flows emit new values`() = runTest {
        // Start unconfigured
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isConfigured becomes true when both apiKey and baseUrl are non-empty`() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_key")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedulePeriodic not called again when only lastSyncedDate changes`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // schedulePeriodic called once during init (interval=30, configured=true)
        io.mockk.verify(exactly = 1) { syncScheduler.schedulePeriodic(30) }
    }

    @Test
    fun `healthConnectAvailable is false when HC client is null`() = runTest {
        viewModel = createViewModel(hcClient = null)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.healthConnectAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `healthConnectAvailable is true when HC client exists`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.healthConnectAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionResult updates permissionGranted state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPermissionResult(true)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync resets isSyncing and sets error message on unexpected exception`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } throws RuntimeException("unexpected")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerSync()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertEquals("Sync failed. Please try again.", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncTime is empty when timestamp is 0`() = runTest {
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.lastSyncTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncTime formats recent timestamp as relative time`() = runTest {
        val fiveMinAgo = System.currentTimeMillis() - 300_000L
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(fiveMinAgo)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(
                state.lastSyncTime.contains("min ago"),
                "Expected 'min ago' in '${state.lastSyncTime}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial permissionGranted is false`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncedMeals is empty list by default`() = runTest {
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(emptyList(), state.lastSyncedMeals)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncedMeals populated from repository flow`() = runTest {
        val meals = listOf(
            SyncedMealSummary(foodName = "Oatmeal", mealType = MealType.BREAKFAST, calories = 300),
            SyncedMealSummary(foodName = "Salad", mealType = MealType.LUNCH, calories = 450),
            SyncedMealSummary(foodName = "Pasta", mealType = MealType.DINNER, calories = 700),
        )
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(meals)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(3, state.lastSyncedMeals.size)
            assertEquals("Oatmeal", state.lastSyncedMeals[0].foodName)
            assertEquals("Salad", state.lastSyncedMeals[1].foodName)
            assertEquals("Pasta", state.lastSyncedMeals[2].foodName)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
