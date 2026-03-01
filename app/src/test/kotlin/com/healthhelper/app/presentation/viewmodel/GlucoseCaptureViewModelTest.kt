package com.healthhelper.app.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
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
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseCaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var anthropicApiClient: AnthropicApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: GlucoseCaptureViewModel

    private val testImageBytes = byteArrayOf(1, 2, 3)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        anthropicApiClient = mockk()
        settingsRepository = mockk(relaxed = true)

        mockkStatic(BitmapFactory::class)
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every {
            BitmapFactory.decodeByteArray(any(), any(), any(), any<BitmapFactory.Options>())
        } answers {
            val options = lastArg<BitmapFactory.Options>()
            if (options.inJustDecodeBounds) {
                options.outWidth = 100
                options.outHeight = 100
                null
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
        GlucoseCaptureViewModel(anthropicApiClient, settingsRepository, savedStateHandle, testDispatcher)

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
            assertEquals("mmol/L", event.second)
            assertEquals("mmol/L", event.third)
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
            assertEquals("mg/dL", event.second)
            assertEquals("mg/dL", event.third)
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
}
