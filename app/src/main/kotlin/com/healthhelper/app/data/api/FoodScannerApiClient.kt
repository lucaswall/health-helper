package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.ApiEnvelope
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.data.api.dto.HydrationReadingRequest
import com.healthhelper.app.data.api.dto.WaistReadingRequest
import com.healthhelper.app.data.api.dto.NutritionSummaryDto
import com.healthhelper.app.data.api.dto.UpsertResponse
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber
import javax.inject.Inject

class FoodScannerApiClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun getFoodLog(
        baseUrl: String,
        apiKey: String,
        date: String,
        etag: String? = null,
    ): Result<FoodLogApiResponse> {
        val root = ApiErrors.normalizeHttpsBaseUrl(baseUrl) ?: return invalidBaseUrl()
        val tag = "getFoodLog($date)"
        return try {
            val response = httpClient.get("$root/api/v1/food-log") {
                parameter("date", date)
                bearerAuth(apiKey.trim())
                if (etag != null) {
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            }

            if (response.status.value == 304) {
                return Result.success(FoodLogApiResponse(emptyList(), etag = null, notModified = true))
            }

            if (!response.status.isSuccess()) {
                return Result.failure(mapHttpError(response.status.value, tag))
            }

            val envelope: ApiEnvelope<NutritionSummaryDto> = response.body()

            if (!envelope.success) {
                logServerError(envelope, tag)
                Result.failure(Exception("Server returned an error"))
            } else {
                val entries = envelope.data?.meals?.flatMap { mealGroup ->
                    mealGroup.entries.map { entry ->
                        FoodLogEntry(
                            id = entry.id,
                            foodName = entry.foodName,
                            mealType = MealType.fromFoodScannerId(mealGroup.mealTypeId),
                            time = entry.time,
                            zoneOffset = entry.zoneOffset,
                            calories = entry.calories,
                            proteinG = entry.proteinG,
                            carbsG = entry.carbsG,
                            fatG = entry.fatG,
                            fiberG = entry.fiberG,
                            sodiumMg = entry.sodiumMg,
                            saturatedFatG = entry.saturatedFatG,
                            transFatG = entry.transFatG,
                            sugarsG = entry.sugarsG,
                            caloriesFromFat = entry.caloriesFromFat,
                        )
                    }
                } ?: emptyList()
                val responseEtag = response.headers[HttpHeaders.ETag]
                Result.success(FoodLogApiResponse(entries, etag = responseEtag, notModified = false))
            }
        } catch (e: Exception) {
            handleException(e, tag)
        }
    }

    suspend fun postGlucoseReadings(
        baseUrl: String,
        apiKey: String,
        request: GlucoseReadingRequest,
    ): Result<Int> = postUpsert(
        baseUrl = baseUrl,
        apiKey = apiKey,
        path = "glucose-readings",
        tag = "postGlucoseReadings",
    ) { setBody(request) }

    suspend fun postBloodPressureReadings(
        baseUrl: String,
        apiKey: String,
        request: BloodPressureReadingRequest,
    ): Result<Int> = postUpsert(
        baseUrl = baseUrl,
        apiKey = apiKey,
        path = "blood-pressure-readings",
        tag = "postBloodPressureReadings",
    ) { setBody(request) }

    suspend fun postHydrationReadings(
        baseUrl: String,
        apiKey: String,
        request: HydrationReadingRequest,
    ): Result<Int> = postUpsert(
        baseUrl = baseUrl,
        apiKey = apiKey,
        path = "hydration-readings",
        tag = "postHydrationReadings",
    ) { setBody(request) }

    suspend fun postWaistReadings(
        baseUrl: String,
        apiKey: String,
        request: WaistReadingRequest,
    ): Result<Int> = postUpsert(
        baseUrl = baseUrl,
        apiKey = apiKey,
        path = "waist-readings",
        tag = "postWaistReadings",
    ) { setBody(request) }

    private suspend fun postUpsert(
        baseUrl: String,
        apiKey: String,
        path: String,
        tag: String,
        body: HttpRequestBuilder.() -> Unit,
    ): Result<Int> {
        val root = ApiErrors.normalizeHttpsBaseUrl(baseUrl) ?: return invalidBaseUrl()
        return try {
            val startMs = System.currentTimeMillis()
            val response = httpClient.post("$root/api/v1/$path") {
                bearerAuth(apiKey.trim())
                contentType(ContentType.Application.Json)
                body()
            }
            if (!response.status.isSuccess()) {
                return Result.failure(mapHttpError(response.status.value, tag))
            }
            val elapsed = System.currentTimeMillis() - startMs
            val envelope: ApiEnvelope<UpsertResponse> = response.body()
            if (!envelope.success) {
                logServerError(envelope, tag)
                Result.failure(Exception("Server returned an error"))
            } else {
                val upserted = envelope.data?.upserted ?: 0
                Timber.d("%s: upserted=%d in %dms", tag, upserted, elapsed)
                Result.success(upserted)
            }
        } catch (e: Exception) {
            handleException(e, tag)
        }
    }

    private fun <T> invalidBaseUrl(): Result<T> =
        Result.failure(Exception("API URL not configured. Set a valid HTTPS URL in Settings."))

    private fun mapHttpError(status: Int, tag: String): FoodScannerApiException {
        when (status) {
            401, 429, in 500..599 -> Timber.w("%s HTTP error: %d", tag, status)
            else -> Timber.e("%s HTTP error: %d", tag, status)
        }
        return when (status) {
            401 -> AuthenticationException(status)
            429 -> RateLimitException(status)
            in 500..599 -> ServerException(status)
            else -> FoodScannerApiException("HTTP error $status", status)
        }
    }

    private fun logServerError(envelope: ApiEnvelope<*>, tag: String) {
        val serverMsg = envelope.error?.message
        if (serverMsg != null) {
            Timber.w("%s server error: %s", tag, serverMsg)
        }
    }

    private fun <T> handleException(e: Exception, tag: String): Result<T> {
        if (e is CancellationException) throw e
        val mapped = ApiErrors.toUserFacing(e)
        when (mapped) {
            is ResponseParseException -> Timber.e(e, "%s parse error", tag)
            is NetworkException -> Timber.w(e, "%s network error", tag)
            else -> Timber.e(e, "%s error", tag)
        }
        return Result.failure(mapped)
    }
}
