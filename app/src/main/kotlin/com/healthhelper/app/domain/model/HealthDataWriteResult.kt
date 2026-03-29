package com.healthhelper.app.domain.model

data class HealthDataWriteResult(
    val healthConnectSuccess: Boolean,
    val foodScannerResult: Result<Unit>,
) {
    val allSucceeded: Boolean get() = healthConnectSuccess && foodScannerResult.isSuccess
    val foodScannerFailed: Boolean get() = foodScannerResult.isFailure
}
