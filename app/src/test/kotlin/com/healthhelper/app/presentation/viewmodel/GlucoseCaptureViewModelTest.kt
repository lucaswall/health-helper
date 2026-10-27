package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.ImageParseErrorKind
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.presentation.image.ImagePreparer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseCaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var anthropicApiClient: AnthropicApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var imagePreparer: ImagePreparer
    private lateinit var viewModel: GlucoseCaptureViewModel

    private val testImageBytes = byteArrayOf(1, 2, 3)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        anthropicApiClient = mockk()
        settingsRepository = mockk(relaxed = true)

        imagePreparer = mockk()
        every { imagePreparer.prepare(any(), any()) } returns byteArrayOf(1, 2, 3)

        every { settingsRepository.apiKeyFlow } returns flowOf("fsk_test")
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.syncIntervalFlow } returns flowOf(30)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")
        every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("test-api-key")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        GlucoseCaptureViewModel(anthropicApiClient, settingsRepository, savedStateHandle, testDispatcher, imagePreparer)

    @Test
    fun `onPhotoCaptured success emits navigateToConfirmation with value and units`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } returns
            GlucoseParseResult.Success(5.6, GlucoseUnit.MMOL_L)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateToConfirmation.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            val event = awaitItem()
            assertEquals(5.6, event.first, 0.01)
            assertEquals(GlucoseUnit.MMOL_L, event.second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured success with mg_dL emits correct units`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } returns
            GlucoseParseResult.Success(101.0, GlucoseUnit.MG_DL)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateToConfirmation.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            val event = awaitItem()
            assertEquals(101.0, event.first, 0.01)
            assertEquals(GlucoseUnit.MG_DL, event.second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured parse error emits navigateBackWithError`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } returns
            GlucoseParseResult.Error("Could not read")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Could not read glucose from image. Please retake.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty API key emits navigateBackWithError`() = runTest {
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Configure Anthropic API key in Settings", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `image preparation failure emits navigateBackWithError`() = runTest {
        every { imagePreparer.prepare(any(), any()) } returns null

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Could not process image. Please try a different photo.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured while processing is ignored`() = runTest {
        var callCount = 0
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            GlucoseParseResult.Success(5.6, GlucoseUnit.MMOL_L)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        testDispatcher.scheduler.advanceTimeBy(100)
        viewModel.onPhotoCaptured(testImageBytes)
        testDispatcher.scheduler.advanceTimeBy(100)

        assertEquals(1, callCount)
    }

    @Test
    fun `exception emits navigateBackWithError`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } throws
            RuntimeException("Network error")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Something went wrong. Please try again.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CancellationException resets isProcessing`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } throws
            CancellationException("Cancelled")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auth error tells user to check Settings instead of retaking`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } returns
            GlucoseParseResult.Error("Authentication failed", ImageParseErrorKind.AUTH)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            val msg = awaitItem()
            assertTrue(msg.contains("Settings"))
            assertFalse(msg.contains("retake", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCaptureError cancels in-flight processing so no confirmation is emitted`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            GlucoseParseResult.Success(5.6, GlucoseUnit.MMOL_L)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateToConfirmation.test {
            viewModel.onPhotoCaptured(testImageBytes)
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onCaptureError("Could not read shared image.")
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    @Test
    fun `navigateToConfirmation event is buffered until a collector subscribes`() = runTest {
        coEvery { anthropicApiClient.parseGlucoseImage(any(), any()) } returns
            GlucoseParseResult.Success(101.0, GlucoseUnit.MG_DL)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.navigateToConfirmation.test {
            val event = awaitItem()
            assertEquals(101.0, event.first, 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
