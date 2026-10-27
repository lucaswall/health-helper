package com.healthhelper.app.presentation.viewmodel

import app.cash.turbine.test
import com.healthhelper.app.domain.repository.SecureStorageUnavailableException
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")
        coEvery { settingsRepository.setApiKey(any()) } returns Unit
        coEvery { settingsRepository.setBaseUrl(any()) } returns Unit
        coEvery { settingsRepository.setSyncInterval(any()) } returns Unit
        coEvery { settingsRepository.setAnthropicApiKey(any()) } returns Unit
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
            assertTrue(state.isLoaded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state with empty settings shows defaults`() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")
        every { settingsRepository.baseUrlFlow } returns flowOf("")
        every { settingsRepository.syncIntervalFlow } returns flowOf(15)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.apiKey)
            assertEquals("", state.baseUrl)
            assertEquals(15, state.syncInterval)
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
        viewModel.updateSyncInterval(95)
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("changed", state.apiKey)
            assertEquals("https://changed-url.com", state.baseUrl)
            assertEquals(95, state.syncInterval)
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
    fun `default SettingsUiState has syncInterval of 15`() {
        val state = SettingsUiState()
        assertEquals(15, state.syncInterval)
    }

    @Test
    fun `initial state has empty anthropicApiKey`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("", state.anthropicApiKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateAnthropicApiKey sets hasUnsavedChanges to true`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateAnthropicApiKey("sk-ant-test")

        viewModel.uiState.test {
            assertTrue(awaitItem().hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save calls setAnthropicApiKey`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateAnthropicApiKey("sk-ant-test")
        viewModel.save()
        advanceUntilIdle()

        coVerify { settingsRepository.setAnthropicApiKey("sk-ant-test") }
    }

    @Test
    fun `reset restores persisted anthropicApiKey`() = runTest {
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("sk-ant-persisted")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateAnthropicApiKey("sk-ant-changed")
        viewModel.uiState.test {
            assertEquals("sk-ant-changed", awaitItem().anthropicApiKey)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.reset()

        viewModel.uiState.test {
            assertEquals("sk-ant-persisted", awaitItem().anthropicApiKey)
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

    // --- Fix 2 (HEA-111): toString redaction tests ---

    @Test
    fun `SettingsUiState toString does not expose apiKey value`() {
        val state = SettingsUiState(apiKey = "secret-api-key-12345")
        assertFalse(state.toString().contains("secret-api-key-12345"))
    }

    @Test
    fun `SettingsUiState toString does not expose anthropicApiKey value`() {
        val state = SettingsUiState(anthropicApiKey = "sk-ant-secret-key-12345")
        assertFalse(state.toString().contains("sk-ant-secret-key-12345"))
    }

    @Test
    fun `SettingsUiState toString shows redacted placeholder for apiKey`() {
        val state = SettingsUiState(apiKey = "super-secret")
        assertTrue(state.toString().contains("***"))
    }

    // --- Fix 3 (HEA-112): Concurrent save guard tests ---

    @Test
    fun `second save while already saving is ignored`() = runTest {
        val continueDeferred = CompletableDeferred<Unit>()
        coEvery { settingsRepository.setApiKey(any()) } coAnswers {
            continueDeferred.await()
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.save() // first call — coroutine starts, suspends at continueDeferred.await()
        advanceUntilIdle() // runs until first save suspends; isSaving is now true

        viewModel.save() // second call — should be ignored (isSaving = true)

        continueDeferred.complete(Unit) // release first save
        advanceUntilIdle()

        // setApiKey called only once
        coVerify(exactly = 1) { settingsRepository.setApiKey(any()) }
    }

    // --- Fix 3 (HEA-112): CancellationException propagation tests ---

    @Test
    fun `CancellationException from setApiKey propagates and stops further saves`() = runTest {
        coEvery { settingsRepository.setApiKey(any()) } throws CancellationException("test cancel")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        // CancellationException propagated — setBaseUrl and setSyncInterval must NOT have been called
        coVerify(exactly = 0) { settingsRepository.setBaseUrl(any()) }
        coVerify(exactly = 0) { settingsRepository.setSyncInterval(any()) }
        coVerify(exactly = 0) { settingsRepository.setAnthropicApiKey(any()) }
    }

    @Test
    fun `CancellationException from setBaseUrl propagates and stops further saves`() = runTest {
        coEvery { settingsRepository.setBaseUrl(any()) } throws CancellationException("test cancel")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        // setSyncInterval and setAnthropicApiKey must NOT have been called
        coVerify(exactly = 0) { settingsRepository.setSyncInterval(any()) }
        coVerify(exactly = 0) { settingsRepository.setAnthropicApiKey(any()) }
    }

    // --- settings-1 / security-privacy-5: secure storage failures are reported ---

    @Test
    fun `save shows secure storage error and stays dirty when setApiKey reports storage unavailable`() = runTest {
        coEvery { settingsRepository.setApiKey(any()) } throws
            SecureStorageUnavailableException("Secure storage unavailable")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-key")
        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(SettingsViewModel.SECURE_STORAGE_ERROR, state.saveError)
            assertTrue(state.hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save shows secure storage error when setAnthropicApiKey reports storage unavailable`() = runTest {
        coEvery { settingsRepository.setAnthropicApiKey(any()) } throws
            SecureStorageUnavailableException("Secure storage unavailable")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateAnthropicApiKey("sk-ant-new")
        viewModel.save()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(SettingsViewModel.SECURE_STORAGE_ERROR, state.saveError)
            assertTrue(state.hasUnsavedChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- settings-2: inputs gated until first load ---

    @Test
    fun `state is not loaded before first emission and save is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        every { settingsRepository.apiKeyFlow } returns flow {
            gate.await()
            emit("stored-key")
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoaded)
        viewModel.updateBaseUrl("https://typed.example.com")
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setApiKey(any()) }
        coVerify(exactly = 0) { settingsRepository.setBaseUrl(any()) }

        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoaded)
        assertEquals("stored-key", state.apiKey)
        assertEquals("https://example.com", state.baseUrl)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `flow failure still marks state loaded so the screen is usable`() = runTest {
        every { settingsRepository.apiKeyFlow } returns flow { throw IOException("corrupt") }

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoaded)
    }

    // --- settings-3 / security-privacy-10 / security-privacy-11: trimming and URL validation ---

    @Test
    fun `save trims api keys and base url`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("  fsk_new \n")
        viewModel.updateAnthropicApiKey(" sk-ant-new ")
        viewModel.updateBaseUrl(" https://food.example.com ")
        viewModel.save()
        advanceUntilIdle()

        coVerify { settingsRepository.setApiKey("fsk_new") }
        coVerify { settingsRepository.setAnthropicApiKey("sk-ant-new") }
        coVerify { settingsRepository.setBaseUrl("https://food.example.com") }
        val state = viewModel.uiState.value
        assertEquals("fsk_new", state.apiKey)
        assertEquals("https://food.example.com", state.baseUrl)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `save rejects http base url without persisting anything`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateApiKey("new-key")
        viewModel.updateBaseUrl("http://food.example.com")
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setApiKey(any()) }
        coVerify(exactly = 0) { settingsRepository.setBaseUrl(any()) }
        coVerify(exactly = 0) { settingsRepository.setSyncInterval(any()) }
        coVerify(exactly = 0) { settingsRepository.setAnthropicApiKey(any()) }
        val state = viewModel.uiState.value
        assertEquals(SettingsViewModel.INVALID_BASE_URL_ERROR, state.baseUrlError)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun `save rejects base url without scheme`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("food.example.com")
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setBaseUrl(any()) }
        assertNotNull(viewModel.uiState.value.baseUrlError)
    }

    @Test
    fun `editing base url clears the base url error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("http://bad")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.baseUrlError)

        viewModel.updateBaseUrl("https://good.example.com")
        assertNull(viewModel.uiState.value.baseUrlError)
    }

    @Test
    fun `save allows empty base url to clear configuration`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateBaseUrl("   ")
        viewModel.save()
        advanceUntilIdle()

        coVerify { settingsRepository.setBaseUrl("") }
        assertNull(viewModel.uiState.value.baseUrlError)
    }

    // --- settings-6: sync interval is clamped ---

    @Test
    fun `updateSyncInterval clamps to allowed range`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSyncInterval(5)
        assertEquals(15, viewModel.uiState.value.syncInterval)
        viewModel.updateSyncInterval(500)
        assertEquals(120, viewModel.uiState.value.syncInterval)
    }
}
