package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.BloodPressureReadingDto
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingDto
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.domain.model.MealType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoodScannerApiClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun createClient(mockEngine: MockEngine): FoodScannerApiClient {
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FoodScannerApiClient(httpClient)
    }

    private val successResponse = """
        {
            "success": true,
            "data": {
                "date": "2026-02-27",
                "meals": [
                    {
                        "mealTypeId": 1,
                        "entries": [
                            {
                                "id": 101,
                                "foodName": "Oatmeal",
                                "time": "08:00:00",
                                "calories": 300.0,
                                "proteinG": 10.0,
                                "carbsG": 50.0,
                                "fatG": 5.0,
                                "fiberG": 4.0,
                                "sodiumMg": 100.0,
                                "saturatedFatG": 1.0,
                                "transFatG": 0.0,
                                "sugarsG": 12.0,
                                "caloriesFromFat": 45.0
                            }
                        ]
                    },
                    {
                        "mealTypeId": 3,
                        "entries": [
                            {
                                "id": 102,
                                "foodName": "Salad",
                                "time": "12:30:00",
                                "calories": 250.0,
                                "proteinG": 15.0,
                                "carbsG": 20.0,
                                "fatG": 10.0,
                                "fiberG": 6.0,
                                "sodiumMg": 200.0
                            }
                        ]
                    }
                ]
            },
            "timestamp": 1709038200
        }
    """.trimIndent()

    @Test
    @DisplayName("successful response deserializes into FoodLogEntry list")
    fun successfulDeserialization() = runTest {
        val engine = MockEngine { respond(content = successResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isSuccess)
        val apiResponse = result.getOrThrow()
        assertFalse(apiResponse.notModified)
        assertEquals(2, apiResponse.entries.size)
        assertEquals("Oatmeal", apiResponse.entries[0].foodName)
        assertEquals(300.0, apiResponse.entries[0].calories)
        assertEquals(MealType.BREAKFAST, apiResponse.entries[0].mealType)
        assertEquals("Salad", apiResponse.entries[1].foodName)
        assertEquals(MealType.LUNCH, apiResponse.entries[1].mealType)
    }

    @Test
    @DisplayName("Bearer token is sent in Authorization header")
    fun bearerTokenSent() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(content = successResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.getFoodLog("https://food.example.com", "fsk_mytoken", "2026-02-27")

        assertEquals("Bearer fsk_mytoken", capturedAuth)
    }

    @Test
    @DisplayName("correct URL construction with date parameter")
    fun correctUrlConstruction() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(content = successResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.getFoodLog("https://food.example.com", "fsk_test", "2026-01-15")

        val url = assertNotNull(capturedUrl)
        assertTrue(url.contains("food.example.com/api/v1/food-log"))
        assertTrue(url.contains("date=2026-01-15"))
    }

    @Test
    @DisplayName("error response with success=false returns failure")
    fun errorResponseSuccessFalse() = runTest {
        val errorJson = """{"success":false,"error":{"code":"INVALID","message":"Bad request"},"timestamp":123}"""
        val engine = MockEngine { respond(content = errorJson, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertEquals("Server returned an error", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("401 response returns auth error")
    fun authError401() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_bad", "2026-02-27")

        assertTrue(result.isFailure)
        assertEquals("Authentication failed", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("429 response returns rate limit error")
    fun rateLimitError429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertEquals("Rate limited", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("network failure returns error")
    fun networkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("null optional fields handled gracefully")
    fun nullOptionalFields() = runTest {
        val entries = """
            {
                "success": true,
                "data": {
                    "date": "2026-02-27",
                    "meals": [{
                        "mealTypeId": 5,
                        "entries": [{
                            "id": 200,
                            "foodName": "Rice",
                            "calories": 200.0,
                            "proteinG": 4.0,
                            "carbsG": 45.0,
                            "fatG": 0.5,
                            "fiberG": 0.6,
                            "sodiumMg": 1.0
                        }]
                    }]
                },
                "timestamp": 123
            }
        """.trimIndent()
        val engine = MockEngine { respond(content = entries, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isSuccess)
        val entry = result.getOrThrow().entries.first()
        assertEquals("Rice", entry.foodName)
        assertEquals(MealType.DINNER, entry.mealType)
        assertEquals(null, entry.saturatedFatG)
        assertEquals(null, entry.transFatG)
        assertEquals(null, entry.sugarsG)
        assertEquals(null, entry.caloriesFromFat)
    }

    @Test
    @DisplayName("meal type IDs correctly mapped")
    fun mealTypeMappings() = runTest {
        val multiMeal = """
            {
                "success": true,
                "data": {
                    "date": "2026-02-27",
                    "meals": [
                        {"mealTypeId": 1, "entries": [{"id":1,"foodName":"A","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]},
                        {"mealTypeId": 2, "entries": [{"id":2,"foodName":"B","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]},
                        {"mealTypeId": 3, "entries": [{"id":3,"foodName":"C","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]},
                        {"mealTypeId": 4, "entries": [{"id":4,"foodName":"D","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]},
                        {"mealTypeId": 5, "entries": [{"id":5,"foodName":"E","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]},
                        {"mealTypeId": 7, "entries": [{"id":6,"foodName":"F","calories":1,"proteinG":0,"carbsG":0,"fatG":0,"fiberG":0,"sodiumMg":0}]}
                    ]
                },
                "timestamp": 123
            }
        """.trimIndent()
        val engine = MockEngine { respond(content = multiMeal, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")
        val entries = result.getOrThrow().entries

        assertEquals(MealType.BREAKFAST, entries[0].mealType)
        assertEquals(MealType.SNACK, entries[1].mealType)
        assertEquals(MealType.LUNCH, entries[2].mealType)
        assertEquals(MealType.SNACK, entries[3].mealType)
        assertEquals(MealType.DINNER, entries[4].mealType)
        assertEquals(MealType.SNACK, entries[5].mealType)
    }

    @Test
    @DisplayName("HTTP base URL is rejected with failure result")
    fun httpBaseUrlRejected() = runTest {
        val engine = MockEngine { respond(content = successResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("http://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    @DisplayName("HTTP base URL is rejected case-insensitively (HTTP:// uppercase)")
    fun httpBaseUrlRejectedCaseInsensitive() = runTest {
        val engine = MockEngine { respond(content = successResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("HTTP://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    @DisplayName("blank base URL is rejected")
    fun blankBaseUrlRejected() = runTest {
        val engine = MockEngine { respond(content = successResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("CancellationException propagates from getFoodLog")
    fun cancellationExceptionPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("Cancelled") }
        val client = createClient(engine)

        assertFailsWith<CancellationException> {
            client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")
        }
    }

    @Test
    @DisplayName("base URL with trailing slash produces correct URL without double slash")
    fun trailingSlashHandled() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(content = successResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.getFoodLog("https://food.example.com/", "fsk_test", "2026-02-27")

        val url = assertNotNull(capturedUrl)
        assertTrue(url.contains("/api/v1/food-log"))
        assertFalse(url.contains("//api"))
    }

    // --- ETag support tests ---

    @Test
    @DisplayName("If-None-Match header is sent when etag parameter is provided")
    fun ifNoneMatchHeaderSentWhenEtagProvided() = runTest {
        var capturedHeaders: Headers? = null
        val engine = MockEngine { request ->
            capturedHeaders = request.headers
            respond(content = successResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27", etag = "\"abc123\"")

        assertEquals("\"abc123\"", capturedHeaders?.get(HttpHeaders.IfNoneMatch))
    }

    @Test
    @DisplayName("If-None-Match header is NOT sent when etag is null")
    fun ifNoneMatchHeaderNotSentWhenEtagNull() = runTest {
        var capturedHeaders: Headers? = null
        val engine = MockEngine { request ->
            capturedHeaders = request.headers
            respond(content = successResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertNull(capturedHeaders?.get(HttpHeaders.IfNoneMatch))
    }

    @Test
    @DisplayName("304 response returns FoodLogApiResponse with notModified=true and empty entries")
    fun notModifiedResponse304() = runTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode(304, "Not Modified"))
        }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27", etag = "\"abc123\"")

        assertTrue(result.isSuccess)
        val apiResponse = result.getOrThrow()
        assertTrue(apiResponse.notModified)
        assertTrue(apiResponse.entries.isEmpty())
    }

    @Test
    @DisplayName("200 response with ETag header returns it in FoodLogApiResponse.etag")
    fun etagHeaderExtractedFrom200Response() = runTest {
        val responseHeaders = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.ETag to listOf("\"def456\""),
        )
        val engine = MockEngine { respond(content = successResponse, headers = responseHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isSuccess)
        assertEquals("\"def456\"", result.getOrThrow().etag)
    }

    @Test
    @DisplayName("200 response without ETag header returns etag=null")
    fun noEtagHeaderReturnsNull() = runTest {
        val engine = MockEngine { respond(content = successResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().etag)
    }

    // --- Network exception tests ---

    @Test
    @DisplayName("UnresolvedAddressException returns failure result")
    fun unresolvedAddressException() = runTest {
        val engine = MockEngine { throw java.nio.channels.UnresolvedAddressException() }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.nio.channels.UnresolvedAddressException)
    }

    // --- 5xx log level tests ---

    @Test
    @DisplayName("503 response returns failure with 'Server unavailable' message")
    fun serverUnavailable503() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertEquals("Server unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("500 response returns failure with 'Server unavailable' message")
    fun serverError500() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = createClient(engine)

        val result = client.getFoodLog("https://food.example.com", "fsk_test", "2026-02-27")

        assertTrue(result.isFailure)
        assertEquals("Server unavailable", result.exceptionOrNull()?.message)
    }

    // --- postGlucoseReadings tests ---

    private val glucoseUpsertSuccessResponse = """{"success":true,"data":{"upserted":3},"timestamp":123}"""

    private val sampleGlucoseRequest = GlucoseReadingRequest(
        readings = listOf(
            GlucoseReadingDto(
                measuredAt = "2026-03-29T10:00:00Z",
                valueMgDl = 120,
                zoneOffset = "+05:30",
                relationToMeal = "before_meal",
                mealType = "breakfast",
                specimenSource = "capillary_blood",
            )
        )
    )

    @Test
    @DisplayName("postGlucoseReadings successful POST returns upserted count")
    fun postGlucoseReadingsSuccess() = runTest {
        val engine = MockEngine { respond(content = glucoseUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow())
    }

    @Test
    @DisplayName("postGlucoseReadings sends Bearer token in Authorization header")
    fun postGlucoseReadingsBearerToken() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(content = glucoseUpsertSuccessResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.postGlucoseReadings("https://food.example.com", "fsk_mytoken", sampleGlucoseRequest)

        assertEquals("Bearer fsk_mytoken", capturedAuth)
    }

    @Test
    @DisplayName("postGlucoseReadings request body contains correct JSON fields")
    fun postGlucoseReadingsRequestBodyFields() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()
                ?.decodeToString()
            respond(content = glucoseUpsertSuccessResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)

        val body = assertNotNull(capturedBody)
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<GlucoseReadingRequest>(body)
        assertEquals(1, parsed.readings.size)
        val dto = parsed.readings[0]
        assertEquals(120, dto.valueMgDl)
        assertEquals("2026-03-29T10:00:00Z", dto.measuredAt)
        assertEquals("+05:30", dto.zoneOffset)
        assertEquals("before_meal", dto.relationToMeal)
        assertEquals("breakfast", dto.mealType)
        assertEquals("capillary_blood", dto.specimenSource)
    }

    @Test
    @DisplayName("postGlucoseReadings 401 returns auth error")
    fun postGlucoseReadings401() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("https://food.example.com", "fsk_bad", sampleGlucoseRequest)

        assertTrue(result.isFailure)
        assertEquals("Authentication failed", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postGlucoseReadings 429 returns rate limit error")
    fun postGlucoseReadings429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isFailure)
        assertEquals("Rate limited", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postGlucoseReadings 5xx returns server unavailable")
    fun postGlucoseReadings5xx() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isFailure)
        assertEquals("Server unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postGlucoseReadings network failure returns failure result")
    fun postGlucoseReadingsNetworkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("postGlucoseReadings CancellationException propagates")
    fun postGlucoseReadingsCancellationPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("Cancelled") }
        val client = createClient(engine)

        assertFailsWith<CancellationException> {
            client.postGlucoseReadings("https://food.example.com", "fsk_test", sampleGlucoseRequest)
        }
    }

    @Test
    @DisplayName("postGlucoseReadings HTTP URL is rejected")
    fun postGlucoseReadingsHttpUrlRejected() = runTest {
        val engine = MockEngine { respond(content = glucoseUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("http://food.example.com", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    @DisplayName("postGlucoseReadings blank base URL is rejected")
    fun postGlucoseReadingsBlankBaseUrl() = runTest {
        val engine = MockEngine { respond(content = glucoseUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postGlucoseReadings("", "fsk_test", sampleGlucoseRequest)

        assertTrue(result.isFailure)
    }

    // --- postBloodPressureReadings tests ---

    private val bpUpsertSuccessResponse = """{"success":true,"data":{"upserted":2},"timestamp":123}"""

    private val sampleBpRequest = BloodPressureReadingRequest(
        readings = listOf(
            BloodPressureReadingDto(
                measuredAt = "2026-03-29T10:00:00Z",
                systolic = 120,
                diastolic = 80,
                zoneOffset = "+05:30",
                bodyPosition = "sitting_down",
                measurementLocation = "left_upper_arm",
            )
        )
    )

    @Test
    @DisplayName("postBloodPressureReadings successful POST returns upserted count")
    fun postBloodPressureReadingsSuccess() = runTest {
        val engine = MockEngine { respond(content = bpUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())
    }

    @Test
    @DisplayName("postBloodPressureReadings sends Bearer token in Authorization header")
    fun postBloodPressureReadingsBearerToken() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(content = bpUpsertSuccessResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.postBloodPressureReadings("https://food.example.com", "fsk_mytoken", sampleBpRequest)

        assertEquals("Bearer fsk_mytoken", capturedAuth)
    }

    @Test
    @DisplayName("postBloodPressureReadings request body contains correct JSON fields")
    fun postBloodPressureReadingsRequestBodyFields() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()
                ?.decodeToString()
            respond(content = bpUpsertSuccessResponse, headers = jsonHeaders)
        }
        val client = createClient(engine)

        client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)

        val body = assertNotNull(capturedBody)
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<BloodPressureReadingRequest>(body)
        assertEquals(1, parsed.readings.size)
        val dto = parsed.readings[0]
        assertEquals(120, dto.systolic)
        assertEquals(80, dto.diastolic)
        assertEquals("2026-03-29T10:00:00Z", dto.measuredAt)
        assertEquals("+05:30", dto.zoneOffset)
        assertEquals("sitting_down", dto.bodyPosition)
        assertEquals("left_upper_arm", dto.measurementLocation)
    }

    @Test
    @DisplayName("postBloodPressureReadings 401 returns auth error")
    fun postBloodPressureReadings401() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("https://food.example.com", "fsk_bad", sampleBpRequest)

        assertTrue(result.isFailure)
        assertEquals("Authentication failed", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postBloodPressureReadings 429 returns rate limit error")
    fun postBloodPressureReadings429() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)

        assertTrue(result.isFailure)
        assertEquals("Rate limited", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postBloodPressureReadings 5xx returns server unavailable")
    fun postBloodPressureReadings5xx() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)

        assertTrue(result.isFailure)
        assertEquals("Server unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("postBloodPressureReadings network failure returns failure result")
    fun postBloodPressureReadingsNetworkFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)

        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("postBloodPressureReadings CancellationException propagates")
    fun postBloodPressureReadingsCancellationPropagates() = runTest {
        val engine = MockEngine { throw CancellationException("Cancelled") }
        val client = createClient(engine)

        assertFailsWith<CancellationException> {
            client.postBloodPressureReadings("https://food.example.com", "fsk_test", sampleBpRequest)
        }
    }

    @Test
    @DisplayName("postBloodPressureReadings HTTP URL is rejected")
    fun postBloodPressureReadingsHttpUrlRejected() = runTest {
        val engine = MockEngine { respond(content = bpUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("http://food.example.com", "fsk_test", sampleBpRequest)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    @DisplayName("postBloodPressureReadings blank base URL is rejected")
    fun postBloodPressureReadingsBlankBaseUrl() = runTest {
        val engine = MockEngine { respond(content = bpUpsertSuccessResponse, headers = jsonHeaders) }
        val client = createClient(engine)

        val result = client.postBloodPressureReadings("", "fsk_test", sampleBpRequest)

        assertTrue(result.isFailure)
    }
}
