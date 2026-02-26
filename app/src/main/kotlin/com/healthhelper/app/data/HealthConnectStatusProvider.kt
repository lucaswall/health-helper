package com.healthhelper.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.domain.model.HealthConnectStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface HealthConnectStatusProvider {
    fun getStatus(): HealthConnectStatus
}

class HealthConnectStatusProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthConnectStatusProvider {
    override fun getStatus(): HealthConnectStatus {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NeedsUpdate
            else -> HealthConnectStatus.NotInstalled
        }
    }
}
