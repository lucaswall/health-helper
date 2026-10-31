package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.BloodPressureParseResult
import com.healthhelper.app.domain.model.GlucoseParseResult
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.ImageParseErrorKind
import com.healthhelper.app.testutil.RecordingTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
                // Same settings as AppModule, so request bodies match what production sends.
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
        return AnthropicApiClient(httpClient)
    }

    /** A message whose structured-output answer is [answerJson], after a thinking block as the API returns it. */
    private fun answerResponse(answerJson: String, stopReason: String = "end_turn"): String = """
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "content": [
                {"type": "thinking", "thinking": "", "signature": "sig"},
                {"type": "text", "text": ${JsonPrimitive(answerJson)}}
            ],
            "model": "claude-opus-5-5",
            "stop_reason": "$stopReason",
            "usage": {"input_tokens": 100, "output_tokens": 20}
        }
    """.trimIndent()

    private fun successResponse(systolic: Int, diastolic: Int, pulse: Int? = null): String = answerResponse(
        """{"readable":true,"systolic":$systolic,"diastolic":$diastolic,"pulse":$pulse,"problem":null}""",
    )

    private fun successResponseWithHeartRate(systolic: Int, diastolic: Int, heartRate: Int): String =
        successResponse(systolic, diastolic, pulse = heartRate)

    private fun errorResponse(reason: String): String = answerResponse(
        """{"readable":false,"systolic":null,"diastolic":null,"pulse":null,"problem":"$reason"}""",
    )

    private suspend fun captureRequest(
        responseBody: String,
        parse: suspend (AnthropicApiClient) -> Unit,
    ): CapturedRequest {
        var captured: CapturedRequest? = null
        val engine = MockEngine { request ->
            captured = CapturedRequest(
                body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject,
                betaHeader = request.headers["anthropic-beta"],
            )
            respond(content = responseBody, headers = jsonHeaders)
        }
        parse(createClient(engine))
        return captured!!
    }

    private data class CapturedRequest(val body: JsonObject, val betaHeader: String?)

    @Test
    @DisplayName("returns Success with systolic and diastolic when the model answers with a valid reading")
    fun returnsSuccessOnValidResponse() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 80), headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
        assertEquals(80, result.diastolic)
    }

    @Test
    @DisplayName("returns Error when the model reports the display as unreadable")
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
    @DisplayName("returns Error when the response has no text answer")
    fun returnsErrorWhenTextAnswerMissing() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"msg","content":[{"type":"thinking","thinking":"","signature":"s"}]}""",
                headers = jsonHeaders,
            )
        }
        val client = createClient(engine)

        val result = client.parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals("No text content in response", result.message)
    }

    @Test
    @DisplayName("an answer that is not JSON maps to 'Failed to parse response'")
    fun nonJsonAnswerIsParseError() = runTest {
        val engine = MockEngine { respond(content = answerResponse("I cannot read this"), headers = jsonHeaders) }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals("Failed to parse response", result.message)
    }

    @Test
    @DisplayName("a refusal is an UNREADABLE error and is not retried")
    fun refusalIsUnreadableAndNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                content = """{"id":"msg","content":[],"stop_reason":"refusal"}""",
                headers = jsonHeaders,
            )
        }

        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals(ImageParseErrorKind.UNREADABLE, result.kind)
        assertEquals(1, calls)
    }

    @Test
    @DisplayName("an answer cut off at max_tokens is an UNKNOWN error")
    fun maxTokensIsUnknownError() = runTest {
        val engine = MockEngine {
            respond(content = answerResponse("""{"readable":tr""", stopReason = "max_tokens"), headers = jsonHeaders)
        }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals(ImageParseErrorKind.UNKNOWN, result.kind)
    }

    @Test
    @DisplayName("readable answer without systolic or diastolic reports missing values")
    fun readableAnswerWithMissingValues() = runTest {
        val engine = MockEngine {
            respond(
                content = answerResponse(
                    """{"readable":true,"systolic":null,"diastolic":80,"pulse":null,"problem":null}""",
                ),
                headers = jsonHeaders,
            )
        }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals("Missing blood pressure values in response", result.message)
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
    @DisplayName("request asks Claude Opus 5.5 for a structured answer with reasoning effort and fallbacks")
    fun requestShape() = runTest {
        val request = captureRequest(successResponse(120, 80)) {
            it.parseBloodPressureImage(testApiKey, testImageBytes)
        }
        val body = request.body

        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        val outputConfig = body["output_config"]!!.jsonObject
        assertEquals("low", outputConfig["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", outputConfig["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("server-side-fallback-2026-07-01", request.betaHeader)
        // Forced tool use is rejected by this model, and the old tool-based request must not linger.
        assertTrue("tools" !in body && "tool_choice" !in body && "thinking" !in body)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("SYS"))
    }

    @Test
    @DisplayName("the image comes before the text in the user message")
    fun imageBeforeText() = runTest {
        val request = captureRequest(glucoseSuccessResponse("142", "mg/dL")) {
            it.parseGlucoseImage(testApiKey, testImageBytes)
        }
        val content = request.body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("base64", content[0].jsonObject["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    // --- Glucose parsing tests ---

    private fun glucoseSuccessResponse(reading: String, unit: String?): String = answerResponse(
        """{"readable":true,"reading":"$reading","unit":${unit?.let { "\"$it\"" }},"problem":null}""",
    )

    private fun glucoseSuccessResponse(value: Double, unit: String): String =
        glucoseSuccessResponse(value.toString(), unit)

    private fun glucoseErrorResponse(reason: String): String = answerResponse(
        """{"readable":false,"reading":null,"unit":null,"problem":"$reason"}""",
    )

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
    @DisplayName("glucose: returns Error when the model reports the display as unreadable")
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

    // --- The model's explanation for an unreadable display ---

    @Test
    @DisplayName("an unreadable answer carries the model's explanation as the user-facing detail")
    fun unreadableAnswerCarriesDetail() = runTest {
        val bpEngine = MockEngine { respond(content = errorResponse("  Glare hides the middle digit.  "), headers = jsonHeaders) }
        val glucoseEngine = MockEngine {
            respond(content = glucoseErrorResponse("The meter shows LO; measure again."), headers = jsonHeaders)
        }

        val bp = createClient(bpEngine).parseBloodPressureImage(testApiKey, testImageBytes)
        val glucose = createClient(glucoseEngine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(bp)
        assertEquals("Glare hides the middle digit.", bp.detail)
        assertEquals("Unable to read blood pressure display", bp.message)
        assertIs<GlucoseParseResult.Error>(glucose)
        assertEquals("The meter shows LO; measure again.", glucose.detail)
    }

    @Test
    @DisplayName("the explanation is capped, and a blank one is dropped")
    fun detailIsCappedAndBlankDropped() = runTest {
        val long = "x".repeat(1000)
        val longResult = createClient(MockEngine { respond(content = errorResponse(long), headers = jsonHeaders) })
            .parseBloodPressureImage(testApiKey, testImageBytes)
        val blankResult = createClient(MockEngine { respond(content = errorResponse("   "), headers = jsonHeaders) })
            .parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Error>(longResult)
        assertEquals(300, longResult.detail!!.length)
        assertIs<BloodPressureParseResult.Error>(blankResult)
        assertEquals(null, blankResult.detail)
    }

    @Test
    @DisplayName("the explanation never reaches a log at WARN or above")
    fun detailIsNotLoggedAboveDebug() = runTest {
        val engine = MockEngine { respond(content = errorResponse("could be 108 or 188"), headers = jsonHeaders) }

        RecordingTree.recording { tree ->
            createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)
            assertTrue(tree.entries.any { it.priority == android.util.Log.DEBUG && "108" in it.message })
            assertTrue(tree.entries.none { it.priority >= android.util.Log.WARN && "108" in it.message })
        }
    }

    // --- Problem notes on readable answers ---

    @Test
    @DisplayName("BP: a problem note on a readable answer does not fail the scan")
    fun bpProblemNoteOnReadableAnswer() = runTest {
        val engine = MockEngine {
            respond(
                content = answerResponse(
                    """{"readable":true,"systolic":120,"diastolic":80,"pulse":null,"problem":"slight glare"}""",
                ),
                headers = jsonHeaders,
            )
        }
        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
    }

    // --- Glucose reading text and unit inference ---

    @Test
    @DisplayName("glucose: without a unit label, a decimal point means mmol/L and a whole number mg/dL")
    fun glucoseUnitInferredFromDecimalPoint() = runTest {
        val cases = listOf(
            "5.6" to (5.6 to GlucoseUnit.MMOL_L),
            "12.0" to (12.0 to GlucoseUnit.MMOL_L),
            "142" to (142.0 to GlucoseUnit.MG_DL),
            "56" to (56.0 to GlucoseUnit.MG_DL),
        )
        cases.forEach { (reading, expected) ->
            val engine = MockEngine { respond(content = glucoseSuccessResponse(reading, null), headers = jsonHeaders) }
            val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)
            assertIs<GlucoseParseResult.Success>(result, "reading '$reading'")
            assertEquals(expected.first, result.value)
            assertEquals(expected.second, result.detectedUnit)
        }
    }

    @Test
    @DisplayName("glucose: a decimal comma is read as a decimal point")
    fun glucoseDecimalComma() = runTest {
        val engine = MockEngine { respond(content = glucoseSuccessResponse("5,6", null), headers = jsonHeaders) }
        val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)

        assertIs<GlucoseParseResult.Success>(result)
        assertEquals(5.6, result.value)
        assertEquals(GlucoseUnit.MMOL_L, result.detectedUnit)
    }

    @Test
    @DisplayName("glucose: a reading that is not a number is rejected")
    fun glucoseNonNumericReadingRejected() = runTest {
        listOf("HI", "1O2", "12.3.4", "-5").forEach { reading ->
            val engine = MockEngine { respond(content = glucoseSuccessResponse(reading, "mg/dL"), headers = jsonHeaders) }
            val result = createClient(engine).parseGlucoseImage(testApiKey, testImageBytes)
            assertIs<GlucoseParseResult.Error>(result, "reading '$reading'")
        }
    }

    @Test
    @DisplayName("glucose: a readable answer with a null reading reports missing values")
    fun glucoseNullReadingIsMissing() = runTest {
        val engine = MockEngine {
            respond(
                content = answerResponse("""{"readable":true,"reading":null,"unit":"mg/dL","problem":null}"""),
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
    @DisplayName("glucose: schema asks for the reading as text and constrains the unit with an enum")
    fun glucoseSchemaShape() = runTest {
        val request = captureRequest(glucoseSuccessResponse(5.6, "mmol/L")) {
            it.parseGlucoseImage(testApiKey, testImageBytes)
        }
        val schema = request.body["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject
        val properties = schema["properties"]!!.jsonObject

        assertEquals(
            "string",
            properties["reading"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertTrue(properties["unit"].toString().contains("\"enum\":[\"mg/dL\",\"mmol/L\"]"))
        assertEquals(setOf("readable", "reading", "unit", "problem"), schema.requiredNames())
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    private fun JsonObject.requiredNames(): Set<String> =
        this["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

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
    @DisplayName("connect timeout is a non-retried NETWORK error logged below ERROR (HEALTH-HELPER-F)")
    fun connectTimeoutIsNetworkErrorNotReported() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw ConnectTimeoutException(
                "Connect timeout has expired [url=https://api.anthropic.com/v1/messages, connect_timeout=unknown ms]",
            )
        }

        val result = RecordingTree.recording { tree ->
            createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes).also {
                assertEquals(0, tree.errorCount, "a connect timeout must not reach Sentry as an error")
            }
        }

        assertIs<BloodPressureParseResult.Error>(result)
        assertEquals("Request timed out", result.message)
        assertEquals(ImageParseErrorKind.NETWORK, result.kind)
        assertEquals(1, calls)
    }

    @Test
    @DisplayName("TLS handshake failure is a NETWORK error logged below ERROR")
    fun tlsFailureIsNetworkErrorNotReported() = runTest {
        val engine = MockEngine { throw trustAnchorFailure() }

        val result = RecordingTree.recording { tree ->
            createClient(engine).parseGlucoseImage(testApiKey, testImageBytes).also {
                assertEquals(0, tree.errorCount, "a TLS failure must not reach Sentry as an error")
            }
        }

        assertIs<GlucoseParseResult.Error>(result)
        assertEquals(ImageParseErrorKind.NETWORK, result.kind)
    }

    private fun trustAnchorFailure() = SSLHandshakeException(
        "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
    ).apply { initCause(CertPathValidatorException("Trust anchor for certification path not found.")) }

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

    // --- heart rate ---

    @Test
    @DisplayName("pulse from the answer is returned as the heart rate")
    fun returnsHeartRate() = runTest {
        val engine = MockEngine { respond(content = successResponseWithHeartRate(120, 80, 72), headers = jsonHeaders) }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(72, result.heartRate)
    }

    @Test
    @DisplayName("null pulse yields a null heart rate")
    fun missingHeartRateIsNull() = runTest {
        val engine = MockEngine { respond(content = successResponse(120, 80), headers = jsonHeaders) }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(null, result.heartRate)
    }

    @Test
    @DisplayName("implausible pulse is dropped without failing the scan")
    fun implausibleHeartRateDropped() = runTest {
        val engine = MockEngine { respond(content = successResponseWithHeartRate(120, 80, 400), headers = jsonHeaders) }

        val result = createClient(engine).parseBloodPressureImage(testApiKey, testImageBytes)

        assertIs<BloodPressureParseResult.Success>(result)
        assertEquals(120, result.systolic)
        assertEquals(null, result.heartRate)
    }

    @Test
    @DisplayName("BP schema asks for a nullable pulse and pins every property")
    fun bpSchemaShape() = runTest {
        val request = captureRequest(successResponse(120, 80)) {
            it.parseBloodPressureImage(testApiKey, testImageBytes)
        }
        val schema = request.body["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject
        val pulseTypes = schema["properties"]!!.jsonObject["pulse"]!!.jsonObject["anyOf"]!!.jsonArray
            .map { it.jsonObject["type"]!!.jsonPrimitive.content }

        assertEquals(listOf("integer", "null"), pulseTypes)
        assertEquals(setOf("readable", "systolic", "diastolic", "pulse", "problem"), schema.requiredNames())
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.content.toBooleanStrict())
    }
}
