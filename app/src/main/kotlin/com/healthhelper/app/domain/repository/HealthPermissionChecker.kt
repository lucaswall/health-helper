package com.healthhelper.app.domain.repository

interface HealthPermissionChecker {
    /**
     * Returns the set of Health Connect permission strings currently granted to this app,
     * or `null` when the grant state is unknown (Health Connect unavailable, or the query
     * timed out or failed). Callers must not treat `null` as "nothing granted".
     */
    suspend fun getGrantedPermissions(): Set<String>?

    /**
     * Returns the subset of [com.healthhelper.app.domain.model.HealthPermissions.REQUIRED]
     * that the installed Health Connect provider can actually grant. Feature-gated permissions
     * (background read, history) are dropped when the provider doesn't support them.
     */
    suspend fun getRequestablePermissions(): Set<String>
}
