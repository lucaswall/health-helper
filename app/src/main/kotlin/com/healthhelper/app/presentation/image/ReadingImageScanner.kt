package com.healthhelper.app.presentation.image

import android.content.Context
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.di.DefaultDispatcher
import com.healthhelper.app.di.IoDispatcher
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.ImageParseErrorKind
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

sealed interface ScanOutcome<out T> {
    data class Success<T>(val value: T) : ScanOutcome<T>
    data class Failure(val message: String) : ScanOutcome<Nothing>
}

/**
 * Reads a health reading off a photo of a device display: checks the Anthropic key, prepares the
 * image and maps every failure to a user-facing message, so entry screens only deal with
 * "fill these values" or "show this error".
 */
class ReadingImageScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val anthropicApiClient: AnthropicApiClient,
    private val settingsRepository: SettingsRepository,
    private val imagePreparer: ImagePreparer,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun scanBloodPressure(imageBytes: ByteArray): ScanOutcome<BloodPressureParseResult.Success> =
        scan(imageBytes) { apiKey, prepared ->
            when (val result = anthropicApiClient.parseBloodPressureImage(apiKey, prepared)) {
                is BloodPressureParseResult.Success -> ScanOutcome.Success(result)
                is BloodPressureParseResult.Error -> {
                    Timber.w("BP parse error (%s): %s", result.kind, result.message)
                    ScanOutcome.Failure(
                        imageParseErrorMessage(
                            result.kind,
                            "Could not read blood pressure from the photo. Try again or enter it manually.",
                        ),
                    )
                }
            }
        }

    suspend fun scanGlucose(imageBytes: ByteArray): ScanOutcome<GlucoseParseResult.Success> =
        scan(imageBytes) { apiKey, prepared ->
            when (val result = anthropicApiClient.parseGlucoseImage(apiKey, prepared)) {
                is GlucoseParseResult.Success -> ScanOutcome.Success(result)
                is GlucoseParseResult.Error -> {
                    Timber.w("Glucose parse error (%s): %s", result.kind, result.message)
                    ScanOutcome.Failure(
                        imageParseErrorMessage(
                            result.kind,
                            "Could not read glucose from the photo. Try again or enter it manually.",
                        ),
                    )
                }
            }
        }

    /**
     * Reads a shared image copied into the cache by MainActivity, then deletes it. Throws
     * [ImageTooLargeException] or an I/O exception when it can't be read, and
     * [IllegalArgumentException] for a path outside the shared-images cache directory (the path
     * arrives as a navigation argument, so it is never trusted to point anywhere else).
     */
    suspend fun readSharedImage(path: String): ByteArray = withContext(ioDispatcher) {
        val file = File(path)
        require(isInsideDirectory(file, File(context.cacheDir, SHARED_IMAGES_DIR))) {
            "Shared image path is outside the shared images directory"
        }
        try {
            file.inputStream().use { readBytesLimited(it, MAX_IMAGE_BYTES) }
        } finally {
            file.delete()
        }
    }

    private suspend fun <T> scan(
        imageBytes: ByteArray,
        parse: suspend (apiKey: String, preparedBytes: ByteArray) -> ScanOutcome<T>,
    ): ScanOutcome<T> = try {
        val apiKey = settingsRepository.anthropicApiKeyFlow.first()
        if (apiKey.isBlank()) {
            ScanOutcome.Failure("Configure Anthropic API key in Settings to scan photos.")
        } else {
            val prepared = withContext(defaultDispatcher) {
                imagePreparer.prepare(imageBytes, maxDimension = API_MAX_IMAGE_DIMENSION)
            }
            if (prepared == null) {
                ScanOutcome.Failure("Could not process image. Please try a different photo.")
            } else {
                parse(apiKey, prepared)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Unexpected error scanning reading image")
        ScanOutcome.Failure("Something went wrong. Please try again.")
    }
}

/** Maps a typed image-parse failure to a user-facing message; only unreadable images suggest a retake. */
internal fun imageParseErrorMessage(kind: ImageParseErrorKind, unreadableMessage: String): String =
    when (kind) {
        ImageParseErrorKind.AUTH -> "Anthropic API key rejected — check Settings."
        ImageParseErrorKind.RATE_LIMITED,
        ImageParseErrorKind.SERVER,
        -> "Service busy, try again shortly."
        ImageParseErrorKind.NETWORK -> "No connection. Check your network and try again."
        ImageParseErrorKind.UNKNOWN -> "Something went wrong. Please try again."
        ImageParseErrorKind.UNREADABLE -> unreadableMessage
    }
