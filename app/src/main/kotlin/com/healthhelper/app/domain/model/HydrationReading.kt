package com.healthhelper.app.domain.model

import java.time.Instant
import java.time.ZoneOffset

data class HydrationReading(
    val volumeMl: Int,
    val timestamp: Instant = Instant.now(),
    val zoneOffset: ZoneOffset? = null,
    /**
     * When the source record was last inserted or modified in Health Connect. Used to detect
     * records written late with a past timestamp (behind the sync watermark).
     */
    val lastModified: Instant? = null,
) {
    init {
        require(volumeMl > 0) { "volumeMl must be positive, was $volumeMl" }
    }
}
