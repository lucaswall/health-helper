package com.healthhelper.app.domain.model

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

/** A waist circumference measurement in centimetres (WHO site: midway between lowest rib and hip bone). */
data class WaistReading(
    val valueCm: Double,
    val timestamp: Instant = Instant.now(),
    /** Offset at measurement time, so every layer buckets the reading into the right local day. */
    val zoneOffset: ZoneOffset? = null,
) {
    init {
        require(valueCm in VALUE_RANGE_CM) { "valueCm must be in $VALUE_RANGE_CM, was $valueCm" }
    }

    companion object {
        /** Plausible adult waist range; catches typos (e.g. 1045 for 104.5) before they reach the server. */
        val VALUE_RANGE_CM: ClosedFloatingPointRange<Double> = 40.0..250.0

        /** Readings are kept to one decimal (a tape reads to the millimetre at best). */
        fun roundToTenth(value: Double): Double = (value * 10).roundToLong() / 10.0
    }
}
