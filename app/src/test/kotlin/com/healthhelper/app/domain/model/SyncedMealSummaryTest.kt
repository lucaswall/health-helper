package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SyncedMealSummaryTest {

    @Test
    @DisplayName("SyncedMealSummary holds foodName, mealType, and calories")
    fun holdsFoodNameMealTypeAndCalories() {
        val summary = SyncedMealSummary(
            foodName = "Oatmeal",
            mealType = MealType.BREAKFAST,
            calories = 350,
        )
        assertEquals("Oatmeal", summary.foodName)
        assertEquals(MealType.BREAKFAST, summary.mealType)
        assertEquals(350, summary.calories)
    }

    @Test
    @DisplayName("SyncedMealSummary requires non-negative calories")
    fun requiresNonNegativeCalories() {
        assertThrows<IllegalArgumentException> {
            SyncedMealSummary(
                foodName = "Test Food",
                mealType = MealType.LUNCH,
                calories = -1,
            )
        }
    }

    @Test
    @DisplayName("SyncedMealSummary requires non-blank foodName")
    fun requiresNonBlankFoodName() {
        assertThrows<IllegalArgumentException> {
            SyncedMealSummary(
                foodName = "   ",
                mealType = MealType.DINNER,
                calories = 200,
            )
        }
    }
}
