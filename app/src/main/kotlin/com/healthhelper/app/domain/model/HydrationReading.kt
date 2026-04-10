package com.healthhelper.app.domain.model

import java.time.Instant
import java.time.ZoneOffset

data class HydrationReading(
    val volumeMl: Int,
    val timestamp: Instant = Instant.now(),
    val zoneOffset: ZoneOffset? = null,
) {
    init {
        require(volumeMl > 0) { "volumeMl must be positive, was $volumeMl" }
    }
}
