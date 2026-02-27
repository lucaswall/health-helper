package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.MealType as HcMealType
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NutritionRecordMapperTest {

    private fun createEntry(
        id: Int = 1,
        foodName: String = "Test Food",
        mealType: MealType = MealType.BREAKFAST,
        time: String? = "12:30:00",
        calories: Double = 300.0,
        proteinG: Double = 10.0,
        carbsG: Double = 40.0,
        fatG: Double = 8.0,
        fiberG: Double = 3.0,
        sodiumMg: Double = 150.0,
        saturatedFatG: Double? = 2.0,
        transFatG: Double? = 0.5,
        sugarsG: Double? = 5.0,
        caloriesFromFat: Double? = 72.0,
    ) = FoodLogEntry(
        id = id,
        foodName = foodName,
        mealType = mealType,
        time = time,
        calories = calories,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        fiberG = fiberG,
        sodiumMg = sodiumMg,
        saturatedFatG = saturatedFatG,
        transFatG = transFatG,
        sugarsG = sugarsG,
        caloriesFromFat = caloriesFromFat,
    )

    @Test
    @DisplayName("maps food name correctly")
    fun mapsName() {
        val record = mapToNutritionRecord(createEntry(foodName = "Oatmeal"), "2026-01-15")
        assertEquals("Oatmeal", record.name)
    }

    @Test
    @DisplayName("maps energy in kilocalories")
    fun mapsEnergy() {
        val record = mapToNutritionRecord(createEntry(calories = 300.0), "2026-01-15")
        assertEquals(300.0, record.energy!!.inKilocalories)
    }

    @Test
    @DisplayName("maps protein in grams")
    fun mapsProtein() {
        val record = mapToNutritionRecord(createEntry(proteinG = 10.0), "2026-01-15")
        assertEquals(10.0, record.protein!!.inGrams)
    }

    @Test
    @DisplayName("maps carbs in grams")
    fun mapsCarbs() {
        val record = mapToNutritionRecord(createEntry(carbsG = 40.0), "2026-01-15")
        assertEquals(40.0, record.totalCarbohydrate!!.inGrams)
    }

    @Test
    @DisplayName("maps fat in grams")
    fun mapsFat() {
        val record = mapToNutritionRecord(createEntry(fatG = 8.0), "2026-01-15")
        assertEquals(8.0, record.totalFat!!.inGrams)
    }

    @Test
    @DisplayName("maps fiber in grams")
    fun mapsFiber() {
        val record = mapToNutritionRecord(createEntry(fiberG = 3.0), "2026-01-15")
        assertEquals(3.0, record.dietaryFiber!!.inGrams)
    }

    @Test
    @DisplayName("maps sodium in milligrams")
    fun mapsSodium() {
        val record = mapToNutritionRecord(createEntry(sodiumMg = 150.0), "2026-01-15")
        assertEquals(150.0, record.sodium!!.inMilligrams)
    }

    @Test
    @DisplayName("maps saturated fat in grams")
    fun mapsSaturatedFat() {
        val record = mapToNutritionRecord(createEntry(saturatedFatG = 2.0), "2026-01-15")
        assertEquals(2.0, record.saturatedFat!!.inGrams)
    }

    @Test
    @DisplayName("maps trans fat in grams")
    fun mapsTransFat() {
        val record = mapToNutritionRecord(createEntry(transFatG = 0.5), "2026-01-15")
        assertEquals(0.5, record.transFat!!.inGrams)
    }

    @Test
    @DisplayName("maps sugar in grams")
    fun mapsSugar() {
        val record = mapToNutritionRecord(createEntry(sugarsG = 5.0), "2026-01-15")
        assertEquals(5.0, record.sugar!!.inGrams)
    }

    @Test
    @DisplayName("maps energy from fat in kilocalories")
    fun mapsEnergyFromFat() {
        val record = mapToNutritionRecord(createEntry(caloriesFromFat = 72.0), "2026-01-15")
        assertEquals(72.0, record.energyFromFat!!.inKilocalories)
    }

    @Test
    @DisplayName("BREAKFAST maps to MEAL_TYPE_BREAKFAST")
    fun mapsBreakfast() {
        val record = mapToNutritionRecord(createEntry(mealType = MealType.BREAKFAST), "2026-01-15")
        assertEquals(HcMealType.MEAL_TYPE_BREAKFAST, record.mealType)
    }

    @Test
    @DisplayName("LUNCH maps to MEAL_TYPE_LUNCH")
    fun mapsLunch() {
        val record = mapToNutritionRecord(createEntry(mealType = MealType.LUNCH), "2026-01-15")
        assertEquals(HcMealType.MEAL_TYPE_LUNCH, record.mealType)
    }

    @Test
    @DisplayName("DINNER maps to MEAL_TYPE_DINNER")
    fun mapsDinner() {
        val record = mapToNutritionRecord(createEntry(mealType = MealType.DINNER), "2026-01-15")
        assertEquals(HcMealType.MEAL_TYPE_DINNER, record.mealType)
    }

    @Test
    @DisplayName("SNACK maps to MEAL_TYPE_SNACK")
    fun mapsSnack() {
        val record = mapToNutritionRecord(createEntry(mealType = MealType.SNACK), "2026-01-15")
        assertEquals(HcMealType.MEAL_TYPE_SNACK, record.mealType)
    }

    @Test
    @DisplayName("UNKNOWN maps to MEAL_TYPE_UNKNOWN")
    fun mapsUnknown() {
        val record = mapToNutritionRecord(createEntry(mealType = MealType.UNKNOWN), "2026-01-15")
        assertEquals(HcMealType.MEAL_TYPE_UNKNOWN, record.mealType)
    }

    @Test
    @DisplayName("time parsing produces correct start and end times with 60s gap")
    fun timeParsing() {
        val record = mapToNutritionRecord(createEntry(time = "12:30:00"), "2026-01-15")
        assertEquals(60, java.time.Duration.between(record.startTime, record.endTime).seconds)
    }

    @Test
    @DisplayName("null time falls back to noon")
    fun nullTimeFallsBackToNoon() {
        val record = mapToNutritionRecord(createEntry(time = null), "2026-01-15")
        val localTime = record.startTime.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        assertEquals(java.time.LocalTime.NOON, localTime)
    }

    @Test
    @DisplayName("null saturatedFatG maps to null saturatedFat")
    fun nullSaturatedFat() {
        val record = mapToNutritionRecord(createEntry(saturatedFatG = null), "2026-01-15")
        assertNull(record.saturatedFat)
    }

    @Test
    @DisplayName("null transFatG maps to null transFat")
    fun nullTransFat() {
        val record = mapToNutritionRecord(createEntry(transFatG = null), "2026-01-15")
        assertNull(record.transFat)
    }

    @Test
    @DisplayName("null sugarsG maps to null sugar")
    fun nullSugar() {
        val record = mapToNutritionRecord(createEntry(sugarsG = null), "2026-01-15")
        assertNull(record.sugar)
    }

    @Test
    @DisplayName("null caloriesFromFat maps to null energyFromFat")
    fun nullEnergyFromFat() {
        val record = mapToNutritionRecord(createEntry(caloriesFromFat = null), "2026-01-15")
        assertNull(record.energyFromFat)
    }

    @Test
    @DisplayName("clientRecordId set to foodscanner-{entryId}")
    fun clientRecordId() {
        val record = mapToNutritionRecord(createEntry(id = 42), "2026-01-15")
        assertEquals("foodscanner-42", record.metadata.clientRecordId)
    }
}
