package com.healthhelper.app.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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
    private lateinit var viewModel: CameraCaptureViewModel

    private val testImageBytes = byteArrayOf(1, 2, 3)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        anthropicApiClient = mockk()
        settingsRepository = mockk(relaxed = true)

        // Mock BitmapFactory — Android stubs throw RuntimeException in JUnit
        mockkStatic(BitmapFactory::class)
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every {
            BitmapFactory.decodeByteArray(any(), any(), any(), any<BitmapFactory.Options>())
        } answers {
            val options = lastArg<BitmapFactory.Options>()
            if (options.inJustDecodeBounds) {
                options.outWidth = 100
                options.outHeight = 100
                null // Real API returns null for bounds-only decode
            } else {
                mockBitmap
            }
        }
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns mockBitmap
        every { mockBitmap.compress(any(), any(), any<OutputStream>()) } answers {
            val os = thirdArg<OutputStream>()
            os.write(byteArrayOf(1, 2, 3))
            true
        }

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
        unmockkStatic(BitmapFactory::class)
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        CameraCaptureViewModel(anthropicApiClient, settingsRepository, savedStateHandle, testDispatcher)

    @Test
    fun `initial state has isProcessing false and no error`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertNull(state.error)
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
    fun `onPhotoCaptured parse error sets error message and clears isProcessing`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Could not parse reading")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("Could not read blood pressure from image. Please retake.", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onRetake clears error`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("Failed")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.onRetake()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.error)
            assertFalse(state.isProcessing)
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
    fun `network error shows generic error message`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } throws
            RuntimeException("Network timeout")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("Something went wrong. Please try again.", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty API key shows configuration error without calling API`() = runTest {
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("Configure Anthropic API key in Settings", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CancellationException resets isProcessing without setting error`() = runTest {
        coEvery { anthropicApiClient.parseBloodPressureImage(any(), any()) } throws
            CancellationException("Cancelled")

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(testImageBytes)
        advanceUntilIdle()

        // CancellationException must not be swallowed as a generic error:
        // isProcessing must reset to false AND error must remain null
        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCaptureError sets error message and clears isProcessing`() = runTest {
        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onCaptureError("Camera capture failed")

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("Camera capture failed", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prepareImageForApi returns null for undecodable bytes sets error`() = runTest {
        // Override BitmapFactory to simulate undecodable image (dimensions = 0)
        every {
            BitmapFactory.decodeByteArray(any(), any(), any(), any<BitmapFactory.Options>())
        } answers {
            val options = lastArg<BitmapFactory.Options>()
            if (options.inJustDecodeBounds) {
                options.outWidth = 0
                options.outHeight = 0
            }
            null
        }

        viewModel = createViewModel()
        advanceTimeBy(1_000)

        viewModel.onPhotoCaptured(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("Could not process image. Please try a different photo.", state.error)
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
}
