package com.healthhelper.app.domain.model

sealed class SyncResult {
    data class Success(val recordsSynced: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
    data object NeedsConfiguration : SyncResult()
}
