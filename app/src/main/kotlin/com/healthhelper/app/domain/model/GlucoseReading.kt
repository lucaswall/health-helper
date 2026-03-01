package com.healthhelper.app.domain.model

import java.time.Instant
import kotlin.math.roundToInt

data class GlucoseReading(
    val valueMmolL: Double,
    val relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN,
    val glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN,
    val specimenSource: SpecimenSource = SpecimenSource.UNKNOWN,
    val timestamp: Instant = Instant.now(),
) {
    init {
        require(valueMmolL in 1.0..40.0) { "valueMmolL must be in 1.0..40.0, was $valueMmolL" }
    }

    fun displayInMgDl(): Int = (valueMmolL * 18.018).roundToInt()
}
