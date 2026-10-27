package com.healthhelper.app.domain.model

sealed class FoodLogResult {
    /**
     * Fresh food log for a date. [etag] is the server's ETag for this response; it is NOT
     * persisted by the repository — the caller commits it (via SettingsRepository.setETag)
     * only once the entries are durably in Health Connect, so a failed write is retried.
     */
    data class Data(
        val entries: List<FoodLogEntry>,
        val etag: String? = null,
    ) : FoodLogResult()

    data object NotModified : FoodLogResult()
}
