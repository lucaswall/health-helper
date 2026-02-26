package com.healthhelper.app.presentation.viewmodel

import com.healthhelper.app.domain.model.HealthRecord
import com.healthhelper.app.domain.usecase.ReadStepsUseCase
import io.mockk.coEvery
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var readStepsUseCase: ReadStepsUseCase

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        readStepsUseCase = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @DisplayName("loads steps on init")
    fun loadsStepsOnInit() = runTest {
        val records = listOf(
            HealthRecord(
                type = "Steps",
                value = 5000.0,
                startTime = Instant.parse("2026-02-19T08:00:00Z"),
                endTime = Instant.parse("2026-02-19T09:00:00Z"),
            ),
        )
        coEvery { readStepsUseCase(any(), any()) } returns records

        val viewModel = HealthViewModel(readStepsUseCase)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.records.size)
        assertEquals(5000.0, state.records[0].value)
    }

    @Test
    @DisplayName("shows empty state when no records")
    fun emptyState() = runTest {
        coEvery { readStepsUseCase(any(), any()) } returns emptyList()

        val viewModel = HealthViewModel(readStepsUseCase)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.records.isEmpty())
    }
}
