package com.healthhelper.app.domain.model

import java.time.Instant

data class HealthRecord(
    val type: String,
    val value: Double,
    val startTime: Instant,
    val endTime: Instant,
)
