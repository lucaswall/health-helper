package com.healthhelper.app.domain.model

/**
 * Outcome of a [SyncHealthReadingsUseCase] run. Lets callers (e.g. SyncWorker) decide
 * whether to surface a "missing permission" notification to the user.
 */
data class HealthReadingsSyncReport(
    val missingReadPermissions: Set<String> = emptySet(),
    val skippedTypes: Set<HealthSyncType> = emptySet(),
) {
    val hasMissingPermissions: Boolean get() = missingReadPermissions.isNotEmpty()
}
