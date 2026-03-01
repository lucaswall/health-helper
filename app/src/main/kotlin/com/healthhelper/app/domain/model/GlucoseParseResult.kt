package com.healthhelper.app.domain.model

sealed class GlucoseParseResult {
    data class Success(
        val value: Double,
        val detectedUnit: GlucoseUnit,
    ) : GlucoseParseResult()

    data class Error(
        val message: String,
    ) : GlucoseParseResult()
}
