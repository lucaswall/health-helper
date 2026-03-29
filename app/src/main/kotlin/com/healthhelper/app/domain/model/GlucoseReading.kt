package com.healthhelper.app.domain.model

import java.time.Instant
import kotlin.math.roundToInt

data class GlucoseReading(
    val valueMgDl: Int,
    val relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN,
    val glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN,
    val specimenSource: SpecimenSource = SpecimenSource.UNKNOWN,
    val timestamp: Instant = Instant.now(),
) {
    init {
        require(valueMgDl in 18..720) { "valueMgDl must be in 18..720, was $valueMgDl" }
    }

    fun toMmolL(): Double = valueMgDl / 18.018

    companion object {
        fun fromMmolL(value: Double): Int = (value * 18.018).roundToInt()
    }
}
