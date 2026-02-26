package com.healthhelper.app.presentation.viewmodel

import com.healthhelper.app.domain.model.HealthConnectStatus
import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.model.HealthRecordType
import com.healthhelper.app.domain.model.PermissionStatus
import com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCase
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import com.healthhelper.app.presentation.viewmodel.HealthViewModel.Companion.REQUIRED_PERMISSIONS
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.withTimeout
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

    @Test
    @DisplayName("loads steps on init when HC available and permissions granted")
    fun loadsStepsOnInit() = runTest {
        val records = listOf(
            HealthRecord(
                id = "record-1",
                type = HealthRecordType.Steps,
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
        )
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns records

        val viewModel = createViewModel()
        // Simulate permission grant then reload
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.records.size)
        assertEquals(5000.0, state.records[0].value)
    }

    @Test
    @DisplayName("shows empty state when no records")
    fun emptyState() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } returns emptyList()

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.records.isEmpty())
    }

    @Test
    @DisplayName("shows error state when use case throws")
    fun errorState() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } throws RuntimeException("Service unavailable")

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
    }

    @Test
    @DisplayName("shows permission denied error for SecurityException")
    fun securityExceptionError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } throws SecurityException("Permission denied")

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Permission denied", state.errorMessage)
    }

    @Test
    @DisplayName("retry after error loads steps successfully")
    fun retryAfterError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } throws RuntimeException("Service unavailable")

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        val records = listOf(
            HealthRecord(
                id = "record-1",
                type = HealthRecordType.Steps,
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
        )
        coEvery { readStepsUseCase(any(), any()) } returns records

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
    @DisplayName("shows timeout error message for TimeoutCancellationException")
    fun timeoutError() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } coAnswers {
            withTimeout(1) {
                kotlinx.coroutines.delay(1000)
                emptyList()
            }
        }

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
        val records = listOf(
            HealthRecord(
                id = "record-1",
                type = HealthRecordType.Steps,
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
        )
        coEvery { readStepsUseCase(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1000)
            records
        }

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        // Call loadSteps again immediately — should cancel the first
        viewModel.loadSteps()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        // Should have records from the second call, not duplicated
        assertEquals(1, state.records.size)
    }

    @Test
    @DisplayName("SecurityException resets permissionStatus to Denied")
    fun securityExceptionResetsPermission() = runTest {
        every { checkHealthConnectStatusUseCase() } returns HealthConnectStatus.Available
        coEvery { readStepsUseCase(any(), any()) } throws SecurityException("Permission revoked")

        val viewModel = createViewModel()
        // Grant permissions, then load (which throws SecurityException)
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
        coEvery { readStepsUseCase(any(), any()) } returns emptyList()

        val viewModel = createViewModel()
        viewModel.onPermissionsResult(REQUIRED_PERMISSIONS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PermissionStatus.Granted, state.permissionStatus)
    }
}
