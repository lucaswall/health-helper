package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.SyncedMealSummary
import kotlinx.coroutines.flow.Flow
import java.net.URI

/** True when [url] is an absolute https URL with a non-blank host. */
fun isValidHttpsBaseUrl(url: String): Boolean = try {
    val uri = URI(url.trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
} catch (e: Exception) {
    false
}

/**
 * Thrown by [SettingsRepository.setApiKey] / [SettingsRepository.setAnthropicApiKey] when the
 * secret could not be persisted to secure storage (Keystore unavailable or the write failed).
 */
class SecureStorageUnavailableException(message: String) : IllegalStateException(message)

interface SettingsRepository {
    val apiKeyFlow: Flow<String>
    val anthropicApiKeyFlow: Flow<String>
    val baseUrlFlow: Flow<String>
    val syncIntervalFlow: Flow<Int>
    val lastSyncedDateFlow: Flow<String>
    val lastSyncTimestampFlow: Flow<Long>
    val lastGlucoseSyncTimestampFlow: Flow<Long>
    val lastBpSyncTimestampFlow: Flow<Long>
    val glucoseSyncCountFlow: Flow<Int>
    val bpSyncCountFlow: Flow<Int>
    val glucoseSyncCaughtUpFlow: Flow<Boolean>
    val bpSyncCaughtUpFlow: Flow<Boolean>
    val glucoseSyncRunTimestampFlow: Flow<Long>
    val bpSyncRunTimestampFlow: Flow<Long>
    val lastHydrationSyncTimestampFlow: Flow<Long>
    val hydrationSyncCountFlow: Flow<Int>
    val hydrationSyncCaughtUpFlow: Flow<Boolean>
    val hydrationSyncRunTimestampFlow: Flow<Long>
    val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>>
    val lastPermissionNotificationTimestampFlow: Flow<Long>
    val missingPermissionsAtLastNotificationFlow: Flow<Set<String>>

    /** @throws SecureStorageUnavailableException if the key could not be stored securely. */
    suspend fun setApiKey(value: String)

    /** @throws SecureStorageUnavailableException if the key could not be stored securely. */
    suspend fun setAnthropicApiKey(value: String)

    /**
     * Stores the base URL. When it points at a different server than the one the current sync
     * state belongs to, the sync watermarks/ETags/ledgers are reset in the same edit so the new
     * server receives full history.
     */
    suspend fun setBaseUrl(value: String)
    suspend fun setSyncInterval(value: Int)
    suspend fun setLastSyncedDate(value: String)
    suspend fun setLastSyncTimestamp(value: Long)
    suspend fun setLastGlucoseSyncTimestamp(value: Long)
    suspend fun setLastBpSyncTimestamp(value: Long)
    suspend fun setGlucoseSyncCount(value: Int)
    suspend fun setBpSyncCount(value: Int)
    suspend fun setGlucoseSyncCaughtUp(value: Boolean)
    suspend fun setBpSyncCaughtUp(value: Boolean)
    suspend fun setGlucoseSyncRunTimestamp(value: Long)
    suspend fun setBpSyncRunTimestamp(value: Long)
    suspend fun setLastHydrationSyncTimestamp(value: Long)
    suspend fun setHydrationSyncCount(value: Int)
    suspend fun setHydrationSyncCaughtUp(value: Boolean)
    suspend fun setHydrationSyncRunTimestamp(value: Long)
    suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>)
    suspend fun setLastPermissionNotificationTimestamp(value: Long)
    suspend fun setMissingPermissionsAtLastNotification(permissions: Set<String>)
    suspend fun getETag(date: String): String?
    suspend fun setETag(date: String, etag: String)
    suspend fun isConfigured(): Boolean
    suspend fun getDirectPushedGlucoseTimestamps(): Set<Long>
    suspend fun addDirectPushedGlucoseTimestamp(timestampMs: Long)
    suspend fun getDirectPushedBpTimestamps(): Set<Long>
    suspend fun addDirectPushedBpTimestamp(timestampMs: Long)
    suspend fun pruneDirectPushedTimestamps(glucoseBeforeMs: Long, bpBeforeMs: Long)
    suspend fun resetHydrationWatermarkIfNeeded()
}
