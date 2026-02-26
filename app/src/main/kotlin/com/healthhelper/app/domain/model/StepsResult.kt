package com.healthhelper.app.domain.model

sealed class StepsResult {
    data class Success(val records: List<HealthRecord>) : StepsResult()
    data class Error(val type: StepsErrorType, val message: String) : StepsResult()
}

enum class StepsErrorType {
    PermissionDenied,
    Timeout,
    ServiceUnavailable,
    Unknown,
}
