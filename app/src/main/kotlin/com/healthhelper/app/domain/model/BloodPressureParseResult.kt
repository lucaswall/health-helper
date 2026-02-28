package com.healthhelper.app.domain.model

sealed class BloodPressureParseResult {
    data class Success(
        val systolic: Int,
        val diastolic: Int,
    ) : BloodPressureParseResult()

    data class Error(
        val message: String,
    ) : BloodPressureParseResult()
}
