package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.HealthConnectStatusProvider
import com.healthhelper.app.domain.model.HealthConnectStatus
import javax.inject.Inject

class CheckHealthConnectStatusUseCase @Inject constructor(
    private val statusProvider: HealthConnectStatusProvider,
) {
    operator fun invoke(): HealthConnectStatus {
        return statusProvider.getStatus()
    }
}
