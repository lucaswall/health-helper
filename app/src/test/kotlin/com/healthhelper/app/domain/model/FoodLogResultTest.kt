package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FoodLogResultTest {

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
    @DisplayName("Data holds entries correctly")
    fun dataHoldsEntries() {
        val entries = listOf(testEntry, testEntry.copy(id = 2, foodName = "Other"))
        val result = FoodLogResult.Data(entries)

        assertEquals(2, result.entries.size)
        assertEquals("Test Food", result.entries[0].foodName)
        assertEquals("Other", result.entries[1].foodName)
    }

    @Test
    @DisplayName("NotModified is a singleton")
    fun notModifiedIsSingleton() {
        assertSame(FoodLogResult.NotModified, FoodLogResult.NotModified)
    }

    @Test
    @DisplayName("when expression covers both branches")
    fun whenExpressionCoversBothBranches() {
        val data: FoodLogResult = FoodLogResult.Data(listOf(testEntry))
        val notModified: FoodLogResult = FoodLogResult.NotModified

        val dataLabel = when (data) {
            is FoodLogResult.Data -> "data"
            is FoodLogResult.NotModified -> "not_modified"
        }
        val notModifiedLabel = when (notModified) {
            is FoodLogResult.Data -> "data"
            is FoodLogResult.NotModified -> "not_modified"
        }

        assertEquals("data", dataLabel)
        assertEquals("not_modified", notModifiedLabel)
    }
}
