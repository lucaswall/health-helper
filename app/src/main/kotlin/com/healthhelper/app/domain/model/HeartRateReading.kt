package com.healthhelper.app.domain.model

import java.time.Instant
import java.time.ZoneOffset

/** A single pulse measurement, e.g. the heart rate shown by a blood pressure monitor. */
data class HeartRateReading(
    val beatsPerMinute: Int,
    val timestamp: Instant,
    val zoneOffset: ZoneOffset? = null,
) {
    init {
        require(beatsPerMinute in BloodPressureReading.HEART_RATE_RANGE) {
            "beatsPerMinute must be in ${BloodPressureReading.HEART_RATE_RANGE}, was $beatsPerMinute"
        }
    }
}
