package com.healthhelper.app.domain.model

import java.time.Instant
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
    @DisplayName("SyncedMealSummary holds timestamp field")
    fun holdsTimestampField() {
        val ts = Instant.parse("2026-03-01T12:30:00Z")
        val summary = SyncedMealSummary(
            foodName = "Oatmeal",
            mealType = MealType.BREAKFAST,
            calories = 350,
            timestamp = ts,
        )
        assertEquals(ts, summary.timestamp)
    }

    @Test
    @DisplayName("SyncedMealSummary defaults timestamp to Instant.EPOCH")
    fun defaultsTimestampToEpoch() {
        val summary = SyncedMealSummary(
            foodName = "Oatmeal",
            mealType = MealType.BREAKFAST,
            calories = 350,
        )
        assertEquals(Instant.EPOCH, summary.timestamp)
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
