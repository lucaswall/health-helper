package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.AnthropicContentItem
import com.healthhelper.app.data.api.dto.AnthropicImageSource
import com.healthhelper.app.data.api.dto.AnthropicMessage
import com.healthhelper.app.data.api.dto.AnthropicMessageRequest
import com.healthhelper.app.data.api.dto.AnthropicMessageResponse
import com.healthhelper.app.domain.model.BloodPressureParseResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        private const val SYSTEM_PROMPT = """You are a medical device display reader.
Analyze the blood pressure monitor image and extract the reading.
The systolic value is the larger/topmost number.
The diastolic value is the middle number.
Ignore pulse/heart rate.
Respond with ONLY valid JSON in one of these formats:
{"systolic": <integer>, "diastolic": <integer>}
{"error": "<brief reason why reading cannot be determined>"}
Do not include any other text."""
    }

    suspend fun parseBloodPressureImage(
        apiKey: String,
        imageBytes: ByteArray,
    ): BloodPressureParseResult {
        return try {
            val startTime = System.currentTimeMillis()
            val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)

            val request = AnthropicMessageRequest(
                model = MODEL,
                maxTokens = MAX_TOKENS,
                system = SYSTEM_PROMPT,
                messages = listOf(
                    AnthropicMessage(
                        role = "user",
                        content = listOf(
                            AnthropicContentItem(
                                type = "image",
                                source = AnthropicImageSource(
                                    mediaType = "image/jpeg",
                                    data = base64Image,
                                ),
                            ),
                            AnthropicContentItem(
                                type = "text",
                                text = "Extract the blood pressure reading from this image.",
                            ),
                        ),
                    ),
                ),
            )

            val response = httpClient.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("parseBloodPressureImage completed in ${elapsed}ms")

            if (!response.status.isSuccess()) {
                val status = response.status.value
                val message = when (status) {
                    401 -> "Authentication failed"
                    429 -> "Rate limited"
                    else -> "HTTP error $status"
                }
                if (status == 401) {
                    Timber.w("parseBloodPressureImage HTTP error: %d", status)
                } else {
                    Timber.e("parseBloodPressureImage HTTP error: %d", status)
                }
                return BloodPressureParseResult.Error(message)
            }

            val messageResponse: AnthropicMessageResponse = response.body()
            val textContent = messageResponse.content
                .firstOrNull { it.type == "text" }
                ?.text
                ?: return BloodPressureParseResult.Error("No text content in response")

            parseResponseText(textContent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            Timber.e(e, "parseBloodPressureImage serialization error")
            BloodPressureParseResult.Error("Failed to parse response")
        } catch (e: Exception) {
            Timber.e(e, "parseBloodPressureImage error")
            BloodPressureParseResult.Error("Failed to analyze image")
        }
    }

    private fun parseResponseText(text: String): BloodPressureParseResult {
        return try {
            val jsonObj: JsonObject = Json.parseToJsonElement(text.trim()).jsonObject

            if (jsonObj.containsKey("error")) {
                val errorMsg = jsonObj["error"]?.jsonPrimitive?.content ?: "Unknown error"
                Timber.d("parseBloodPressureImage: model returned error: %s", errorMsg)
                return BloodPressureParseResult.Error("Unable to read blood pressure display")
            }

            val systolic = jsonObj["systolic"]?.jsonPrimitive?.intOrNull
            val diastolic = jsonObj["diastolic"]?.jsonPrimitive?.intOrNull

            if (systolic == null || diastolic == null) {
                Timber.w("parseBloodPressureImage: missing systolic or diastolic in response")
                return BloodPressureParseResult.Error("Missing blood pressure values in response")
            }

            if (systolic < SYSTOLIC_MIN || systolic > SYSTOLIC_MAX) {
                Timber.w("parseBloodPressureImage: systolic %d out of range", systolic)
                return BloodPressureParseResult.Error("Systolic value $systolic is out of valid range")
            }

            if (diastolic < DIASTOLIC_MIN || diastolic > DIASTOLIC_MAX) {
                Timber.w("parseBloodPressureImage: diastolic %d out of range", diastolic)
                return BloodPressureParseResult.Error("Diastolic value $diastolic is out of valid range")
            }

            if (systolic <= diastolic) {
                Timber.w(
                    "parseBloodPressureImage: systolic %d not greater than diastolic %d",
                    systolic,
                    diastolic,
                )
                return BloodPressureParseResult.Error("Systolic must be greater than diastolic")
            }

            BloodPressureParseResult.Success(systolic = systolic, diastolic = diastolic)
        } catch (e: Exception) {
            Timber.e(e, "parseBloodPressureImage failed to parse response JSON")
            BloodPressureParseResult.Error("Failed to parse blood pressure values")
        }
    }
}
