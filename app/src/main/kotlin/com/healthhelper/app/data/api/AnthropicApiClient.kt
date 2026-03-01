package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.AnthropicContentItem
import com.healthhelper.app.data.api.dto.AnthropicImageSource
import com.healthhelper.app.data.api.dto.AnthropicMessage
import com.healthhelper.app.data.api.dto.AnthropicMessageRequest
import com.healthhelper.app.data.api.dto.AnthropicMessageResponse
import com.healthhelper.app.data.api.dto.AnthropicToolChoice
import com.healthhelper.app.data.api.dto.AnthropicToolDefinition
import com.healthhelper.app.domain.model.BloodPressureParseResult
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
                                text = "Extract the blood pressure reading from this monitor display. " +
                                    "The systolic value is the larger/topmost number. " +
                                    "The diastolic value is the middle number. Ignore pulse/heart rate.",
                            ),
                        ),
                    ),
                ),
                tools = listOf(BP_TOOL),
                toolChoice = BP_TOOL_CHOICE,
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
                val errorBody = try {
                    response.bodyAsText()
                } catch (_: Exception) {
                    ""
                }
                val message = when (status) {
                    401 -> "Authentication failed"
                    429 -> "Rate limited"
                    else -> "HTTP error $status"
                }
                if (status == 401) {
                    Timber.w("parseBloodPressureImage HTTP %d: %s", status, errorBody)
                } else {
                    Timber.e("parseBloodPressureImage HTTP %d: %s", status, errorBody)
                }
                return BloodPressureParseResult.Error(message)
            }

            val messageResponse: AnthropicMessageResponse = response.body()
            val toolUseBlock = messageResponse.content
                .firstOrNull { it.type == "tool_use" }
                ?: return BloodPressureParseResult.Error("No tool_use content in response")

            val input = toolUseBlock.input
                ?: return BloodPressureParseResult.Error("No input in tool_use response")

            parseToolInput(input)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            Timber.e(e, "parseBloodPressureImage security exception")
            BloodPressureParseResult.Error("Network access denied. Please check app permissions.")
        } catch (e: SerializationException) {
            Timber.e(e, "parseBloodPressureImage serialization error")
            BloodPressureParseResult.Error("Failed to parse response")
        } catch (e: Exception) {
            Timber.e(e, "parseBloodPressureImage error")
            BloodPressureParseResult.Error("Failed to analyze image")
        }
    }

    private fun parseToolInput(input: JsonObject): BloodPressureParseResult {
        val errorField = input["error"]?.jsonPrimitive?.content
        if (!errorField.isNullOrBlank()) {
            Timber.d("parseBloodPressureImage: model returned error: %s", errorField)
            return BloodPressureParseResult.Error("Unable to read blood pressure display")
        }

        val systolic = input["systolic"]?.jsonPrimitive?.intOrNull
        val diastolic = input["diastolic"]?.jsonPrimitive?.intOrNull

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

        return BloodPressureParseResult.Success(systolic = systolic, diastolic = diastolic)
    }
}
