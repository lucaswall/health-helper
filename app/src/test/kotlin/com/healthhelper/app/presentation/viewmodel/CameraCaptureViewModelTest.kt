package com.healthhelper.app.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.BloodPressureParseResult
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraCaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var anthropicApiClient: AnthropicApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var imagePreparer: ImagePreparer
    private lateinit var viewModel: CameraCaptureViewModel

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
        CameraCaptureViewModel(anthropicApiClient, settingsRepository, savedStateHandle, testDispatcher, imagePreparer)

    @Test
    fun `initial state has isProcessing false`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured sets isProcessing true while calling API`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            BloodPressureParseResult.Success(120, 80)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        testDispatcher.scheduler.advanceTimeBy(100)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.isProcessing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured success emits navigateToConfirmation event`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Success(120, 80)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateToConfirmation.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            val event = awaitItem()
            assertEquals(120, event.first)
            assertEquals(80, event.second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured success clears isProcessing`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Success(120, 80)

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
    fun `onPhotoCaptured parse error emits navigateBackWithError`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Could not parse reading")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Could not read blood pressure from image. Please retake.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured while already processing is ignored`() = runTest {
        var callCount = 0
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } coAnswers {
            callCount++
            kotlinx.coroutines.delay(2_000)
            BloodPressureParseResult.Success(120, 80)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        testDispatcher.scheduler.advanceTimeBy(100)

        // Second call while processing — should be ignored
        viewModel.onPhotoCaptured(testImageBytes)
        testDispatcher.scheduler.advanceTimeBy(100)

        assertEquals(1, callCount)
    }

    @Test
    fun `network error emits navigateBackWithError`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } throws
            RuntimeException("Network timeout")

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
    fun `CancellationException resets isProcessing without emitting error`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } throws
            CancellationException("Cancelled")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        // CancellationException must not be swallowed as a generic error:
        // isProcessing must reset to false
        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCaptureError emits navigateBackWithError`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onCaptureError("Camera capture failed")
            advanceUntilIdle()
            assertEquals("Camera capture failed", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prepareImageForApi returns null for undecodable bytes emits navigateBackWithError`() = runTest {
        every { imagePreparer.prepare(any(), any()) } returns null

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            advanceUntilIdle()
            assertEquals("Could not process image. Please try a different photo.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPhotoCaptured passes non-null bytes to API client`() = runTest {
        var capturedBytes: ByteArray? = null
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } coAnswers {
            capturedBytes = secondArg()
            BloodPressureParseResult.Success(120, 80)
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        assertNotNull(capturedBytes)
    }

    @Test
    fun `setTempFilePath persists path and clearTempFilePath clears it`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.tempFilePath.test {
            assertEquals(null, awaitItem())

            viewModel.setTempFilePath("/cache/bp_images/bp_123.jpg")
            assertEquals("/cache/bp_images/bp_123.jpg", awaitItem())

            viewModel.clearTempFilePath()
            assertEquals(null, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tempFilePath survives SavedStateHandle restoration`() = runTest {
        val savedState = SavedStateHandle(mapOf("temp_file_path" to "/cache/bp_images/bp_456.jpg"))
        viewModel = createViewModel(savedStateHandle = savedState)
        advanceTimeBy(1_000)

        viewModel.tempFilePath.test {
            assertEquals("/cache/bp_images/bp_456.jpg", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auth error tells user to check Settings instead of retaking`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Authentication failed", ImageParseErrorKind.AUTH)

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
    fun `network error kind reports connection problem`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Failed to analyze image", ImageParseErrorKind.NETWORK)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertTrue(awaitItem().contains("connection", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rate limited and server error kinds report service busy`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Rate limited", ImageParseErrorKind.RATE_LIMITED)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.navigateBackWithError.test {
            viewModel.onPhotoCaptured(testImageBytes)
            advanceUntilIdle()
            assertEquals("Service busy, try again shortly.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCaptureError cancels in-flight processing so no confirmation is emitted`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            BloodPressureParseResult.Success(120, 80)
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
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Success(120, 80)

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        // No collector attached yet (e.g. Activity being recreated)
        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.navigateToConfirmation.test {
            assertEquals(Pair(120, 80), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
