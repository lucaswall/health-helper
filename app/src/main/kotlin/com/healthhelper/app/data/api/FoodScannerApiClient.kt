package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.ApiEnvelope
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.data.api.dto.NutritionSummaryDto
import com.healthhelper.app.data.api.dto.UpsertResponse
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.SerializationException
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
        if (baseUrl.isBlank() || !baseUrl.lowercase().startsWith("https://")) {
            return Result.failure(Exception("API URL not configured. Set a valid HTTPS URL in Settings."))
        }
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v1/food-log") {
                parameter("date", date)
                bearerAuth(apiKey)
                if (etag != null) {
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            }

            if (response.status.value == 304) {
                return Result.success(FoodLogApiResponse(emptyList(), etag = null, notModified = true))
            }

            if (!response.status.isSuccess()) {
                val status = response.status.value
                val message = when (status) {
                    401 -> "Authentication failed"
                    429 -> "Rate limited"
                    in 500..599 -> "Server unavailable"
                    else -> "HTTP error $status"
                }
                when (status) {
                    401, 429, in 500..599 -> Timber.w("getFoodLog(%s) HTTP error: %d", date, status)
                    else -> Timber.e("getFoodLog(%s) HTTP error: %d", date, status)
                }
                return Result.failure(Exception(message))
            }

            val envelope: ApiEnvelope<NutritionSummaryDto> = response.body()

            if (!envelope.success) {
                val serverMsg = envelope.error?.message
                if (serverMsg != null) {
                    Timber.w("getFoodLog(%s) server error: %s", date, serverMsg)
                }
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
        } catch (e: SerializationException) {
            Timber.e(e, "getFoodLog(%s) parse error", date)
            Result.failure(Exception("Failed to parse response"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException) {
                Timber.w(e, "getFoodLog(%s) network error", date)
            } else {
                Timber.e(e, "getFoodLog(%s) error", date)
            }
            Result.failure(e)
        }
    }

    suspend fun postGlucoseReadings(
        baseUrl: String,
        apiKey: String,
        request: GlucoseReadingRequest,
    ): Result<Int> {
        if (baseUrl.isBlank() || !baseUrl.lowercase().startsWith("https://")) {
            return Result.failure(Exception("API URL not configured. Set a valid HTTPS URL in Settings."))
        }
        return try {
            val startMs = System.currentTimeMillis()
            val response = httpClient.post("${baseUrl.trimEnd('/')}/api/v1/glucose-readings") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!response.status.isSuccess()) {
                val status = response.status.value
                val exception: FoodScannerApiException = when (status) {
                    401 -> AuthenticationException(status)
                    429 -> RateLimitException(status)
                    in 500..599 -> ServerException(status)
                    else -> FoodScannerApiException("HTTP error $status", status)
                }
                when (status) {
                    401, 429, in 500..599 -> Timber.w("postGlucoseReadings HTTP error: %d", status)
                    else -> Timber.e("postGlucoseReadings HTTP error: %d", status)
                }
                return Result.failure(exception)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val envelope: ApiEnvelope<UpsertResponse> = response.body()
            if (!envelope.success) {
                val serverMsg = envelope.error?.message
                if (serverMsg != null) {
                    Timber.w("postGlucoseReadings server error: %s", serverMsg)
                }
                Result.failure(Exception("Server returned an error"))
            } else {
                val upserted = envelope.data?.upserted ?: 0
                Timber.d("postGlucoseReadings: upserted=%d in %dms", upserted, elapsed)
                Result.success(upserted)
            }
        } catch (e: SerializationException) {
            Timber.e(e, "postGlucoseReadings parse error")
            Result.failure(Exception("Failed to parse response"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException) {
                Timber.w(e, "postGlucoseReadings network error")
            } else {
                Timber.e(e, "postGlucoseReadings error")
            }
            Result.failure(e)
        }
    }

    suspend fun postBloodPressureReadings(
        baseUrl: String,
        apiKey: String,
        request: BloodPressureReadingRequest,
    ): Result<Int> {
        if (baseUrl.isBlank() || !baseUrl.lowercase().startsWith("https://")) {
            return Result.failure(Exception("API URL not configured. Set a valid HTTPS URL in Settings."))
        }
        return try {
            val startMs = System.currentTimeMillis()
            val response = httpClient.post("${baseUrl.trimEnd('/')}/api/v1/blood-pressure-readings") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!response.status.isSuccess()) {
                val status = response.status.value
                val exception: FoodScannerApiException = when (status) {
                    401 -> AuthenticationException(status)
                    429 -> RateLimitException(status)
                    in 500..599 -> ServerException(status)
                    else -> FoodScannerApiException("HTTP error $status", status)
                }
                when (status) {
                    401, 429, in 500..599 -> Timber.w("postBloodPressureReadings HTTP error: %d", status)
                    else -> Timber.e("postBloodPressureReadings HTTP error: %d", status)
                }
                return Result.failure(exception)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val envelope: ApiEnvelope<UpsertResponse> = response.body()
            if (!envelope.success) {
                val serverMsg = envelope.error?.message
                if (serverMsg != null) {
                    Timber.w("postBloodPressureReadings server error: %s", serverMsg)
                }
                Result.failure(Exception("Server returned an error"))
            } else {
                val upserted = envelope.data?.upserted ?: 0
                Timber.d("postBloodPressureReadings: upserted=%d in %dms", upserted, elapsed)
                Result.success(upserted)
            }
        } catch (e: SerializationException) {
            Timber.e(e, "postBloodPressureReadings parse error")
            Result.failure(Exception("Failed to parse response"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException) {
                Timber.w(e, "postBloodPressureReadings network error")
            } else {
                Timber.e(e, "postBloodPressureReadings error")
            }
            Result.failure(e)
        }
    }
}
