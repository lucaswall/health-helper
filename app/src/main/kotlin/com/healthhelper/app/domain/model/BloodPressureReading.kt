package com.healthhelper.app.domain.model

import java.time.Instant

data class BloodPressureReading(
    val systolic: Int,
    val diastolic: Int,
    val bodyPosition: BodyPosition = BodyPosition.UNKNOWN,
    val measurementLocation: MeasurementLocation = MeasurementLocation.UNKNOWN,
    val timestamp: Instant = Instant.now(),
) {
    init {
        require(systolic in 60..300) { "systolic must be in 60..300, was $systolic" }
        require(diastolic in 30..200) { "diastolic must be in 30..200, was $diastolic" }
        require(systolic > diastolic) { "systolic ($systolic) must be greater than diastolic ($diastolic)" }
    }
}
