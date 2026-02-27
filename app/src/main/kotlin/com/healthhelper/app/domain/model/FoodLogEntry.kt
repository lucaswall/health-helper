package com.healthhelper.app.domain.model

data class FoodLogEntry(
    val id: Int,
    val foodName: String,
    val mealType: MealType,
    val time: String?,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val sodiumMg: Double,
    val saturatedFatG: Double?,
    val transFatG: Double?,
    val sugarsG: Double?,
    val caloriesFromFat: Double?,
)
