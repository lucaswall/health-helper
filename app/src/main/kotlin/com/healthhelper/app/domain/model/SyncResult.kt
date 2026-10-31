package com.healthhelper.app.domain.model

sealed class SyncResult {
    data class Success(val recordsSynced: Int, val daysProcessed: Int) : SyncResult()

    /**
     * [isNetworkError]: the failure was network-level (offline, DNS, timeout, TLS interception by
     * a captive portal) — expected on a phone and fixed by retrying, not a bug.
     */
    data class Error(
        val message: String,
        val isNetworkError: Boolean = false,
    ) : SyncResult()

    data object NeedsConfiguration : SyncResult()
}
