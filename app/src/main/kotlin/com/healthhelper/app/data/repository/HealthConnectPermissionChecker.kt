package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import com.healthhelper.app.domain.model.HealthPermissions
import com.healthhelper.app.domain.repository.HealthPermissionChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

class HealthConnectPermissionChecker @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : HealthPermissionChecker {

    override suspend fun getGrantedPermissions(): Set<String>? {
        if (healthConnectClient == null) return null
        return try {
            withTimeout(10_000L) {
                healthConnectClient.permissionController.getGrantedPermissions()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("getGrantedPermissions: timed out after 10s")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "getGrantedPermissions: failed")
            null
        }
    }

    override suspend fun getRequestablePermissions(): Set<String> {
        val client = healthConnectClient ?: return HealthPermissions.CORE
        val unavailable = FEATURE_GATED_PERMISSIONS.filter { (permission, feature) ->
            val available = try {
                client.features.getFeatureStatus(feature) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unknown: keep requesting it rather than silently dropping background sync.
                Timber.w(e, "getRequestablePermissions: feature check failed for %s", permission)
                true
            }
            !available
        }.keys
        return HealthPermissions.REQUIRED - unavailable
    }

    private companion object {
        val FEATURE_GATED_PERMISSIONS: Map<String, Int> = mapOf(
            HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND to
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            HealthPermissions.READ_HEALTH_DATA_HISTORY to
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
        )
    }
}
