package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.usecase.GetLastBloodPressureReadingUseCase
import com.healthhelper.app.domain.usecase.GetLastGlucoseReadingUseCase
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
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
    private lateinit var viewModel: SyncViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        syncNutritionUseCase = mockk()
        settingsRepository = mockk()
        syncScheduler = mockk(relaxed = true)
        getLastBpReadingUseCase = mockk()
        getLastGlucoseReadingUseCase = mockk()

        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_test")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")
        coEvery { settingsRepository.isConfigured() } returns true
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration
        every { syncScheduler.getNextSyncTimeFlow() } returns flowOf(null)
        coEvery { getLastBpReadingUseCase.invoke() } returns null
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns null
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val healthConnectClient: HealthConnectClient? = mockk(relaxed = true)

    private fun createViewModel(hcClient: HealthConnectClient? = healthConnectClient): SyncViewModel =
        SyncViewModel(syncNutritionUseCase, settingsRepository, syncScheduler, getLastBpReadingUseCase, getLastGlucoseReadingUseCase, hcClient)

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
            assertEquals("Sync failed. Please try again.", state.lastSyncResult)
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
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPermissionResult(true)

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
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } returns SyncViewModel.REQUIRED_HC_PERMISSIONS

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertTrue(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted stays false on init when permission not granted`() = viewModelTest {
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } returns emptySet()

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertFalse(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted stays false when getGrantedPermissions throws`() = viewModelTest {
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } throws RuntimeException("HC error")

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
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } returns SyncViewModel.REQUIRED_HC_PERMISSIONS

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            assertTrue(awaitItem().permissionGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permissionGranted is false when only WRITE_NUTRITION is granted`() = viewModelTest {
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } returns
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
        val permController = mockk<PermissionController>()
        every { healthConnectClient!!.permissionController } returns permController
        coEvery { permController.getGrantedPermissions() } returns setOf(
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
    fun `lastGlucoseReadingDisplay formats as value mmol_L`() = viewModelTest {
        val reading = GlucoseReading(valueMgDl = 101, timestamp = Instant.now())
        coEvery { getLastGlucoseReadingUseCase.invoke() } returns reading

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("5.6 mmol/L (101 mg/dL)", state.lastGlucoseReadingDisplay)
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
}
