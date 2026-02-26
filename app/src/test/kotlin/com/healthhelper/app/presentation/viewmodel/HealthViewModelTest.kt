package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.HealthRecordType
import com.healthhelper.app.domain.model.PermissionStatus
import com.healthhelper.app.domain.model.StepsErrorType
import com.healthhelper.app.domain.model.StepsResult
import com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCase
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import com.healthhelper.app.presentation.viewmodel.HealthViewModel.Companion.REQUIRED_PERMISSIONS
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var readStepsUseCase: ReadStepsUseCase
    private lateinit var checkHealthConnectStatusUseCase: CheckHealthConnectStatusUseCase

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        readStepsUseCase = mockk()
        checkHealthConnectStatusUseCase = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HealthViewModel {
        return HealthViewModel(readStepsUseCase, checkHealthConnectStatusUseCase)
    }

    private val testRecords = listOf(
        HealthRecord(
            id = "record-1",
            type = HealthRecordType.Steps,
            value = 5000.0,
            startTime = Instant.parse("2026-02-19T08:00:00Z"),
            endTime = Instant.parse("2026-02-19T09:00:00Z"),
        ),
    )

    @Test
    @DisplayName("loads steps after permission grant")
    fun loadsStepsAfterPermissionGrant() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.records.size)
        assertEquals(5000.0, state.records[0].value)
    }

    @Test
    @DisplayName("loads steps on checkAndLoad when permissions already granted")
    fun loadsStepsOnResumeWithPreGrantedPermissions() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        val viewModel = createViewModel()
        // First grant permissions
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        // Simulate resume — should reload since permissions are Granted
        viewModel.checkAndLoad()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.records.size)
    }

    @Test
    @DisplayName("shows empty state when no records")
    fun emptyState() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(emptyList())

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.records.isEmpty())
    }

    @Test
    @DisplayName("shows error state when use case returns error")
    fun errorState() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Error(
            StepsErrorType.Unknown,
            "Failed to load steps",
        )

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
    }

    @Test
    @DisplayName("shows permission denied error for PermissionDenied result")
    fun permissionDeniedError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Error(
            StepsErrorType.PermissionDenied,
            "Permission denied",
        )

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Permission denied", state.errorMessage)
        assertEquals(PermissionStatus.Denied, state.permissionStatus)
    }

    @Test
    @DisplayName("retry after error loads steps successfully")
    fun retryAfterError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Error(
            StepsErrorType.Unknown,
            "Failed to load steps",
        )

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        viewModel.loadSteps()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(1, state.records.size)
    }

    @Test
    @DisplayName("shows NotInstalled when Health Connect is unavailable")
    fun healthConnectNotInstalled() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.NotInstalled

        val viewModel = createViewModel()
        viewModel.checkAndLoad()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HealthConnectStatus.NotInstalled, state.healthConnectStatus)
        assertFalse(state.isLoading)
    }

    @Test
    @DisplayName("shows NeedsUpdate when Health Connect needs update")
    fun healthConnectNeedsUpdate() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.NeedsUpdate

        val viewModel = createViewModel()
        viewModel.checkAndLoad()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HealthConnectStatus.NeedsUpdate, state.healthConnectStatus)
        assertFalse(state.isLoading)
    }

    @Test
    @DisplayName("permission denied sets PermissionStatus.Denied")
    fun permissionDenied() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(emptySet())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PermissionStatus.Denied, state.permissionStatus)
    }

    @Test
    @DisplayName("shows timeout error message for Timeout result")
    fun timeoutError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Error(
            StepsErrorType.Timeout,
            "Request timed out. Please try again.",
        )

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Request timed out. Please try again.", state.errorMessage)
    }

    @Test
    @DisplayName("rapid loadSteps calls cancel previous in-flight work")
    fun loadStepsCancelsPrevious() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1000)
            StepsResult.Success(testRecords)
        }

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        // Call loadSteps again immediately — should cancel the first
        viewModel.loadSteps()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.records.size)
    }

    @Test
    @DisplayName("PermissionDenied result resets permissionStatus to Denied")
    fun permissionDeniedResultResetsPermission() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Error(
            StepsErrorType.PermissionDenied,
            "Permission denied",
        )

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PermissionStatus.Denied, state.permissionStatus)
        assertEquals("Permission denied", state.errorMessage)
    }

    @Test
    @DisplayName("partial permission grant sets PermissionStatus.Denied")
    fun partialPermissionGrantDenied() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(setOf("wrong.permission"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PermissionStatus.Denied, state.permissionStatus)
    }

    @Test
    @DisplayName("permission granted sets PermissionStatus.Granted and loads steps")
    fun permissionGranted() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(emptyList())

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PermissionStatus.Granted, state.permissionStatus)
    }

    @Test
    @DisplayName("loadSteps transitions through isLoading intermediate state")
    fun loadStepsIntermediateState() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)

        viewModel.uiState.test {
            // Skip initial emissions until we see isLoading = true
            var seenLoading = false
            var seenLoaded = false
            while (!seenLoaded) {
                val item = awaitItem()
                if (item.isLoading && !item.isRefreshing) {
                    seenLoading = true
                }
                if (seenLoading && !item.isLoading && item.records.isNotEmpty()) {
                    seenLoaded = true
                }
            }
            assertTrue(seenLoading, "Should have seen isLoading = true intermediate state")
            assertTrue(seenLoaded, "Should have seen final loaded state")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("refreshSteps sets isRefreshing instead of isLoading")
    fun refreshStepsUsesRefreshingFlag() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        val viewModel = createViewModel()
        // First load normally
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        // Now refresh
        viewModel.refreshSteps()

        viewModel.uiState.test {
            var seenRefreshing = false
            var seenRefreshed = false
            while (!seenRefreshed) {
                val item = awaitItem()
                if (item.isRefreshing && !item.isLoading) {
                    seenRefreshing = true
                }
                if (seenRefreshing && !item.isRefreshing && item.records.isNotEmpty()) {
                    seenRefreshed = true
                }
            }
            assertTrue(seenRefreshing, "Should have seen isRefreshing = true intermediate state")
            assertFalse(viewModel.uiState.value.isLoading, "isLoading should remain false during refresh")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("init does not call checkAndLoad — lifecycle observer handles it")
    fun initDoesNotLoad() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns StepsResult.Success(testRecords)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Without checkAndLoad being called, healthConnectStatus should be null
        // because init no longer calls checkAndLoad
        assertNull(viewModel.uiState.value.healthConnectStatus)
    }
}
