package com.healthhelper.app.data.repository

import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class HealthConnectNutritionRepositoryTest {

    private val testEntry = FoodLogEntry(
        id = 1,
        foodName = "Test Food",
        mealType = MealType.BREAKFAST,
        time = "08:00:00",
        calories = 300.0,
        proteinG = 10.0,
        carbsG = 40.0,
        fatG = 8.0,
        fiberG = 3.0,
        sodiumMg = 150.0,
        saturatedFatG = null,
        transFatG = null,
        sugarsG = null,
        caloriesFromFat = null,
    )

    @Test
    @DisplayName("writeNutritionRecords returns false when HealthConnectClient is null")
    fun nullClientReturnsFalse() = runTest {
        val repository = HealthConnectNutritionRepository(healthConnectClient = null)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }
}
