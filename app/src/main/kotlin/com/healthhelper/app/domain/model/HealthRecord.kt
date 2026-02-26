package com.healthhelper.app.domain.model

import java.time.Instant

data class HealthRecord(
    val id: String,
    val type: HealthRecordType,
    val value: Double,
    val startTime: Instant,
    val endTime: Instant,
)
