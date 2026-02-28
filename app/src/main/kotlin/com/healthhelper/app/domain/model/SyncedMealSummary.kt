package com.healthhelper.app.domain.model

data class SyncedMealSummary(
    val foodName: String,
    val mealType: MealType,
    val calories: Int,
) {
    init {
        require(calories >= 0) { "calories must be non-negative, was $calories" }
        require(foodName.isNotBlank()) { "foodName must not be blank" }
    }
}
