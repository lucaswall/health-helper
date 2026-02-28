package com.healthhelper.app.domain.model

sealed class FoodLogResult {
    data class Data(val entries: List<FoodLogEntry>) : FoodLogResult()
    data object NotModified : FoodLogResult()
}
