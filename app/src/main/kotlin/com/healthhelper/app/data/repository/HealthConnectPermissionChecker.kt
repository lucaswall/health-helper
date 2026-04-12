package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.domain.repository.HealthPermissionChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

class HealthConnectPermissionChecker @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : HealthPermissionChecker {

    override suspend fun getGrantedPermissions(): Set<String> {
        if (healthConnectClient == null) return emptySet()
        return try {
            withTimeout(10_000L) {
                healthConnectClient.permissionController.getGrantedPermissions()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getGrantedPermissions: timed out after 10s")
            emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "getGrantedPermissions: failed")
            emptySet()
        }
    }
}
