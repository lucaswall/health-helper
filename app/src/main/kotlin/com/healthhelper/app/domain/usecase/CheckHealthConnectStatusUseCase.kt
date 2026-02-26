package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.HealthConnectStatusProvider
import com.healthhelper.app.domain.model.HealthConnectStatus
import timber.log.Timber
import javax.inject.Inject

class CheckHealthConnectStatusUseCase @Inject constructor(
    private val statusProvider: HealthConnectStatusProvider,
) {
    operator fun invoke(): HealthConnectStatus {
        val status = statusProvider.getStatus()
        Timber.d("Health Connect status: %s", status)
        return status
    }
}
