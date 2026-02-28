package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.FoodLogEntry

data class FoodLogApiResponse(
    val entries: List<FoodLogEntry>,
    val etag: String?,
    val notModified: Boolean,
)
