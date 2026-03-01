package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationToMealTest {

    @Test
    @DisplayName("RelationToMeal has exactly 5 values")
    fun hasExactlyFiveValues() {
        assertEquals(5, RelationToMeal.entries.size)
    }

    @Test
    @DisplayName("RelationToMeal has GENERAL value")
    fun hasGeneral() {
        assertTrue(RelationToMeal.entries.any { it == RelationToMeal.GENERAL })
    }

    @Test
    @DisplayName("RelationToMeal has FASTING value")
    fun hasFasting() {
        assertTrue(RelationToMeal.entries.any { it == RelationToMeal.FASTING })
    }

    @Test
    @DisplayName("RelationToMeal has BEFORE_MEAL value")
    fun hasBeforeMeal() {
        assertTrue(RelationToMeal.entries.any { it == RelationToMeal.BEFORE_MEAL })
    }

    @Test
    @DisplayName("RelationToMeal has AFTER_MEAL value")
    fun hasAfterMeal() {
        assertTrue(RelationToMeal.entries.any { it == RelationToMeal.AFTER_MEAL })
    }

    @Test
    @DisplayName("RelationToMeal has UNKNOWN value")
    fun hasUnknown() {
        assertTrue(RelationToMeal.entries.any { it == RelationToMeal.UNKNOWN })
    }
}
