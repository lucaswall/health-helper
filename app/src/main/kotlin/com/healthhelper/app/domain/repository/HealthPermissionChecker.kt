package com.healthhelper.app.domain.repository

interface HealthPermissionChecker {
    /**
     * Returns the set of Health Connect permission strings currently granted to this app.
     * Returns an empty set if Health Connect is unavailable or the query fails.
     */
    suspend fun getGrantedPermissions(): Set<String>
}
