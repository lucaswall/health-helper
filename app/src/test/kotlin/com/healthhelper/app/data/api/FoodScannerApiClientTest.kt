package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.MealType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
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
}
