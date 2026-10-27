package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import com.healthhelper.app.domain.model.HealthPermissions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthConnectPermissionCheckerTest {

    private val client: HealthConnectClient = mockk(relaxed = true)
    private val checker = HealthConnectPermissionChecker(client)

    @Test
    fun `getGrantedPermissions returns null when client is null`() = runTest {
        assertNull(HealthConnectPermissionChecker(null).getGrantedPermissions())
    }

    @Test
    fun `getGrantedPermissions returns null on failure instead of empty set`() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } throws IllegalStateException("binder")
        assertNull(checker.getGrantedPermissions())
    }

    @Test
    fun `getGrantedPermissions returns granted set on success`() = runTest {
        coEvery { client.permissionController.getGrantedPermissions() } returns HealthPermissions.CORE
        assertEquals(HealthPermissions.CORE, checker.getGrantedPermissions())
    }

    @Test
    fun `getRequestablePermissions includes optional permissions when features available`() = runTest {
        every { client.features.getFeatureStatus(any()) } returns HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        assertEquals(HealthPermissions.REQUIRED, checker.getRequestablePermissions())
    }

    @Test
    fun `getRequestablePermissions drops feature-gated permissions when unavailable`() = runTest {
        every {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
        } returns HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        every {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)
        } returns HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

        assertEquals(
            HealthPermissions.REQUIRED - HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND,
            checker.getRequestablePermissions(),
        )
    }

    @Test
    fun `getRequestablePermissions keeps permission when feature check throws`() = runTest {
        every { client.features.getFeatureStatus(any()) } throws RuntimeException("old HC")
        assertEquals(HealthPermissions.REQUIRED, checker.getRequestablePermissions())
    }
}
