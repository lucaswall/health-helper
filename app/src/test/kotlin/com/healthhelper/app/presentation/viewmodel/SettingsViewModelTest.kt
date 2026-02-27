package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import com.healthhelper.app.domain.repository.SettingsRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk()
        every { settingsRepository.apiKeyFlow } returns flowOf("test-key")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        coEvery { settingsRepository.isConfigured() } returns true
        coEvery { settingsRepository.setApiKey(any()) } returns Unit
        coEvery { settingsRepository.setBaseUrl(any()) } returns Unit
        coEvery { settingsRepository.setSyncInterval(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(settingsRepository)

    @Test
    fun `initial state loads current settings from repository flows`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("test-key", state.apiKey)
            assertEquals("https://example.com", state.baseUrl)
            assertEquals(30, state.syncInterval)
            assertTrue(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state with empty settings shows defaults`() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")
        every { settingsRepository.syncIntervalFlow } returns flowOf(10)
        coEvery { settingsRepository.isConfigured() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.apiKey)
            assertEquals("", state.baseUrl)
            assertEquals(10, state.syncInterval)
            assertFalse(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateApiKey calls repository setApiKey`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-api-key")
        advanceUntilIdle()

        coVerify { settingsRepository.setApiKey("new-api-key") }
    }

    @Test
    fun `updateBaseUrl calls repository setBaseUrl`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://new-url.com")
        advanceUntilIdle()

        coVerify { settingsRepository.setBaseUrl("https://new-url.com") }
    }

    @Test
    fun `updateSyncInterval calls repository setSyncInterval`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSyncInterval(60)
        advanceUntilIdle()

        coVerify { settingsRepository.setSyncInterval(60) }
    }

    @Test
    fun `isConfigured state reflects repository isConfigured`() = runTest {
        coEvery { settingsRepository.isConfigured() } returns false
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")
        every { settingsRepository.syncIntervalFlow } returns flowOf(10)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isConfigured)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
