package com.healthhelper.app.domain.model

import java.time.Instant

data class SyncedMealSummary(
    val foodName: String,
    val mealType: MealType,
    val calories: Int,
    val timestamp: Instant = Instant.EPOCH,
) {
    init {
        require(calories >= 0) { "calories must be non-negative, was $calories" }
        require(foodName.isNotBlank()) { "foodName must not be blank" }
    }
}
