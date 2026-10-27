package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.AnthropicContentItem
import com.healthhelper.app.data.api.dto.AnthropicImageSource
import com.healthhelper.app.data.api.dto.AnthropicMessage
import com.healthhelper.app.data.api.dto.AnthropicMessageRequest
import com.healthhelper.app.data.api.dto.AnthropicMessageResponse
import com.healthhelper.app.data.api.dto.AnthropicToolChoice
import com.healthhelper.app.data.api.dto.AnthropicToolDefinition
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import javax.inject.Inject

class AnthropicApiClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_TOKENS = 256
        private const val SYSTOLIC_MIN = 60
        private const val SYSTOLIC_MAX = 300
        private const val DIASTOLIC_MIN = 30
        private const val DIASTOLIC_MAX = 200
        private const val MMOL_L_MIN = 0.5
        private const val MMOL_L_MAX = 40.0
        private const val MG_DL_MIN = 10.0
        private const val MG_DL_MAX = 720.0
        internal const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 500L
        private const val RETRY_JITTER_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 4_000L
        private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504, 529)
        private const val TOOL_NAME = "blood_pressure_reading"

        private val BP_TOOL = AnthropicToolDefinition(
            name = TOOL_NAME,
            description = "Extract a blood pressure reading from a monitor display image.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("systolic") {
                        put("type", "integer")
                        put("description", "Systolic pressure (top/larger number)")
                    }
                    putJsonObject("diastolic") {
                        put("type", "integer")
                        put("description", "Diastolic pressure (middle/smaller number)")
                    }
                    putJsonObject("error") {
                        put("type", "string")
                        put("description", "Error message if the reading cannot be determined")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("systolic"))
                    add(JsonPrimitive("diastolic"))
                }
            },
        )

        private val BP_TOOL_CHOICE = AnthropicToolChoice(
            type = "tool",
            name = TOOL_NAME,
        )

        private const val GLUCOSE_TOOL_NAME = "glucose_reading"

        private val GLUCOSE_TOOL = AnthropicToolDefinition(
            name = GLUCOSE_TOOL_NAME,
            description = "Extract a glucose reading from a glucometer display image.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("value") {
                        put("type", "number")
                        put("description", "The numeric glucose value shown on the display")
                    }
                    putJsonObject("unit") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("mmol/L"))
                            add(JsonPrimitive("mg/dL"))
                        }
                        put("description", "The unit of measurement: mmol/L or mg/dL")
                    }
                    putJsonObject("error") {
                        put("type", "string")
                        put("description", "Error message if the reading cannot be determined")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("value"))
                    add(JsonPrimitive("unit"))
                }
            },
        )

        private val GLUCOSE_TOOL_CHOICE = AnthropicToolChoice(
            type = "tool",
            name = GLUCOSE_TOOL_NAME,
        )
    }

    suspend fun parseBloodPressureImage(
        apiKey: String,
        imageBytes: ByteArray,
    ): BloodPressureParseResult {
        val outcome = callTool(
            apiKey = apiKey,
            imageBytes = imageBytes,
            tool = BP_TOOL,
            toolChoice = BP_TOOL_CHOICE,
            prompt = "Extract the blood pressure reading from this monitor display. " +
                "The systolic value is the larger/topmost number. " +
                "The diastolic value is the middle number. Ignore pulse/heart rate.",
            system = null,
            tag = "parseBloodPressureImage",
        )
        return when (outcome) {
            is ToolCallOutcome.Failure -> BloodPressureParseResult.Error(outcome.message)
            is ToolCallOutcome.Success -> parseToolInput(outcome.input)
        }
    }

    private fun parseToolInput(input: JsonObject): BloodPressureParseResult {
        val errorField = (input["error"] as? JsonPrimitive)?.contentOrNull
        if (!errorField.isNullOrBlank()) {
            Timber.d("parseBloodPressureImage: model returned error: %s", errorField)
            return BloodPressureParseResult.Error("Unable to read blood pressure display")
        }

        val systolic = (input["systolic"] as? JsonPrimitive)?.intOrNull
        val diastolic = (input["diastolic"] as? JsonPrimitive)?.intOrNull

        if (systolic == null || diastolic == null) {
            Timber.w("parseBloodPressureImage: missing systolic or diastolic in response")
            return BloodPressureParseResult.Error("Missing blood pressure values in response")
        }

        // Numeric health values are only logged at DEBUG, which does not reach Sentry.
        if (systolic < SYSTOLIC_MIN || systolic > SYSTOLIC_MAX) {
            Timber.d("parseBloodPressureImage: systolic %d out of range", systolic)
            Timber.w("parseBloodPressureImage: systolic out of range")
            return BloodPressureParseResult.Error("Systolic value is out of valid range")
        }

        if (diastolic < DIASTOLIC_MIN || diastolic > DIASTOLIC_MAX) {
            Timber.d("parseBloodPressureImage: diastolic %d out of range", diastolic)
            Timber.w("parseBloodPressureImage: diastolic out of range")
            return BloodPressureParseResult.Error("Diastolic value is out of valid range")
        }

        if (systolic <= diastolic) {
            Timber.d(
                "parseBloodPressureImage: systolic %d not greater than diastolic %d",
                systolic,
                diastolic,
            )
            Timber.w("parseBloodPressureImage: systolic not greater than diastolic")
            return BloodPressureParseResult.Error("Systolic must be greater than diastolic")
        }

        return BloodPressureParseResult.Success(systolic = systolic, diastolic = diastolic)
    }

    suspend fun parseGlucoseImage(
        apiKey: String,
        imageBytes: ByteArray,
    ): GlucoseParseResult {
        val outcome = callTool(
            apiKey = apiKey,
            imageBytes = imageBytes,
            tool = GLUCOSE_TOOL,
            toolChoice = GLUCOSE_TOOL_CHOICE,
            prompt = "Extract the glucose reading from this glucometer display.",
            system = "Extract the glucose reading from this glucometer display. " +
                "Return the numeric value shown and the unit displayed (mmol/L or mg/dL). " +
                "If the unit is not visible, infer from the numeric range: " +
                "values above 30 are likely mg/dL, below 30 are likely mmol/L.",
            tag = "parseGlucoseImage",
        )
        return when (outcome) {
            is ToolCallOutcome.Failure -> GlucoseParseResult.Error(outcome.message)
            is ToolCallOutcome.Success -> parseGlucoseToolInput(outcome.input)
        }
    }

    private fun parseGlucoseToolInput(input: JsonObject): GlucoseParseResult {
        val errorField = (input["error"] as? JsonPrimitive)?.contentOrNull
        if (!errorField.isNullOrBlank()) {
            Timber.d("parseGlucoseImage: model returned error: %s", errorField)
            return GlucoseParseResult.Error("Unable to read glucose display")
        }

        val value = (input["value"] as? JsonPrimitive)?.doubleOrNull
        val unitStr = (input["unit"] as? JsonPrimitive)?.contentOrNull

        if (value == null || unitStr.isNullOrBlank()) {
            Timber.w("parseGlucoseImage: missing value or unit in response")
            return GlucoseParseResult.Error("Missing glucose values in response")
        }

        if (value <= 0) {
            Timber.d("parseGlucoseImage: value %f is not positive", value)
            Timber.w("parseGlucoseImage: value is not positive")
            return GlucoseParseResult.Error("Glucose value must be positive")
        }

        val detectedUnit = when (unitStr.trim().lowercase().replace(" ", "")) {
            "mmol/l" -> GlucoseUnit.MMOL_L
            "mg/dl" -> GlucoseUnit.MG_DL
            else -> {
                Timber.w("parseGlucoseImage: unrecognized unit: %s", unitStr)
                return GlucoseParseResult.Error("Unrecognized glucose unit: $unitStr")
            }
        }

        // Reject (rather than silently flip) values implausible for the stated unit, so a
        // misread unit surfaces as a retake instead of a medically wrong number.
        val validRange = when (detectedUnit) {
            GlucoseUnit.MMOL_L -> MMOL_L_MIN..MMOL_L_MAX
            GlucoseUnit.MG_DL -> MG_DL_MIN..MG_DL_MAX
        }
        if (value !in validRange) {
            Timber.d("parseGlucoseImage: value %f out of range for %s", value, detectedUnit)
            Timber.w("parseGlucoseImage: value out of range for %s", detectedUnit)
            return GlucoseParseResult.Error("Glucose value is out of valid range for the unit")
        }

        return GlucoseParseResult.Success(value = value, detectedUnit = detectedUnit)
    }

    private sealed interface ToolCallOutcome {
        data class Success(val input: JsonObject) : ToolCallOutcome
        data class Failure(val message: String) : ToolCallOutcome
    }

    private sealed interface AttemptOutcome {
        data class Done(val outcome: ToolCallOutcome) : AttemptOutcome
        data class Retryable(val message: String, val retryAfterMs: Long?) : AttemptOutcome
    }

    /**
     * Sends a forced tool-use request with the image and returns the tool input. Transient failures
     * (429/5xx/529 and network errors) are retried a small, bounded number of times, since the user
     * is waiting and every attempt re-uploads the image. Retries live here rather than on the shared
     * HttpClient so they do not stack with FoodScanner's own retry logic.
     */
    private suspend fun callTool(
        apiKey: String,
        imageBytes: ByteArray,
        tool: AnthropicToolDefinition,
        toolChoice: AnthropicToolChoice,
        prompt: String,
        system: String?,
        tag: String,
    ): ToolCallOutcome {
        val request = AnthropicMessageRequest(
            model = MODEL,
            maxTokens = MAX_TOKENS,
            system = system,
            messages = listOf(
                AnthropicMessage(
                    role = "user",
                    content = listOf(
                        AnthropicContentItem(
                            type = "image",
                            source = AnthropicImageSource(
                                mediaType = "image/jpeg",
                                data = java.util.Base64.getEncoder().encodeToString(imageBytes),
                            ),
                        ),
                        AnthropicContentItem(
                            type = "text",
                            text = prompt,
                        ),
                    ),
                ),
            ),
            tools = listOf(tool),
            toolChoice = toolChoice,
        )
        val trimmedKey = apiKey.trim()

        var attempt = 0
        while (true) {
            when (val result = attemptCall(trimmedKey, request, tag)) {
                is AttemptOutcome.Done -> return result.outcome
                is AttemptOutcome.Retryable -> {
                    if (attempt >= MAX_RETRIES) return ToolCallOutcome.Failure(result.message)
                    val delayMs = retryDelayMs(attempt, result.retryAfterMs)
                    Timber.w("%s: attempt %d failed, retrying after %dms", tag, attempt + 1, delayMs)
                    delay(delayMs)
                    attempt++
                }
            }
        }
    }

    private suspend fun attemptCall(
        apiKey: String,
        request: AnthropicMessageRequest,
        tag: String,
    ): AttemptOutcome {
        return try {
            val startTime = System.currentTimeMillis()
            val response = httpClient.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("%s completed in %dms", tag, elapsed)

            if (!response.status.isSuccess()) {
                val status = response.status.value
                val errorType = try {
                    extractErrorType(response.bodyAsText())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // Only status and error.type are logged; the full body may echo request content.
                Timber.w("%s HTTP %d (%s)", tag, status, errorType ?: "unknown")
                val message = when (status) {
                    401 -> "Authentication failed"
                    429 -> "Rate limited"
                    529 -> "Anthropic is overloaded"
                    in 500..599 -> "Server unavailable"
                    else -> "HTTP error $status"
                }
                return if (status in RETRYABLE_STATUSES) {
                    AttemptOutcome.Retryable(message, parseRetryAfterMs(response.headers["retry-after"]))
                } else {
                    AttemptOutcome.Done(ToolCallOutcome.Failure(message))
                }
            }

            val messageResponse: AnthropicMessageResponse = response.body()
            val toolUseBlock = messageResponse.content
                .firstOrNull { it.type == "tool_use" }
                ?: return AttemptOutcome.Done(ToolCallOutcome.Failure("No tool_use content in response"))

            val input = toolUseBlock.input
                ?: return AttemptOutcome.Done(ToolCallOutcome.Failure("No input in tool_use response"))

            AttemptOutcome.Done(ToolCallOutcome.Success(input))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            Timber.e(e, "%s security exception", tag)
            AttemptOutcome.Done(ToolCallOutcome.Failure("Network access denied. Please check app permissions."))
        } catch (e: Exception) {
            when {
                ApiErrors.isParseError(e) -> {
                    Timber.e(e, "%s parse error", tag)
                    AttemptOutcome.Done(ToolCallOutcome.Failure("Failed to parse response"))
                }
                ApiErrors.isNetworkError(e) -> {
                    Timber.w(e, "%s network error", tag)
                    AttemptOutcome.Retryable("Failed to analyze image", retryAfterMs = null)
                }
                else -> {
                    Timber.e(e, "%s error", tag)
                    AttemptOutcome.Done(ToolCallOutcome.Failure("Failed to analyze image"))
                }
            }
        }
    }

    private fun extractErrorType(body: String): String? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val error = root["error"] as? JsonObject ?: return null
        return (error["type"] as? JsonPrimitive)?.contentOrNull
    }

    private fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }

    private fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
        val backoff = INITIAL_RETRY_DELAY_MS shl attempt
        val jitter = Random.nextLong(0, RETRY_JITTER_MS + 1)
        val base = retryAfterMs ?: (backoff + jitter)
        return base.coerceIn(0, MAX_RETRY_DELAY_MS)
    }
}
