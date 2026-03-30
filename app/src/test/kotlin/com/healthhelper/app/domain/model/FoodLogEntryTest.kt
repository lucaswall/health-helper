package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class FoodLogEntryTest {

    private fun validEntry() = FoodLogEntry(
        id = 1,
        foodName = "Apple",
        mealType = MealType.BREAKFAST,
        time = "08:00:00",
        calories = 100.0,
        proteinG = 1.0,
        carbsG = 25.0,
        fatG = 0.5,
        fiberG = 3.0,
        sodiumMg = 10.0,
        saturatedFatG = null,
        transFatG = null,
        sugarsG = null,
        caloriesFromFat = null,
    )

    @Test
    @DisplayName("valid non-negative values succeed")
    fun validValuesSucceed() {
        val entry = validEntry()
        assertEquals(100.0, entry.calories)
    }

    @Test
    @DisplayName("zero values are valid")
    fun zeroValuesAreValid() {
        val entry = validEntry().copy(
            calories = 0.0,
            proteinG = 0.0,
            carbsG = 0.0,
            fatG = 0.0,
            fiberG = 0.0,
            sodiumMg = 0.0,
        )
        assertEquals(0.0, entry.calories)
    }

    @Test
    @DisplayName("negative calories throws IllegalArgumentException")
    fun negativeCaloriesThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(calories = -1.0)
        }
    }

    @Test
    @DisplayName("negative proteinG throws IllegalArgumentException")
    fun negativeProteinGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(proteinG = -0.1)
        }
    }

    @Test
    @DisplayName("negative carbsG throws IllegalArgumentException")
    fun negativeCarbsGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(carbsG = -5.0)
        }
    }

    @Test
    @DisplayName("negative fatG throws IllegalArgumentException")
    fun negativeFatGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(fatG = -2.0)
        }
    }

    @Test
    @DisplayName("negative fiberG throws IllegalArgumentException")
    fun negativeFiberGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(fiberG = -0.5)
        }
    }

    @Test
    @DisplayName("negative sodiumMg throws IllegalArgumentException")
    fun negativeSodiumMgThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(sodiumMg = -10.0)
        }
    }

    @Test
    @DisplayName("negative saturatedFatG throws IllegalArgumentException when non-null")
    fun negativeSaturatedFatGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(saturatedFatG = -1.0)
        }
    }

    @Test
    @DisplayName("null saturatedFatG is valid")
    fun nullSaturatedFatGIsValid() {
        val entry = validEntry().copy(saturatedFatG = null)
        assertEquals(null, entry.saturatedFatG)
    }

    @Test
    @DisplayName("negative transFatG throws IllegalArgumentException when non-null")
    fun negativeTransFatGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(transFatG = -0.5)
        }
    }

    @Test
    @DisplayName("null transFatG is valid")
    fun nullTransFatGIsValid() {
        val entry = validEntry().copy(transFatG = null)
        assertEquals(null, entry.transFatG)
    }

    @Test
    @DisplayName("negative sugarsG throws IllegalArgumentException when non-null")
    fun negativeSugarsGThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(sugarsG = -3.0)
        }
    }

    @Test
    @DisplayName("null sugarsG is valid")
    fun nullSugarsGIsValid() {
        val entry = validEntry().copy(sugarsG = null)
        assertEquals(null, entry.sugarsG)
    }

    @Test
    @DisplayName("negative caloriesFromFat throws IllegalArgumentException when non-null")
    fun negativeCaloriesFromFatThrows() {
        assertThrows<IllegalArgumentException> {
            validEntry().copy(caloriesFromFat = -10.0)
        }
    }

    @Test
    @DisplayName("null caloriesFromFat is valid")
    fun nullCaloriesFromFatIsValid() {
        val entry = validEntry().copy(caloriesFromFat = null)
        assertEquals(null, entry.caloriesFromFat)
    }

    @Test
    @DisplayName("zoneOffset +05:30 is accepted")
    fun zoneOffsetPositiveIsAccepted() {
        val entry = validEntry().copy(zoneOffset = "+05:30")
        assertEquals("+05:30", entry.zoneOffset)
    }

    @Test
    @DisplayName("zoneOffset null is accepted")
    fun zoneOffsetNullIsAccepted() {
        val entry = validEntry().copy(zoneOffset = null)
        assertEquals(null, entry.zoneOffset)
    }
}
