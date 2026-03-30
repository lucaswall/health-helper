package com.healthhelper.app.domain.model

data class FoodLogEntry(
    val id: Int,
    val foodName: String,
    val mealType: MealType,
    val time: String?,
    val zoneOffset: String? = null,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val sodiumMg: Double,
    val saturatedFatG: Double?,
    val transFatG: Double?,
    val sugarsG: Double?,
    val caloriesFromFat: Double?,
) {
    init {
        require(calories >= 0) { "calories must be non-negative, was $calories" }
        require(proteinG >= 0) { "proteinG must be non-negative, was $proteinG" }
        require(carbsG >= 0) { "carbsG must be non-negative, was $carbsG" }
        require(fatG >= 0) { "fatG must be non-negative, was $fatG" }
        require(fiberG >= 0) { "fiberG must be non-negative, was $fiberG" }
        require(sodiumMg >= 0) { "sodiumMg must be non-negative, was $sodiumMg" }
        if (saturatedFatG != null) require(saturatedFatG >= 0) { "saturatedFatG must be non-negative, was $saturatedFatG" }
        if (transFatG != null) require(transFatG >= 0) { "transFatG must be non-negative, was $transFatG" }
        if (sugarsG != null) require(sugarsG >= 0) { "sugarsG must be non-negative, was $sugarsG" }
        if (caloriesFromFat != null) require(caloriesFromFat >= 0) { "caloriesFromFat must be non-negative, was $caloriesFromFat" }
    }
}
