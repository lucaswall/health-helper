package com.healthhelper.app.domain.model

import java.time.Instant
import java.time.ZoneOffset

data class BloodPressureReading(
    val systolic: Int,
    val diastolic: Int,
    val bodyPosition: BodyPosition = BodyPosition.UNKNOWN,
    val measurementLocation: MeasurementLocation = MeasurementLocation.UNKNOWN,
    val timestamp: Instant = Instant.now(),
    /** Offset at measurement time, when known (e.g. read from Health Connect). */
    val zoneOffset: ZoneOffset? = null,
) {
    init {
        require(systolic in SYSTOLIC_RANGE) {
            "systolic must be in ${SYSTOLIC_RANGE.first}..${SYSTOLIC_RANGE.last}, was $systolic"
        }
        require(diastolic in DIASTOLIC_RANGE) {
            "diastolic must be in ${DIASTOLIC_RANGE.first}..${DIASTOLIC_RANGE.last}, was $diastolic"
        }
        require(systolic > diastolic) { "systolic ($systolic) must be greater than diastolic ($diastolic)" }
    }

    companion object {
        /** Upper bounds match Health Connect's BloodPressureRecord limits (systolic ≤ 200, diastolic ≤ 180). */
        val SYSTOLIC_RANGE: IntRange = 60..200
        val DIASTOLIC_RANGE: IntRange = 30..180
    }
}
