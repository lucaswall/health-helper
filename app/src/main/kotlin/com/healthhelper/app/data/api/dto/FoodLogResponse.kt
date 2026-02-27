package com.healthhelper.app.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val success: Boolean,
    val data: T? = null,
    val timestamp: Long? = null,
    val error: ApiErrorDto? = null,
)

@Serializable
data class NutritionSummaryDto(
    val date: String,
    val meals: List<MealGroupDto>,
    val totals: MealEntryTotalsDto? = null,
)

@Serializable
data class MealGroupDto(
    val mealTypeId: Int,
    val entries: List<MealEntryDto>,
    val subtotal: MealEntryTotalsDto? = null,
)

@Serializable
data class MealEntryDto(
    val id: Int,
    val foodName: String,
    val time: String? = null,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val sodiumMg: Double,
    val saturatedFatG: Double? = null,
    val transFatG: Double? = null,
    val sugarsG: Double? = null,
    val caloriesFromFat: Double? = null,
)

@Serializable
data class MealEntryTotalsDto(
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
)
