package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.MealType as HcMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

fun mapToNutritionRecord(entry: FoodLogEntry, date: String): NutritionRecord {
    val localDate = LocalDate.parse(date)
    val localTime = if (entry.time != null) {
        try {
            LocalTime.parse(entry.time)
        } catch (e: DateTimeParseException) {
            Timber.w("mapToNutritionRecord: unparseable time '%s' for entry %d, defaulting to noon", entry.time, entry.id)
            LocalTime.NOON
        }
    } else {
        LocalTime.NOON
    }
    val zoneId: java.time.ZoneId = if (entry.zoneOffset != null) {
        try {
            ZoneId.of(entry.zoneOffset)
        } catch (e: java.time.DateTimeException) {
            Timber.w("mapToNutritionRecord: unparseable zoneOffset '%s' for entry %d, defaulting to system", entry.zoneOffset, entry.id)
            ZoneId.systemDefault()
        }
    } else {
        ZoneId.systemDefault()
    }
    val startZdt = ZonedDateTime.of(localDate, localTime, zoneId)
    val startInstant = startZdt.toInstant()
    val endInstant = startInstant.plusSeconds(60)

    val mealTypeInt = when (entry.mealType) {
        MealType.BREAKFAST -> HcMealType.MEAL_TYPE_BREAKFAST
        MealType.LUNCH -> HcMealType.MEAL_TYPE_LUNCH
        MealType.DINNER -> HcMealType.MEAL_TYPE_DINNER
        MealType.SNACK -> HcMealType.MEAL_TYPE_SNACK
        MealType.UNKNOWN -> HcMealType.MEAL_TYPE_UNKNOWN
    }

    return NutritionRecord(
        startTime = startInstant,
        startZoneOffset = startZdt.offset,
        endTime = endInstant,
        endZoneOffset = startZdt.offset,
        name = entry.foodName,
        energy = Energy.kilocalories(entry.calories),
        protein = Mass.grams(entry.proteinG),
        totalCarbohydrate = Mass.grams(entry.carbsG),
        totalFat = Mass.grams(entry.fatG),
        dietaryFiber = Mass.grams(entry.fiberG),
        sodium = Mass.milligrams(entry.sodiumMg),
        saturatedFat = entry.saturatedFatG?.let { Mass.grams(it) },
        transFat = entry.transFatG?.let { Mass.grams(it) },
        sugar = entry.sugarsG?.let { Mass.grams(it) },
        energyFromFat = entry.caloriesFromFat?.let { Energy.kilocalories(it) },
        mealType = mealTypeInt,
        metadata = Metadata.manualEntry(
            clientRecordId = "foodscanner-${entry.id}",
        ),
    )
}
