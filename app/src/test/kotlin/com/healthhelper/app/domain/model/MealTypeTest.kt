package com.healthhelper.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MealType.fromFoodScannerId")
class MealTypeTest {

    @Test
    @DisplayName("mealTypeId 1 maps to BREAKFAST")
    fun id1MapsToBreakfast() {
        assertEquals(MealType.BREAKFAST, MealType.fromFoodScannerId(1))
    }

    @Test
    @DisplayName("mealTypeId 3 maps to LUNCH")
    fun id3MapsToLunch() {
        assertEquals(MealType.LUNCH, MealType.fromFoodScannerId(3))
    }

    @Test
    @DisplayName("mealTypeId 5 maps to DINNER")
    fun id5MapsToDinner() {
        assertEquals(MealType.DINNER, MealType.fromFoodScannerId(5))
    }

    @Test
    @DisplayName("mealTypeId 2 maps to SNACK")
    fun id2MapsToSnack() {
        assertEquals(MealType.SNACK, MealType.fromFoodScannerId(2))
    }

    @Test
    @DisplayName("mealTypeId 4 maps to SNACK")
    fun id4MapsToSnack() {
        assertEquals(MealType.SNACK, MealType.fromFoodScannerId(4))
    }

    @Test
    @DisplayName("mealTypeId 7 maps to SNACK")
    fun id7MapsToSnack() {
        assertEquals(MealType.SNACK, MealType.fromFoodScannerId(7))
    }

    @Test
    @DisplayName("unknown mealTypeId returns UNKNOWN")
    fun unknownIdReturnsUnknown() {
        assertEquals(MealType.UNKNOWN, MealType.fromFoodScannerId(99))
    }
}
