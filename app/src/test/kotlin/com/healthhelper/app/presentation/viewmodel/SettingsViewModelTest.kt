package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `updateApiKey updates UI state but does not persist to repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-api-key")
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals("new-api-key", awaitItem().apiKey)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { settingsRepository.setApiKey(any()) }
    }

    @Test
    fun `updateBaseUrl updates UI state but does not persist to repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://new-url.com")
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals("https://new-url.com", awaitItem().baseUrl)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { settingsRepository.setBaseUrl(any()) }
    }

    @Test
    fun `updateSyncInterval updates UI state but does not persist to repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSyncInterval(60)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(60, awaitItem().syncInterval)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { settingsRepository.setSyncInterval(any()) }
    }

    @Test
    fun `hasUnsavedChanges is false when UI matches persisted state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertFalse(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasUnsavedChanges is true after updateApiKey with different value`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("changed-key")

        viewModel.uiState.test {
            assertTrue(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasUnsavedChanges is true after updateBaseUrl with different value`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://changed-url.com")

        viewModel.uiState.test {
            assertTrue(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasUnsavedChanges is true after updateSyncInterval with different value`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSyncInterval(60)

        viewModel.uiState.test {
            assertTrue(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save persists all current settings to repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new")
        viewModel.updateBaseUrl("https://new-url.com")
        viewModel.updateSyncInterval(60)
        viewModel.save()
        advanceUntilIdle()

        coVerify { settingsRepository.setApiKey("new") }
        coVerify { settingsRepository.setBaseUrl("https://new-url.com") }
        coVerify { settingsRepository.setSyncInterval(60) }
    }

    @Test
    fun `save sets hasUnsavedChanges to false`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("changed")
        viewModel.uiState.test {
            assertTrue(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertFalse(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reset reverts UI state to persisted values`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("changed")
        viewModel.updateBaseUrl("https://changed-url.com")
        viewModel.updateSyncInterval(99)
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("changed", state.apiKey)
            assertEquals("https://changed-url.com", state.baseUrl)
            assertEquals(99, state.syncInterval)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.reset()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("test-key", state.apiKey)
            assertEquals("https://example.com", state.baseUrl)
            assertEquals(30, state.syncInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reset sets hasUnsavedChanges to false`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("changed")
        viewModel.reset()

        viewModel.uiState.test {
            assertFalse(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save handles repository exception gracefully`() = runTest {
        coEvery { settingsRepository.setApiKey(any()) } throws RuntimeException("write failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-key")
        viewModel.save()
        advanceUntilIdle()

        // Should not crash — verify base URL and sync interval still attempted
        coVerify { settingsRepository.setBaseUrl(any()) }
        coVerify { settingsRepository.setSyncInterval(any()) }

        // apiKey write failed — should remain dirty and show error
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasUnsavedChanges)
            assertNotNull(state.saveError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save sets error message when repository write fails`() = runTest {
        coEvery { settingsRepository.setApiKey(any()) } throws RuntimeException("write failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-key")
        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Some settings could not be saved. Please try again.", state.saveError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveError is cleared on next successful save`() = runTest {
        coEvery { settingsRepository.setApiKey(any()) } throws RuntimeException("write failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-key")
        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertNotNull(awaitItem().saveError)
            cancelAndIgnoreRemainingEvents()
        }

        // Fix the mock and save again
        coEvery { settingsRepository.setApiKey(any()) } returns Unit
        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            assertNull(awaitItem().saveError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `default SettingsUiState has syncInterval of 5`() {
        val state = SettingsUiState()
        assertEquals(5, state.syncInterval)
    }

    @Test
    fun `isConfigured is false when apiKey and baseUrl are empty`() = runTest {
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

    @Test
    fun `init handles repository flow exception gracefully`() = runTest {
        every { settingsRepository.apiKeyFlow } returns flow { throw IOException("corrupt") }

        viewModel = createViewModel()
        advanceUntilIdle()

        // Should not crash — UI stays at defaults
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.apiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
