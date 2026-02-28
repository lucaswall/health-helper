package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.BloodPressureParseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AnthropicApiClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val testApiKey = "sk-ant-test123"
    private val testImageBytes = ByteArray(100) { it.toByte() }

    private fun createClient(mockEngine: MockEngine): AnthropicApiClient {
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return AnthropicApiClient(httpClient)
    }

    private fun successResponse(systolic: Int, diastolic: Int): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {
                    "type": "text",
                    "text": "{\"systolic\": $systolic, \"diastolic\": $diastolic}"
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 100, "output_tokens": 20}
        }
    """.trimIndent()

    private fun errorResponse(reason: String): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {
                    "type": "text",
                    "text": "{\"error\": \"$reason\"}"
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 100, "output_tokens": 10}
        }
    """.trimIndent()

    @Test
    @DisplayName("returns Success with systolic and diastolic when Haiku responds with valid JSON")
    fun returnsSuccessOnValidResponse() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 80), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
        assertEquals(80, result.diastolic)
    }

    @Test
    @DisplayName("returns Error when Haiku indicates unreadable display")
    fun returnsErrorOnUnreadableDisplay() = runTest {
        val engine = MockEngine { respond(content = errorResponse("unreadable display"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error on HTTP 401 (bad API key)")
    fun returnsErrorOn401() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error on HTTP 429 (rate limited)")
    fun returnsErrorOn429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error on network timeout or IO exception")
    fun returnsErrorOnNetworkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error on malformed response JSON")
    fun returnsErrorOnMalformedJson() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"msg","content":[{"type":"text","text":"not json at all"}]}""",
                headers = jsonHeaders,
            )
        }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error when systolic is out of range (below 60)")
    fun returnsErrorWhenSystolicTooLow() = runTest {
        val engine = MockEngine { respond(content = successResponse(59, 40), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error when systolic is out of range (above 300)")
    fun returnsErrorWhenSystolicTooHigh() = runTest {
        val engine = MockEngine { respond(content = successResponse(301, 80), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error when diastolic is out of range (below 30)")
    fun returnsErrorWhenDiastolicTooLow() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 29), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error when diastolic is out of range (above 200)")
    fun returnsErrorWhenDiastolicTooHigh() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 201), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("returns Error when systolic is not greater than diastolic")
    fun returnsErrorWhenSystolicNotGreaterThanDiastolic() = runTest {
        val engine = MockEngine { respond(content = successResponse(80, 120), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
    }

    @Test
    @DisplayName("CancellationException propagates from parseBloodPressureImage")
    fun cancellationExceptionPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("Cancelled") }
        val client = createClient(engine)

        assertFailsWith<CancellationException> {
            client.parseBloodPressureImage(testApiKey, testImageBytes)
        }
    }

    @Test
    @DisplayName("x-api-key header is sent in the request")
    fun apiKeyHeaderIsSent() = runTest {
        var capturedApiKey: String? = null
        val engine = MockEngine { request ->
            capturedApiKey = request.headers["x-api-key"]
            respond(content = successResponse(120, 80), headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertEquals(testApiKey, capturedApiKey)
    }

    @Test
    @DisplayName("anthropic-version header is sent in the request")
    fun anthropicVersionHeaderIsSent() = runTest {
        var capturedVersion: String? = null
        val engine = MockEngine { request ->
            capturedVersion = request.headers["anthropic-version"]
            respond(content = successResponse(120, 80), headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertEquals("2023-06-01", capturedVersion)
    }

    @Test
    @DisplayName("accepts valid boundary values for systolic (60) and diastolic (30)")
    fun acceptsValidBoundaryLow() = runTest {
        val engine = MockEngine { respond(content = successResponse(60, 30), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(60, result.systolic)
        assertEquals(30, result.diastolic)
    }

    @Test
    @DisplayName("accepts valid boundary values for systolic (300) and diastolic (200)")
    fun acceptsValidBoundaryHigh() = runTest {
        val engine = MockEngine { respond(content = successResponse(300, 200), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(300, result.systolic)
        assertEquals(200, result.diastolic)
    }
}
