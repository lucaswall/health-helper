package com.healthhelper.app.presentation.ui

import com.healthhelper.app.domain.model.HealthPermissions
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncScreenPermissionLogicTest {

    @Test
    fun `auto-requests when nothing has been requested yet`() {
        assertTrue(shouldAutoRequestHcPermissions(HealthPermissions.REQUIRED, emptySet()))
    }

    @Test
    fun `does not auto-request when nothing is missing`() {
        assertFalse(shouldAutoRequestHcPermissions(emptySet(), emptySet()))
    }

    @Test
    fun `does not re-launch for the same missing set`() {
        assertFalse(shouldAutoRequestHcPermissions(HealthPermissions.OPTIONAL, HealthPermissions.OPTIONAL))
    }

    @Test
    fun `does not re-launch after a partial grant leaves a subset missing`() {
        // Sheet shown for everything; user granted core but declined background/history.
        assertFalse(shouldAutoRequestHcPermissions(HealthPermissions.OPTIONAL, HealthPermissions.REQUIRED))
    }

    @Test
    fun `auto-requests when a newly missing permission appears`() {
        val missing = HealthPermissions.OPTIONAL + HealthPermissions.WRITE_NUTRITION
        assertTrue(shouldAutoRequestHcPermissions(missing, HealthPermissions.OPTIONAL))
    }
}
