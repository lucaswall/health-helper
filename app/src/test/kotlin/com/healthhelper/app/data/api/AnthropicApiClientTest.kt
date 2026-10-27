package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.ImageParseErrorKind
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
import kotlin.test.assertTrue

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
                    "type": "tool_use",
                    "id": "toolu_test",
                    "name": "blood_pressure_reading",
                    "input": {"systolic": $systolic, "diastolic": $diastolic}
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "tool_use",
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
                    "type": "tool_use",
                    "id": "toolu_test",
                    "name": "blood_pressure_reading",
                    "input": {"systolic": 0, "diastolic": 0, "error": "$reason"}
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 100, "output_tokens": 10}
        }
    """.trimIndent()

    @Test
    @DisplayName("returns Success with systolic and diastolic when Haiku responds with valid tool_use")
    fun returnsSuccessOnValidResponse() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 80), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
        assertEquals(80, result.diastolic)
    }

    @Test
    @DisplayName("returns Error when Haiku indicates unreadable display via tool_use error field")
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
        assertEquals(ImageParseErrorKind.AUTH, result.kind)
    }

    @Test
    @DisplayName("returns Error on HTTP 429 (rate limited)")
    fun returnsErrorOn429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals(ImageParseErrorKind.RATE_LIMITED, result.kind)
    }

    @Test
    @DisplayName("maps HTTP 5xx to SERVER and other 4xx to UNKNOWN")
    fun mapsServerAndClientErrorKinds() = runTest {
        val serverResult = createClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
            .parseBloodPressureImage(testApiKey, testImageBytes)
        assertIs<BloodPressureParseResult.Error>(serverResult)
        assertEquals(ImageParseErrorKind.SERVER, serverResult.kind)

        val badRequest = createClient(MockEngine { respondError(HttpStatusCode.BadRequest) })
            .parseGlucoseImage(testApiKey, testImageBytes)
        assertIs<GlucoseParseResult.Error>(badRequest)
        assertEquals(ImageParseErrorKind.UNKNOWN, badRequest.kind)

        val forbidden = createClient(MockEngine { respondError(HttpStatusCode.Forbidden) })
            .parseGlucoseImage(testApiKey, testImageBytes)
        assertIs<GlucoseParseResult.Error>(forbidden)
        assertEquals(ImageParseErrorKind.AUTH, forbidden.kind)
    }

    @Test
    @DisplayName("returns Error on network timeout or IO exception")
    fun returnsErrorOnNetworkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals(ImageParseErrorKind.NETWORK, result.kind)
    }

    @Test
    @DisplayName("returns Error when no tool_use content in response")
    fun returnsErrorWhenToolUseContentMissing() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"msg","content":[{"type":"text","text":"I cannot read this"}]}""",
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

    @Test
    @DisplayName("returns Error on SecurityException (DNS/network permission denied)")
    fun returnsErrorOnSecurityException() = runTest {
        val engine = MockEngine { throw SecurityException("EPERM on DNS resolution") }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertTrue(result.message.contains("Network access denied"))
    }

    @Test
    @DisplayName("request contains tools array with blood_pressure_reading tool")
    fun requestContainsToolsArray() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(content = successResponse(120, 80), headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.parseBloodPressureImage(testApiKey, testImageBytes)

        val body = capturedBody!!
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"blood_pressure_reading\""))
    }

    @Test
    @DisplayName("request contains tool_choice forcing blood_pressure_reading")
    fun requestContainsToolChoice() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(content = successResponse(120, 80), headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.parseBloodPressureImage(testApiKey, testImageBytes)

        val body = capturedBody!!
        assertTrue(body.contains("\"tool_choice\""))
        assertTrue(body.contains("\"blood_pressure_reading\""))
    }

    // --- Glucose parsing tests ---

    private fun glucoseSuccessResponse(value: Double, unit: String): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": "toolu_test",
                    "name": "glucose_reading",
                    "input": {"value": $value, "unit": "$unit"}
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 100, "output_tokens": 20}
        }
    """.trimIndent()

    private fun glucoseErrorResponse(reason: String): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": "toolu_test",
                    "name": "glucose_reading",
                    "input": {"value": 0, "unit": "", "error": "$reason"}
                }
            ],
            "model": "claude-haiku-4-5-20251001",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 100, "output_tokens": 10}
        }
    """.trimIndent()

    @Test
    @DisplayName("glucose: returns Success with value and detected unit mmol/L")
    fun glucoseReturnsSuccessMmolL() = runTest {
        val engine = MockEngine { respond(content = glucoseSuccessResponse(5.6, "mmol/L"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Success>(result)
        assertEquals(5.6, result.value)
        assertEquals(GlucoseUnit.MMOL_L, result.detectedUnit)
    }

    @Test
    @DisplayName("glucose: returns Success with value and detected unit mg/dL")
    fun glucoseReturnsSuccessMgDl() = runTest {
        val engine = MockEngine { respond(content = glucoseSuccessResponse(101.0, "mg/dL"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Success>(result)
        assertEquals(101.0, result.value)
        assertEquals(GlucoseUnit.MG_DL, result.detectedUnit)
    }

    @Test
    @DisplayName("glucose: returns Error when model returns error field")
    fun glucoseReturnsErrorOnModelError() = runTest {
        val engine = MockEngine { respond(content = glucoseErrorResponse("blurry image"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
    }

    @Test
    @DisplayName("glucose: returns Error on HTTP 401")
    fun glucoseReturnsErrorOn401() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Authentication failed", result.message)
    }

    @Test
    @DisplayName("glucose: returns Error on HTTP 429")
    fun glucoseReturnsErrorOn429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Rate limited", result.message)
    }

    @Test
    @DisplayName("glucose: returns Error on network exception")
    fun glucoseReturnsErrorOnNetworkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Failed to analyze image", result.message)
    }

    @Test
    @DisplayName("glucose: returns Error when value is 0 or negative")
    fun glucoseReturnsErrorOnZeroOrNegativeValue() = runTest {
        val engine = MockEngine { respond(content = glucoseSuccessResponse(0.0, "mmol/L"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
    }

    @Test
    @DisplayName("glucose: returns Error when unit string is invalid")
    fun glucoseReturnsErrorOnInvalidUnit() = runTest {
        val engine = MockEngine { respond(content = glucoseSuccessResponse(5.6, "invalid"), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
    }

    @Test
    @DisplayName("glucose: CancellationException propagates")
    fun glucoseCancellationExceptionPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("Cancelled") }
        val client = createClient(engine)

        assertFailsWith<CancellationException> {
            client.parseGlucoseImage(testApiKey, testImageBytes)
        }
    }

    @Test
    @DisplayName("glucose: returns Error on SecurityException")
    fun glucoseReturnsErrorOnSecurityException() = runTest {
        val engine = MockEngine { throw SecurityException("EPERM") }
        val client = createClient(engine)

        val result = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertTrue(result.message.contains("Network access denied"))
    }

    // --- Tool input helpers ---

    private fun toolUseResponse(toolName: String, inputJson: String): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {"type": "tool_use", "id": "toolu_test", "name": "$toolName", "input": $inputJson}
            ]
        }
    """.trimIndent()

    // --- JSON null in error field ---

    @Test
    @DisplayName("BP: explicit \"error\": null is treated as no error")
    fun bpErrorNullIsSuccess() = runTest {
        val engine = MockEngine {
            respond(
                content = toolUseResponse("blood_pressure_reading", """{"systolic":120,"diastolic":80,"error":null}"""),
                headers = jsonHeaders,
            )
        }
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
    }

    @Test
    @DisplayName("glucose: explicit \"error\": null is treated as no error")
    fun glucoseErrorNullIsSuccess() = runTest {
        val engine = MockEngine {
            respond(
                content = toolUseResponse("glucose_reading", """{"value":5.6,"unit":"mmol/L","error":null}"""),
                headers = jsonHeaders,
            )
        }
        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Success>(result)
        assertEquals(5.6, result.value)
    }

    @Test
    @DisplayName("glucose: JSON null unit is reported as missing, not as unit \"null\"")
    fun glucoseNullUnitIsMissing() = runTest {
        val engine = MockEngine {
            respond(
                content = toolUseResponse("glucose_reading", """{"value":5.6,"unit":null}"""),
                headers = jsonHeaders,
            )
        }
        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Missing glucose values in response", result.message)
    }

    // --- Glucose unit normalization ---

    @Test
    @DisplayName("glucose: unit variants in different case/spacing are accepted")
    fun glucoseUnitVariantsAccepted() = runTest {
        val cases = listOf(
            "mg/dl" to GlucoseUnit.MG_DL,
            "MG/DL" to GlucoseUnit.MG_DL,
            " mg / dL " to GlucoseUnit.MG_DL,
            "mmol/l" to GlucoseUnit.MMOL_L,
            "MMOL/L" to GlucoseUnit.MMOL_L,
        )
        cases.forEach { (unit, expected) ->
            val value = if (expected == GlucoseUnit.MG_DL) 101.0 else 5.6
            val engine = MockEngine { respond(content = glucoseSuccessResponse(value, unit), headers = jsonHeaders) }
            val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)
            assertIs<GlucoseParseResult.Success>(result, "unit '$unit'")
            assertEquals(expected, result.detectedUnit)
        }
    }

    @Test
    @DisplayName("glucose: tool schema constrains unit with an enum")
    fun glucoseSchemaHasUnitEnum() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(content = glucoseSuccessResponse(5.6, "mmol/L"), headers = jsonHeaders)
        }
        createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertTrue(capturedBody!!.contains("\"enum\":[\"mmol/L\",\"mg/dL\"]"))
    }

    // --- Glucose plausible range ---

    @Test
    @DisplayName("glucose: values within unit range bounds are accepted")
    fun glucoseRangeBoundsAccepted() = runTest {
        listOf(0.5 to "mmol/L", 40.0 to "mmol/L", 10.0 to "mg/dL", 720.0 to "mg/dL").forEach { (value, unit) ->
            val engine = MockEngine { respond(content = glucoseSuccessResponse(value, unit), headers = jsonHeaders) }
            val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)
            assertIs<GlucoseParseResult.Success>(result, "$value $unit")
        }
    }

    @Test
    @DisplayName("glucose: values implausible for the stated unit are rejected")
    fun glucoseOutOfRangeRejected() = runTest {
        listOf(0.4 to "mmol/L", 110.0 to "mmol/L", 5.6 to "mg/dL", 721.0 to "mg/dL").forEach { (value, unit) ->
            val engine = MockEngine { respond(content = glucoseSuccessResponse(value, unit), headers = jsonHeaders) }
            val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)
            assertIs<GlucoseParseResult.Error>(result, "$value $unit")
            assertTrue(result.message.contains("out of valid range"))
        }
    }

    // --- Error messages do not carry health values ---

    @Test
    @DisplayName("BP: out-of-range error message does not include the numeric reading")
    fun bpOutOfRangeMessageHasNoValue() = runTest {
        val engine = MockEngine { respond(content = successResponse(301, 80), headers = jsonHeaders) }
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertTrue(!result.message.contains("301"))
    }

    // --- Parse errors (Ktor 3 JsonConvertException) ---

    @Test
    @DisplayName("malformed JSON body maps to 'Failed to parse response' for both methods")
    fun malformedJsonMapsToParseError() = runTest {
        val engine = MockEngine { respond(content = "{not json", headers = jsonHeaders) }
        val client = createClient(engine)

        val bp = client.parseBloodPressureImage(testApiKey, testImageBytes)
        val glucose = client.parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(bp)
        assertEquals("Failed to parse response", bp.message)
        assertIs<GlucoseParseResult.Error>(glucose)
        assertEquals("Failed to parse response", glucose.message)
    }

    @Test
    @DisplayName("200 text/html body maps to 'Failed to parse response' and is not retried")
    fun htmlBodyMapsToParseError() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = "<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals("Failed to parse response", result.message)
        assertEquals(1, calls)
    }

    // --- Retry behaviour ---

    @Test
    @DisplayName("529 overloaded is retried and succeeds on a later attempt")
    fun overloadedIsRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    content = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
                    status = HttpStatusCode.fromValue(529),
                    headers = jsonHeaders,
                )
            } else {
                respond(content = successResponse(120, 80), headers = jsonHeaders)
            }
        }
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(2, calls)
    }

    @Test
    @DisplayName("persistent 529 gives up after bounded retries with an overloaded message")
    fun overloadedExhaustsRetries() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = "", status = HttpStatusCode.fromValue(529), headers = jsonHeaders)
        }
        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Anthropic is overloaded", result.message)
        assertEquals(1 + AnthropicApiClient.MAX_RETRIES, calls)
    }

    @Test
    @DisplayName("503 and IOException are retried; 401 and 400 are not")
    fun retryClassification() = runTest {
        val cases = listOf(
            HttpStatusCode.ServiceUnavailable to 1 + AnthropicApiClient.MAX_RETRIES,
            HttpStatusCode.Unauthorized to 1,
            HttpStatusCode.BadRequest to 1,
        )
        for ((code, expected) in cases) {
            var calls = 0
            val engine = MockEngine {
                calls++
                respondError(code)
            }
            createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)
            assertEquals(expected, calls, "status $code")
        }

        var ioCalls = 0
        val ioEngine = MockEngine {
            ioCalls++
            throw java.io.IOException("offline")
        }
        createClient(ioEngine).parseBloodPressureImage(testApiKey, testImageBytes)
        assertEquals(1 + AnthropicApiClient.MAX_RETRIES, ioCalls)
    }

    @Test
    @DisplayName("timeouts are not retried and report a timeout message")
    fun timeoutIsNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw java.net.SocketTimeoutException("read timed out")
        }
        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals("Request timed out", result.message)
        assertEquals(1, calls)
    }

    @Test
    @DisplayName("retry-after header is honored but capped")
    fun retryAfterHonoredAndCapped() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf("retry-after", "3600"),
                )
            } else {
                respond(content = successResponse(120, 80), headers = jsonHeaders)
            }
        }
        val start = testScheduler.currentTime
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        val waited = testScheduler.currentTime - start
        assertTrue(waited in 1..4_000, "waited $waited ms")
    }

    // --- API key normalization ---

    @Test
    @DisplayName("API key with surrounding whitespace/newline is trimmed before sending")
    fun apiKeyTrimmed() = runTest {
        var capturedApiKey: String? = null
        val engine = MockEngine { request ->
            capturedApiKey = request.headers["x-api-key"]
            respond(content = successResponse(120, 80), headers = jsonHeaders)
        }
        val result = createClient(engine).parseBloodPressureImage(" $testApiKey\n", testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(testApiKey, capturedApiKey)
    }
}
