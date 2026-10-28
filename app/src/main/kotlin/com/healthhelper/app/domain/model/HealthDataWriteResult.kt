package com.healthhelper.app.domain.model

data class HealthDataWriteResult(
    val healthConnectSuccess: Boolean,
    val foodScannerResult: Result<Unit>,
    /** Result of the separate HC heart-rate write; null when the reading had no heart rate. */
    val heartRateSuccess: Boolean? = null,
) {
    /** Primary reading reached both destinations. A failed heart-rate write is reported via [heartRateFailed]. */
    val allSucceeded: Boolean get() = healthConnectSuccess && foodScannerResult.isSuccess
    val foodScannerFailed: Boolean get() = foodScannerResult.isFailure
    val heartRateFailed: Boolean get() = heartRateSuccess == false
}
