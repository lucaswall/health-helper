package com.healthhelper.app.presentation.image

import android.content.Context
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.ImageParseErrorKind
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingImageScannerTest {

    @TempDir
    lateinit var tempDir: File

    private val dispatcher = StandardTestDispatcher()
    private lateinit var apiClient: AnthropicApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var imagePreparer: ImagePreparer
    private lateinit var scanner: ReadingImageScanner
    private lateinit var sharedDir: File

    private val rawBytes = byteArrayOf(1, 2, 3)
    private val preparedBytes = byteArrayOf(4, 5)

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        settingsRepository = mockk()
        imagePreparer = mockk()
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("sk-test")
        every { imagePreparer.prepare(rawBytes, API_MAX_IMAGE_DIMENSION) } returns preparedBytes
        val context = mockk<Context>()
        every { context.cacheDir } returns tempDir
        sharedDir = File(tempDir, SHARED_IMAGES_DIR).apply { mkdirs() }
        scanner = ReadingImageScanner(context, apiClient, settingsRepository, imagePreparer, dispatcher, dispatcher)
    }

    @Test
    fun `blood pressure success returns the parsed reading including heart rate`() = runTest(dispatcher) {
        val parsed = BloodPressureParseResult.Success(systolic = 120, diastolic = 80, heartRate = 70)
        coEvery { apiClient.parseBloodPressureImage("sk-test", preparedBytes) } returns parsed

        assertEquals(ScanOutcome.Success(parsed), scanner.scanBloodPressure(rawBytes))
    }

    @Test
    fun `glucose success returns the parsed reading`() = runTest(dispatcher) {
        val parsed = GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L)
        coEvery { apiClient.parseGlucoseImage("sk-test", preparedBytes) } returns parsed

        assertEquals(ScanOutcome.Success(parsed), scanner.scanGlucose(rawBytes))
    }

    @Test
    fun `missing API key fails without calling the API`() = runTest(dispatcher) {
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")

        val outcome = scanner.scanBloodPressure(rawBytes)

        assertEquals(ScanOutcome.Failure("Configure Anthropic API key in Settings to scan photos."), outcome)
        coVerify(exactly = 0) { apiClient.parseBloodPressureImage(any(), any()) }
    }

    @Test
    fun `image that cannot be prepared fails without calling the API`() = runTest(dispatcher) {
        every { imagePreparer.prepare(any(), any()) } returns null

        val outcome = scanner.scanGlucose(rawBytes)

        assertEquals(ScanOutcome.Failure("Could not process image. Please try a different photo."), outcome)
        coVerify(exactly = 0) { apiClient.parseGlucoseImage(any(), any()) }
    }

    @Test
    fun `unreadable display suggests retrying or entering manually`() = runTest(dispatcher) {
        coEvery { apiClient.parseBloodPressureImage(any(), any()) } returns
            BloodPressureParseResult.Error("no reading", ImageParseErrorKind.UNREADABLE)

        val outcome = scanner.scanBloodPressure(rawBytes)

        assertEquals(
            ScanOutcome.Failure("Could not read blood pressure from the photo. Try again or enter it manually."),
            outcome,
        )
    }

    @Test
    fun `typed API failures map to specific messages`() = runTest(dispatcher) {
        val expected = mapOf(
            ImageParseErrorKind.AUTH to "Anthropic API key rejected — check Settings.",
            ImageParseErrorKind.RATE_LIMITED to "Service busy, try again shortly.",
            ImageParseErrorKind.SERVER to "Service busy, try again shortly.",
            ImageParseErrorKind.NETWORK to "No connection. Check your network and try again.",
            ImageParseErrorKind.UNKNOWN to "Something went wrong. Please try again.",
        )
        expected.forEach { (kind, message) ->
            coEvery { apiClient.parseGlucoseImage(any(), any()) } returns GlucoseParseResult.Error("x", kind)
            assertEquals(ScanOutcome.Failure(message), scanner.scanGlucose(rawBytes), "kind=$kind")
        }
    }

    @Test
    fun `unexpected exception becomes a generic failure`() = runTest(dispatcher) {
        coEvery { apiClient.parseBloodPressureImage(any(), any()) } throws IllegalStateException("boom")

        assertEquals(
            ScanOutcome.Failure("Something went wrong. Please try again."),
            scanner.scanBloodPressure(rawBytes),
        )
    }

    @Test
    fun `cancellation is rethrown`() = runTest(dispatcher) {
        coEvery { apiClient.parseBloodPressureImage(any(), any()) } throws CancellationException("cancelled")

        assertThrows<CancellationException> { scanner.scanBloodPressure(rawBytes) }
    }

    @Test
    fun `readSharedImage returns the bytes and deletes the file`() = runTest(dispatcher) {
        val file = File(sharedDir, "shared.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }

        assertContentEquals(byteArrayOf(7, 8, 9), scanner.readSharedImage(file.absolutePath))
        assertFalse(file.exists())
    }

    @Test
    fun `readSharedImage deletes the file even when it is too large`() = runTest(dispatcher) {
        val file = File(sharedDir, "big.jpg").apply { writeBytes(ByteArray(MAX_IMAGE_BYTES + 1)) }

        assertThrows<ImageTooLargeException> { scanner.readSharedImage(file.absolutePath) }
        assertFalse(file.exists())
    }

    @Test
    fun `readSharedImage refuses paths outside the shared images directory and leaves them alone`() = runTest(dispatcher) {
        val outside = File(tempDir, "private.db").apply { writeBytes(byteArrayOf(1)) }
        val traversal = File(sharedDir, "../private.db").path

        assertThrows<IllegalArgumentException> { scanner.readSharedImage(outside.absolutePath) }
        assertThrows<IllegalArgumentException> { scanner.readSharedImage(traversal) }
        kotlin.test.assertTrue(outside.exists())
    }
}
