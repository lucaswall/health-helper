package com.healthhelper.app.data.api

import com.healthhelper.app.data.api.dto.ApiEnvelope
import com.healthhelper.app.data.api.dto.NutritionSummaryDto
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
    ): Result<List<FoodLogEntry>> {
        if (baseUrl.isBlank() || !baseUrl.lowercase().startsWith("https://")) {
            return Result.failure(Exception("HTTPS required for API connections"))
        }
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v1/food-log") {
                parameter("date", date)
                bearerAuth(apiKey)
            }

            if (!response.status.isSuccess()) {
                val status = response.status.value
                val message = when (status) {
                    401 -> "Authentication failed"
                    429 -> "Rate limited"
                    else -> "HTTP error $status"
                }
                if (status == 401) {
                    Timber.w("getFoodLog(%s) HTTP error: %d", date, status)
                } else {
                    Timber.e("getFoodLog(%s) HTTP error: %d", date, status)
                }
                return Result.failure(Exception(message))
            }

            val envelope: ApiEnvelope<NutritionSummaryDto> = response.body()

            if (!envelope.success) {
                val serverMsg = envelope.error?.message
                if (serverMsg != null) {
                    Timber.d("getFoodLog(%s) server error: %s", date, serverMsg)
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
                Result.success(entries)
            }
        } catch (e: SerializationException) {
            Timber.e(e, "getFoodLog(%s) parse error", date)
            Result.failure(Exception("Failed to parse response"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "getFoodLog(%s) error", date)
            Result.failure(e)
        }
    }
}
