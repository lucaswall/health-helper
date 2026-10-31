package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.AnthropicContentItem
import com.healthhelper.app.data.api.dto.AnthropicImageSource
import com.healthhelper.app.data.api.dto.AnthropicMessage
import com.healthhelper.app.data.api.dto.AnthropicMessageRequest
import com.healthhelper.app.data.api.dto.AnthropicMessageResponse
import com.healthhelper.app.data.api.dto.AnthropicOutputConfig
import com.healthhelper.app.data.api.dto.AnthropicOutputFormat
import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.ImageParseErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        private const val MODEL = "claude-opus-5-5"

        // Thinking is always on for this model; effort sets how much it reasons before answering. On
        // test photos low read as accurately as medium and declined fewer legible ones.
        private const val EFFORT = "low"

        // Room for the reasoning plus the short JSON answer; thinking counts toward this cap.
        private const val MAX_TOKENS = 8_000

        // On a safety-classifier decline the API re-runs the same request on a fallback model.
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val FALLBACKS = "default"

        // A reasoned answer about a full-resolution image takes longer than the shared 30s client timeout.
        private const val REQUEST_TIMEOUT_MS = 60_000L

        private const val SYSTOLIC_MIN = 60
        private const val SYSTOLIC_MAX = 300
        private const val DIASTOLIC_MIN = 30
        private const val DIASTOLIC_MAX = 200
        private val HEART_RATE_RANGE = BloodPressureReading.HEART_RATE_RANGE
        private const val MMOL_L_MIN = 0.5
        private const val MMOL_L_MAX = 40.0
        private const val MG_DL_MIN = 10.0
        private const val MG_DL_MAX = 720.0
        internal const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 500L
        private const val RETRY_JITTER_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 4_000L
        private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504, 529)

        /** A glucose result as displayed: whole mg/dL, or mmol/L with a decimal point. */
        private val GLUCOSE_READING_PATTERN = Regex("""\d{1,3}(\.\d{1,2})?""")

        private const val SEVEN_SEGMENT_NOTES =
            "Seven-segment digits are easy to misread when a segment is faint, washed out by glare or a " +
                "reflection, or seen at an angle. The usual confusions are 1/7, 3/9, 5/6, 6/8, 8/9 and 0/8, " +
                "and a leading 1 (just two short vertical segments) is easy to overlook in values of 100 and " +
                "above. Many LCDs also show unlit segments faintly; those are not lit. Read each digit from " +
                "the segments that are actually dark."

        private const val DOUBT_RULE =
            "Set readable to true only when you are sure of every digit you return. If any digit could be " +
                "a different one, set readable to false rather than picking the likelier digit, and say in " +
                "problem which digit is in doubt and how to get a clearer photo; the app shows that text to " +
                "the user."

        private val BP_SYSTEM = "You read blood pressure monitor displays from photos. The user has just " +
            "taken a measurement and photographed the monitor. The app fills in the values you return and " +
            "the user confirms them before saving, so a plausible-looking wrong number is the worst outcome; " +
            "an unread photo only costs the user a retake or typing the values in. $DOUBT_RULE If only the " +
            "pulse is unclear, return null for pulse and keep the rest.\n\n" +
            "Home monitors usually show the result on a segmented LCD with three numbers stacked top to " +
            "bottom: systolic (the largest, labelled SYS or mmHg), diastolic (DIA) and pulse (PUL, PULSE, " +
            "/min, or next to a heart symbol). Some models show pulse in a smaller font or beside the other " +
            "two. The screen can also show the date, time, a memory number, an average marker or status " +
            "icons (irregular heartbeat, cuff fit, battery); none of those are part of the reading.\n\n" +
            "$SEVEN_SEGMENT_NOTES\n\n" +
            "If the photo shows something other than a finished reading (an error code, a measurement in " +
            "progress, a blank screen, or a different kind of device), set readable to false and say what " +
            "you see in problem."

        private val GLUCOSE_SYSTEM = "You read blood glucose meter displays from photos. The user has just " +
            "measured and photographed the meter. The app fills in the value you return and the user " +
            "confirms it before saving, so a plausible-looking wrong number is the worst outcome; an unread " +
            "photo only costs the user a retake or typing the value in. $DOUBT_RULE\n\n" +
            "The result is the largest number on the screen, usually with its unit beside it. mg/dL results " +
            "are whole numbers (98, 142); mmol/L results have one decimal place (5.6, 12.0). The decimal " +
            "point is a small dot between digits that is easy to miss, and missing it turns 5.6 mmol/L into " +
            "56, so look for it and report the result exactly as displayed, dot included. The screen can " +
            "also show the date, time, a memory number, meal markers (before/after-meal icons, an apple), a " +
            "ketone or temperature warning and a battery icon; none of those are the result. HI or LO means " +
            "the result is outside the meter's range: set readable to false.\n\n" +
            "$SEVEN_SEGMENT_NOTES\n\n" +
            "If the photo shows something other than a finished result (an error code such as E-3, a " +
            "test-strip prompt, a blank screen, or a different kind of device), set readable to false and " +
            "say what you see in problem."

        private const val PROBLEM_DESCRIPTION =
            "When readable is false: what you see and how to get a readable photo, in one or two short " +
                "sentences addressed to the user. Null when readable is true."

        private val BP_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("readable") {
                    put("type", "boolean")
                    put("description", "True only when you are sure of every digit of systolic and diastolic.")
                }
                nullable("systolic", "integer", "Systolic pressure in mmHg, the top and largest number.")
                nullable("diastolic", "integer", "Diastolic pressure in mmHg, the second number.")
                nullable(
                    "pulse",
                    "integer",
                    "Pulse in beats per minute, the third value. Null when the display does not show it.",
                )
                nullable("problem", "string", PROBLEM_DESCRIPTION)
            }
            requireAll("readable", "systolic", "diastolic", "pulse", "problem")
        }

        private val GLUCOSE_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("readable") {
                    put("type", "boolean")
                    put("description", "True only when you are sure of every digit of the result.")
                }
                nullable(
                    "reading",
                    "string",
                    "The result exactly as displayed, digits and decimal point only (\"142\", \"5.6\").",
                )
                putJsonObject("unit") {
                    putJsonArray("anyOf") {
                        add(
                            buildJsonObject {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("mg/dL"))
                                    add(JsonPrimitive("mmol/L"))
                                }
                            },
                        )
                        add(buildJsonObject { put("type", "null") })
                    }
                    put("description", "The unit label shown on the screen. Null when no unit label is visible.")
                }
                nullable("problem", "string", PROBLEM_DESCRIPTION)
            }
            requireAll("readable", "reading", "unit", "problem")
        }

        /** A property that holds [type] or null, so every property can be required and the shape stays fixed. */
        private fun JsonObjectBuilder.nullable(name: String, type: String, description: String) {
            putJsonObject(name) {
                putJsonArray("anyOf") {
                    add(buildJsonObject { put("type", type) })
                    add(buildJsonObject { put("type", "null") })
                }
                put("description", description)
            }
        }

        private fun JsonObjectBuilder.requireAll(vararg names: String) {
            putJsonArray("required") { names.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }
    }

    suspend fun parseBloodPressureImage(
        apiKey: String,
        imageBytes: ByteArray,
    ): BloodPressureParseResult {
        val outcome = callModel(
            apiKey = apiKey,
            imageBytes = imageBytes,
            system = BP_SYSTEM,
            prompt = "Read the blood pressure result on this monitor.",
            schema = BP_SCHEMA,
            tag = "parseBloodPressureImage",
        )
        return when (outcome) {
            is ModelCallOutcome.Failure -> BloodPressureParseResult.Error(outcome.message, outcome.kind)
            is ModelCallOutcome.Success -> parseBloodPressureAnswer(outcome.answer)
        }
    }

    private fun parseBloodPressureAnswer(answer: JsonObject): BloodPressureParseResult {
        if (answer.boolean("readable") == false) {
            // The problem text can quote digits off the display, so it stays at DEBUG.
            val problem = answer.userDetail()
            Timber.d("parseBloodPressureImage: unreadable: %s", problem)
            return BloodPressureParseResult.Error("Unable to read blood pressure display", detail = problem)
        }

        val systolic = answer.int("systolic")
        val diastolic = answer.int("diastolic")

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

        return BloodPressureParseResult.Success(
            systolic = systolic,
            diastolic = diastolic,
            heartRate = parseHeartRate(answer),
        )
    }

    /** Pulse is optional: a missing or implausible value is dropped instead of failing the scan. */
    private fun parseHeartRate(answer: JsonObject): Int? {
        val heartRate = answer.int("pulse") ?: return null
        if (heartRate !in HEART_RATE_RANGE) {
            Timber.d("parseBloodPressureImage: heart rate %d out of range, ignoring", heartRate)
            Timber.w("parseBloodPressureImage: heart rate out of range, ignoring")
            return null
        }
        return heartRate
    }

    suspend fun parseGlucoseImage(
        apiKey: String,
        imageBytes: ByteArray,
    ): GlucoseParseResult {
        val outcome = callModel(
            apiKey = apiKey,
            imageBytes = imageBytes,
            system = GLUCOSE_SYSTEM,
            prompt = "Read the glucose result on this meter.",
            schema = GLUCOSE_SCHEMA,
            tag = "parseGlucoseImage",
        )
        return when (outcome) {
            is ModelCallOutcome.Failure -> GlucoseParseResult.Error(outcome.message, outcome.kind)
            is ModelCallOutcome.Success -> parseGlucoseAnswer(outcome.answer)
        }
    }

    private fun parseGlucoseAnswer(answer: JsonObject): GlucoseParseResult {
        if (answer.boolean("readable") == false) {
            // The problem text can quote digits off the display, so it stays at DEBUG.
            val problem = answer.userDetail()
            Timber.d("parseGlucoseImage: unreadable: %s", problem)
            return GlucoseParseResult.Error("Unable to read glucose display", detail = problem)
        }

        // The reading comes back as displayed text so the decimal point survives: 12.0 mmol/L and
        // 12 would be the same JSON number, and the dot is what tells the two units apart.
        val reading = answer.string("reading")?.trim()?.replace(',', '.')
        if (reading.isNullOrEmpty()) {
            Timber.w("parseGlucoseImage: missing reading in response")
            return GlucoseParseResult.Error("Missing glucose values in response")
        }
        if (!GLUCOSE_READING_PATTERN.matches(reading)) {
            Timber.d("parseGlucoseImage: reading %s is not a number", reading)
            Timber.w("parseGlucoseImage: reading is not a number")
            return GlucoseParseResult.Error("Unrecognized glucose reading")
        }
        val value = reading.toDouble()

        if (value <= 0) {
            Timber.d("parseGlucoseImage: value %f is not positive", value)
            Timber.w("parseGlucoseImage: value is not positive")
            return GlucoseParseResult.Error("Glucose value must be positive")
        }

        val unitStr = answer.string("unit")
        val detectedUnit = when (unitStr?.trim()?.lowercase()?.replace(" ", "")) {
            "mmol/l" -> GlucoseUnit.MMOL_L
            "mg/dl" -> GlucoseUnit.MG_DL
            // No unit label on screen: mmol/L results carry a decimal place, mg/dL results don't.
            null -> if ('.' in reading) GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL
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

    private sealed interface ModelCallOutcome {
        data class Success(val answer: JsonObject) : ModelCallOutcome
        data class Failure(
            val message: String,
            val kind: ImageParseErrorKind = ImageParseErrorKind.UNREADABLE,
        ) : ModelCallOutcome
    }

    private sealed interface AttemptOutcome {
        data class Done(val outcome: ModelCallOutcome) : AttemptOutcome
        data class Retryable(
            val message: String,
            val kind: ImageParseErrorKind,
            val retryAfterMs: Long?,
        ) : AttemptOutcome
    }

    /**
     * Sends the image and returns the model's answer, a JSON object that structured outputs guarantee
     * matches [schema]; the model reasons about the display before it answers. Transient failures
     * (429/5xx/529 and network errors) are retried a small, bounded number of times, since the user
     * is waiting and every attempt re-uploads the image. Retries live here rather than on the shared
     * HttpClient so they do not stack with FoodScanner's own retry logic.
     */
    private suspend fun callModel(
        apiKey: String,
        imageBytes: ByteArray,
        system: String,
        prompt: String,
        schema: JsonObject,
        tag: String,
    ): ModelCallOutcome {
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
            outputConfig = AnthropicOutputConfig(
                effort = EFFORT,
                format = AnthropicOutputFormat(type = "json_schema", schema = schema),
            ),
            fallbacks = FALLBACKS,
        )
        val trimmedKey = apiKey.trim()

        var attempt = 0
        while (true) {
            when (val result = attemptCall(trimmedKey, request, tag)) {
                is AttemptOutcome.Done -> return result.outcome
                is AttemptOutcome.Retryable -> {
                    if (attempt >= MAX_RETRIES) return ModelCallOutcome.Failure(result.message, result.kind)
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
                header("anthropic-beta", FALLBACK_BETA)
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
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
                    AttemptOutcome.Retryable(
                        message,
                        httpStatusErrorKind(status),
                        parseRetryAfterMs(response.headers["retry-after"]),
                    )
                } else {
                    AttemptOutcome.Done(ModelCallOutcome.Failure(message, httpStatusErrorKind(status)))
                }
            }

            val messageResponse: AnthropicMessageResponse = response.body()
            when (messageResponse.stopReason) {
                "refusal" -> {
                    Timber.w("%s: model declined the request", tag)
                    return AttemptOutcome.Done(ModelCallOutcome.Failure("Model declined to read the image"))
                }
                "max_tokens" -> {
                    Timber.w("%s: response cut off at max_tokens", tag)
                    return AttemptOutcome.Done(
                        ModelCallOutcome.Failure("Response was cut off", ImageParseErrorKind.UNKNOWN),
                    )
                }
            }

            // The answer is the last text block; thinking blocks come before it.
            val answerText = messageResponse.content.lastOrNull { it.type == "text" }?.text
                ?: return AttemptOutcome.Done(ModelCallOutcome.Failure("No text content in response"))

            val answer = Json.parseToJsonElement(answerText) as? JsonObject
                ?: return AttemptOutcome.Done(
                    ModelCallOutcome.Failure("Failed to parse response", ImageParseErrorKind.SERVER),
                )

            AttemptOutcome.Done(ModelCallOutcome.Success(answer))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            Timber.e(e, "%s security exception", tag)
            AttemptOutcome.Done(
                ModelCallOutcome.Failure(
                    "Network access denied. Please check app permissions.",
                    ImageParseErrorKind.NETWORK,
                ),
            )
        } catch (e: Exception) {
            when {
                ApiErrors.isParseError(e) -> {
                    // Decode error messages embed a snippet of the JSON body (which may contain the
                    // extracted health values), so only the exception type reaches Sentry.
                    Timber.d(e, "%s parse error detail", tag)
                    Timber.e("%s parse error (%s)", tag, e::class.simpleName)
                    AttemptOutcome.Done(ModelCallOutcome.Failure("Failed to parse response", ImageParseErrorKind.SERVER))
                }
                ApiErrors.isNetworkError(e) && ApiErrors.isTimeout(e) -> {
                    // A timeout already cost the user up to the full request timeout; retrying
                    // (and re-uploading the image) would multiply that wait.
                    Timber.w(e, "%s timed out", tag)
                    AttemptOutcome.Done(ModelCallOutcome.Failure("Request timed out", ImageParseErrorKind.NETWORK))
                }
                ApiErrors.isNetworkError(e) -> {
                    Timber.w(e, "%s network error", tag)
                    AttemptOutcome.Retryable(
                        "Failed to analyze image",
                        ImageParseErrorKind.NETWORK,
                        retryAfterMs = null,
                    )
                }
                else -> {
                    Timber.e(e, "%s error", tag)
                    AttemptOutcome.Done(ModelCallOutcome.Failure("Failed to analyze image", ImageParseErrorKind.UNKNOWN))
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

private fun httpStatusErrorKind(status: Int): ImageParseErrorKind = when {
    status == 401 || status == 403 -> ImageParseErrorKind.AUTH
    status == 429 -> ImageParseErrorKind.RATE_LIMITED
    // 5xx (incl. 529 overloaded) are transient; other 4xx are request problems a retry won't fix.
    status >= 500 -> ImageParseErrorKind.SERVER
    else -> ImageParseErrorKind.UNKNOWN
}

private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

/** String value of [name], or null for a missing property or a JSON null. */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Longest model explanation shown to the user. */
private const val MAX_DETAIL_CHARS = 300

/** The model's explanation for the user, trimmed and capped; null when it gave none. */
private fun JsonObject.userDetail(): String? =
    string("problem")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS)
