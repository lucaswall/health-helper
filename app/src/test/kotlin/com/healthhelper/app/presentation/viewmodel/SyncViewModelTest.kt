package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.HealthPermissionChecker
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.usecase.GetLastBloodPressureReadingUseCase
import com.healthhelper.app.domain.usecase.GetLastGlucoseReadingUseCase
import com.healthhelper.app.domain.usecase.GetTodayHydrationTotalUseCase
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
import com.healthhelper.app.domain.usecase.TodayHydrationResult
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.text.NumberFormat
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var getLastBpReadingUseCase: GetLastBloodPressureReadingUseCase
    private lateinit var getLastGlucoseReadingUseCase: GetLastGlucoseReadingUseCase
    private lateinit var getTodayHydrationTotalUseCase: GetTodayHydrationTotalUseCase
    private lateinit var syncHealthReadingsUseCase: SyncHealthReadingsUseCase
    private lateinit var permissionChecker: HealthPermissionChecker
    private lateinit var viewModel: SyncViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        syncNutritionUseCase = mockk()
        settingsRepository = mockk()
        syncScheduler = mockk(relaxed = true)
        getLastBpReadingUseCase = mockk()
        getLastGlucoseReadingUseCase = mockk()
        getTodayHydrationTotalUseCase = mockk()
        syncHealthReadingsUseCase = mockk(relaxed = true)
        permissionChecker = mockk()
        coEvery { permissionChecker.getGrantedPermissions() } returns emptySet()

        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_test")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")
        every { settingsRepository.lastGlucoseSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastBpSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(0)
        every { settingsRepository.bpSyncCountFlow } returns flowOf(0)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(0L)
        every { settingsRepository.bpSyncRunTimestampFlow } returns flowOf(0L)
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(0)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(0L)
        coEvery { settingsRepository.isConfigured() } returns true
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
        every { syncScheduler.getNextSyncTimeFlow() } returns flowOf(null)
        coEvery { getLastBpReadingUseCase.invoke() } returns null
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns null
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(0)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val healthConnectClient: HealthConnectClient? = mockk(relaxed = true)

    private fun createViewModel(hcClient: HealthConnectClient? = healthConnectClient): SyncViewModel =
        SyncViewModel(syncNutritionUseCase, syncHealthReadingsUseCase, settingsRepository, syncScheduler, getLastBpReadingUseCase, getLastGlucoseReadingUseCase, getTodayHydrationTotalUseCase, permissionChecker, hcClient)

    /**
     * Wraps [runTest] to cancel viewModelScope after the test body,
     * preventing runTest's internal advanceUntilIdle from hanging on
     * the ViewModel's periodic refresh coroutine (while(isActive) { delay(30_000) }).
     */
    private fun viewModelTest(testBody: suspend TestScope.() -> Unit) = runTest {
        try {
            testBody()
        } finally {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun `initial state shows idle not syncing`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertNull(state.lastSyncResult)
            assertNull(state.syncProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync sets isSyncing true then false on completion`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync calls use case`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(3, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        coVerify { syncNutritionUseCase.invoke(any()) }
    }

    @Test
    fun `sync success updates lastSyncResult with record count`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(42, 3)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Synced 42 meals across 3 days", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync success with zero records shows no new meals`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(0, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("No new meals", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync success with one day uses singular day`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Synced 5 meals across 1 day", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync error updates lastSyncResult with error message`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Error("Network failure")
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Network failure", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync NeedsConfiguration updates state accordingly`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
        coEvery { settingsRepository.isConfigured() } returns false

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Please configure API settings", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `syncProgress is null after sync completes`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            val onProgress = firstArg<(SyncProgress) -> Unit>()
            onProgress(SyncProgress("2024-01-01", 5, 1, 10))
            SyncResult.Success(10, 5)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.syncProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cannot trigger sync when isSyncing is true`() = viewModelTest {
        // Test the guard: if isSyncing is true, triggerSync should not call use case again
        var callCount = 0
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(1000L) // hold the coroutine
            SyncResult.Success(1, 1)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

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
    fun `cancelSync cancels running sync and shows cancelled message`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } coAnswers {
            kotlinx.coroutines.delay(5000L)
            SyncResult.Success(1, 1)
        }
        coEvery { settingsRepository.isConfigured() } returns true

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        testDispatcher.scheduler.advanceTimeBy(100L)

        // Sync should be running
        assertTrue(viewModel.uiState.value.isSyncing)

        // Cancel it
        viewModel.cancelSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertEquals("Sync cancelled", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelSync does nothing when not syncing`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        // Should not throw or change state
        viewModel.cancelSync()
        advanceTimeBy(100)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertNull(state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isConfigured updates when apiKey and baseUrl flows emit new values`() = viewModelTest {
        // Start unconfigured
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isConfigured becomes true when both apiKey and baseUrl are non-empty`() = viewModelTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_key")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedulePeriodic not called again when only lastSyncedDate changes`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        // schedulePeriodic called once during init (interval=30, configured=true)
        io.mockk.verify(exactly = 1) { syncScheduler.schedulePeriodic(30) }
    }

    @Test
    fun `healthConnectAvailable is false when HC client is null`() = viewModelTest {
        viewModel = createViewModel(hcClient = null)
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.healthConnectAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `healthConnectAvailable is true when HC client exists`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.healthConnectAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionResult updates permissionGranted state`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns SyncViewModel.REQUIRED_HC_PERMISSIONS
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPermissionResult(true, emptySet())

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync resets isSyncing and sets error message on unexpected exception`() = viewModelTest {
        coEvery { syncNutritionUseCase.invoke(any()) } throws RuntimeException("unexpected")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.triggerSync()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isSyncing)
            assertEquals("Sync failed. Please try again.", state.lastSyncResult)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncTime is empty when timestamp is 0`() = viewModelTest {
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.lastSyncTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncTime formats recent timestamp as relative time`() = viewModelTest {
        val fiveMinAgo = System.currentTimeMillis() - 300_000L
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(fiveMinAgo)
        viewModel = createViewModel()
        advanceTimeBy(1_000)

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
    fun `initial permissionGranted is false`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncedMeals is empty list by default`() = viewModelTest {
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(emptyList(), state.lastSyncedMeals)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSyncedMeals populated from repository flow`() = viewModelTest {
        val meals = listOf(
            SyncedMealSummary(foodName = "Oatmeal", mealType = MealType.BREAKFAST, calories = 300),
            SyncedMealSummary(foodName = "Salad", mealType = MealType.LUNCH, calories = 450),
            SyncedMealSummary(foodName = "Pasta", mealType = MealType.DINNER, calories = 700),
        )
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(meals)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(3, state.lastSyncedMeals.size)
            assertEquals("Oatmeal", state.lastSyncedMeals[0].foodName)
            assertEquals("Salad", state.lastSyncedMeals[1].foodName)
            assertEquals("Pasta", state.lastSyncedMeals[2].foodName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 2: Permission check on app launch ---

    @Test
    fun `permissionGranted is true on init when permission already granted`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns SyncViewModel.REQUIRED_HC_PERMISSIONS

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertTrue(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted stays false on init when permission not granted`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns emptySet()

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertFalse(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted stays false when getGrantedPermissions throws`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } throws RuntimeException("HC error")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertFalse(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 9: Next scheduled sync time ---

    @Test
    fun `nextSyncTime shows formatted time when sync is scheduled`() = viewModelTest {
        val futureTimeMs = System.currentTimeMillis() + 10 * 60 * 1000L
        every { syncScheduler.getNextSyncTimeFlow() } returns flowOf(futureTimeMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.nextSyncTime.startsWith("Next sync in ~"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nextSyncTime shows empty string when not configured`() = viewModelTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")
        every { syncScheduler.getNextSyncTimeFlow() } returns flowOf(null)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.nextSyncTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 7 (HEA-104): All HC permissions required ---

    @Test
    fun `permissionGranted is true only when ALL required HC permissions are granted`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns SyncViewModel.REQUIRED_HC_PERMISSIONS

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertTrue(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted is false when only WRITE_NUTRITION is granted`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns
            setOf(HealthPermission.getWritePermission(NutritionRecord::class))

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertFalse(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted is false when only BP permissions are granted`() = viewModelTest {
        coEvery { permissionChecker.getGrantedPermissions() } returns setOf(
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
        )

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertFalse(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cameraPermissionGranted is false by default`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.cameraPermissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCameraPermissionResult true sets cameraPermissionGranted to true`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onCameraPermissionResult(true)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.cameraPermissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCameraPermissionResult false sets cameraPermissionGranted to false`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onCameraPermissionResult(true)
        viewModel.onCameraPermissionResult(false)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.cameraPermissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 8 (HEA-105): Last BP reading on home screen ---

    @Test
    fun `lastBpReading is null by default`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.lastBpReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastBpReading populated after init loads from GetLastBloodPressureReadingUseCase`() = viewModelTest {
        val reading = BloodPressureReading(systolic = 120, diastolic = 80, timestamp = Instant.now())
        coEvery { getLastBpReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(reading, state.lastBpReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastBpReadingDisplay formats as systolic slash diastolic mmHg`() = viewModelTest {
        val reading = BloodPressureReading(systolic = 120, diastolic = 80, timestamp = Instant.now())
        coEvery { getLastBpReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("120/80 mmHg", state.lastBpReadingDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastBpReadingTime shows relative time when reading exists`() = viewModelTest {
        val twoHoursAgo = Instant.now().minusSeconds(7200)
        val reading = BloodPressureReading(systolic = 120, diastolic = 80, timestamp = twoHoursAgo)
        coEvery { getLastBpReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(
                state.lastBpReadingTime.contains("hr ago") || state.lastBpReadingTime.contains("min ago"),
                "Expected relative time in '${state.lastBpReadingTime}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastBpReadingDisplay is empty when no reading exists`() = viewModelTest {
        coEvery { getLastBpReadingUseCase.invoke() } returns null

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.lastBpReadingDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshLastBpReading reloads from use case`() = viewModelTest {
        val reading = BloodPressureReading(systolic = 130, diastolic = 85, timestamp = Instant.now())
        coEvery { getLastBpReadingUseCase.invoke() } returns null andThen reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.refreshLastBpReading()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(reading, state.lastBpReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Glucose reading on home screen ---

    @Test
    fun `lastGlucoseReading is null by default`() = viewModelTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.lastGlucoseReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastGlucoseReading populated after init loads from GetLastGlucoseReadingUseCase`() = viewModelTest {
        val reading = GlucoseReading(valueMgDl = 101, timestamp = Instant.now())
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(reading, state.lastGlucoseReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastGlucoseReadingDisplay formats as mg_dL primary`() = viewModelTest {
        val reading = GlucoseReading(valueMgDl = 101, timestamp = Instant.now())
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("101 mg/dL (5.6 mmol/L)", state.lastGlucoseReadingDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastGlucoseReadingTime shows relative time when reading exists`() = viewModelTest {
        val twoHoursAgo = Instant.now().minusSeconds(7200)
        val reading = GlucoseReading(valueMgDl = 101, timestamp = twoHoursAgo)
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(
                state.lastGlucoseReadingTime.contains("hr ago") || state.lastGlucoseReadingTime.contains("min ago"),
                "Expected relative time in '${state.lastGlucoseReadingTime}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastGlucoseReadingDisplay is empty when no reading exists`() = viewModelTest {
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns null

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.lastGlucoseReadingDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshLastGlucoseReading reloads from use case`() = viewModelTest {
        val reading = GlucoseReading(valueMgDl = 130, timestamp = Instant.now())
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns null andThen reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.refreshLastGlucoseReading()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(reading, state.lastGlucoseReading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 6 (HEA-190): Home screen sync status display ---

    @Test
    fun `glucoseSyncStatus shows pushed count when caughtUp true and count 342`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(342)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.glucoseSyncStatus.startsWith("Pushed 342 readings"), "Got: ${state.glucoseSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus shows pushed count with date when caughtUp false and count 100 and timestamp maps to Mar 15`() = viewModelTest {
        val mar15Ms = java.time.Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(100)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(mar15Ms)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Pushed 100 readings · Mar 15", state.glucoseSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus shows not synced when count 0 and caughtUp false`() = viewModelTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(0)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(false)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Not synced to food-scanner", state.glucoseSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus shows up to date when count 0 and caughtUp true and has run`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(0)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.glucoseSyncStatus.startsWith("Up to date"), "Got: ${state.glucoseSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bpSyncStatus shows pushed count when caughtUp true and count 342`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.bpSyncCountFlow } returns flowOf(342)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.bpSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.bpSyncStatus.startsWith("Pushed 342 readings"), "Got: ${state.bpSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bpSyncStatus shows pushed count with date when caughtUp false and count 100 and timestamp maps to Mar 15`() = viewModelTest {
        val mar15Ms = java.time.Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        every { settingsRepository.bpSyncCountFlow } returns flowOf(100)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.bpSyncRunTimestampFlow } returns flowOf(mar15Ms)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Pushed 100 readings · Mar 15", state.bpSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bpSyncStatus shows not synced when count 0 and caughtUp false`() = viewModelTest {
        every { settingsRepository.bpSyncCountFlow } returns flowOf(0)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(false)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Not synced to food-scanner", state.bpSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bpSyncStatus shows up to date when count 0 and caughtUp true and has run`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.bpSyncCountFlow } returns flowOf(0)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.bpSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.bpSyncStatus.startsWith("Up to date"), "Got: ${state.bpSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus shows not synced when run timestamp is 0`() = viewModelTest {
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(50)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(0L)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Not synced to food-scanner", state.glucoseSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus contains relative time when timestamp is positive`() = viewModelTest {
        val mar15Ms = java.time.Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(50)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(mar15Ms)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            val relativeTime = formatRelativeTime(mar15Ms)
            assertEquals("Pushed 50 readings · $relativeTime", state.glucoseSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- HEA-201: Hydration sync card ---

    @Test
    fun `hydrationTodayDisplay shows formatted total when use case returns positive value`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(1250)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            val expected = "${NumberFormat.getIntegerInstance().format(1250)} mL"
            assertEquals(expected, state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationTodayDisplay is empty when use case returns 0`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(0)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationSyncStatus shows not synced when run timestamp is 0`() = viewModelTest {
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(10)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(0L)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Not synced to food-scanner", state.hydrationSyncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationSyncStatus shows pushed count with relative time`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(12)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hydrationSyncStatus.startsWith("Pushed 12 readings"), "Got: ${state.hydrationSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationSyncStatus shows up to date when caught up`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(0)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hydrationSyncStatus.startsWith("Up to date"), "Got: ${state.hydrationSyncStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationHistoryStatus shows up to date when caught up`() = viewModelTest {
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(recentMs)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("History: up to date", state.hydrationHistoryStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationHistoryStatus shows relative age when backfilling`() = viewModelTest {
        val twoMonthsAgoMs = System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000)
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(twoMonthsAgoMs)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hydrationHistoryStatus.startsWith("History: synced to"), "Got: ${state.hydrationHistoryStatus}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationHistoryStatus shows today when watermark is recent`() = viewModelTest {
        val oneHourAgoMs = System.currentTimeMillis() - 3_600_000L
        val recentMs = System.currentTimeMillis() - 60_000L
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(oneHourAgoMs)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(recentMs)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("History: synced to today", state.hydrationHistoryStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hydrationHistoryStatus is empty when never synced`() = viewModelTest {
        every { settingsRepository.lastHydrationSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(false)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(0L)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.hydrationHistoryStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshTodayHydration reloads from use case`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(0) andThen TodayHydrationResult.Total(750)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.refreshTodayHydration()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            val expected = "${NumberFormat.getIntegerInstance().format(750)} mL"
            assertEquals(expected, state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 6 (HEA-210): Refresh sync status strings every 30s even when caught up ---
    // Note: formatRelativeTime uses System.currentTimeMillis() (real clock), so we verify the
    // loop runs the update path by ensuring the correct value is present in the next emission.

    @Test
    fun `hydrationSyncStatus is correct in 30s loop tick even when caught up`() = viewModelTest {
        val positiveTs = System.currentTimeMillis() - 5_000L
        every { settingsRepository.hydrationSyncCountFlow } returns flowOf(0)
        every { settingsRepository.hydrationSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.hydrationSyncRunTimestampFlow } returns flowOf(positiveTs)
        // Second call returns Total(200) so the loop's loadTodayHydration() causes a state emission
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns
            TodayHydrationResult.Total(0) andThen TodayHydrationResult.Total(200)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            awaitItem() // consume initial state (hydrationTodayDisplay = "")

            advanceTimeBy(30_001)

            // hydrationTodayDisplay changed → StateFlow emits; hydrationSyncStatus co-present
            val state = awaitItem()
            assertEquals("200 mL", state.hydrationTodayDisplay)
            assertTrue(
                state.hydrationSyncStatus.startsWith("Up to date"),
                "Expected 'Up to date' in hydrationSyncStatus (caughtUp=true), got: '${state.hydrationSyncStatus}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `glucoseSyncStatus is correct in 30s loop tick even when caught up`() = viewModelTest {
        val positiveTs = System.currentTimeMillis() - 5_000L
        every { settingsRepository.glucoseSyncCountFlow } returns flowOf(0)
        every { settingsRepository.glucoseSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.glucoseSyncRunTimestampFlow } returns flowOf(positiveTs)
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns
            TodayHydrationResult.Total(0) andThen TodayHydrationResult.Total(200)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            awaitItem()
            advanceTimeBy(30_001)

            val state = awaitItem()
            assertTrue(
                state.glucoseSyncStatus.startsWith("Up to date"),
                "Expected 'Up to date' in glucoseSyncStatus (caughtUp=true), got: '${state.glucoseSyncStatus}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bpSyncStatus is correct in 30s loop tick even when caught up`() = viewModelTest {
        val positiveTs = System.currentTimeMillis() - 5_000L
        every { settingsRepository.bpSyncCountFlow } returns flowOf(0)
        every { settingsRepository.bpSyncCaughtUpFlow } returns flowOf(true)
        every { settingsRepository.bpSyncRunTimestampFlow } returns flowOf(positiveTs)
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns
            TodayHydrationResult.Total(0) andThen TodayHydrationResult.Total(200)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            awaitItem()
            advanceTimeBy(30_001)

            val state = awaitItem()
            assertTrue(
                state.bpSyncStatus.startsWith("Up to date"),
                "Expected 'Up to date' in bpSyncStatus (caughtUp=true), got: '${state.bpSyncStatus}'",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 4 (HEA-207): Hydration permission denial ---

    @Test
    fun `loadTodayHydration PermissionDenied sets hydrationReadPermissionMissing true and clears display`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.PermissionDenied

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hydrationReadPermissionMissing)
            assertEquals("", state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadTodayHydration Total(0) sets hydrationReadPermissionMissing false and empty display`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(0)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.hydrationReadPermissionMissing)
            assertEquals("", state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Task 5 (HEA-209): Serialize loadTodayHydration to prevent stale-value races ---

    @Test
    fun `rapid loadTodayHydration calls do not emit stale value`() = viewModelTest {
        val firstDeferred = CompletableDeferred<TodayHydrationResult>()
        var callCount = 0
        coEvery { getTodayHydrationTotalUseCase.invoke() } coAnswers {
            callCount++
            if (callCount == 1) firstDeferred.await() else TodayHydrationResult.Total(500)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        // First call from init already in-flight (suspended on firstDeferred)
        // Trigger second call which should cancel the first
        viewModel.refreshTodayHydration()
        advanceTimeBy(1_000)

        // Complete second call (callCount==2 returns Total(500)) — already done
        // Now complete first call with stale value — should be ignored since job was cancelled
        firstDeferred.complete(TodayHydrationResult.Total(100))
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("500 mL", state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadTodayHydration Total(500) sets display to 500 mL and hydrationReadPermissionMissing false`() = viewModelTest {
        coEvery { getTodayHydrationTotalUseCase.invoke() } returns TodayHydrationResult.Total(500)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.hydrationReadPermissionMissing)
            assertEquals("500 mL", state.hydrationTodayDisplay)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
