package com.healthhelper.app.domain.model

data class ReadingsResult<T>(
    val readings: List<T>,
    val truncated: Boolean = false,
)
