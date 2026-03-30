package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.SyncedMealSummary
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val apiKeyFlow: Flow<String>
    val anthropicApiKeyFlow: Flow<String>
    val baseUrlFlow: Flow<String>
    val syncIntervalFlow: Flow<Int>
    val lastSyncedDateFlow: Flow<String>
    val lastSyncTimestampFlow: Flow<Long>
    val lastHealthReadingsSyncTimestampFlow: Flow<Long>
    val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>>

    // Per-type sync timestamps (Task 4)
    val lastGlucoseSyncTimestampFlow: Flow<Long>
    val lastBpSyncTimestampFlow: Flow<Long>
    val glucoseSyncCountFlow: Flow<Int>
    val bpSyncCountFlow: Flow<Int>
    val glucoseSyncCaughtUpFlow: Flow<Boolean>
    val bpSyncCaughtUpFlow: Flow<Boolean>

    suspend fun setApiKey(value: String)
    suspend fun setAnthropicApiKey(value: String)
    suspend fun setBaseUrl(value: String)
    suspend fun setSyncInterval(value: Int)
    suspend fun setLastSyncedDate(value: String)
    suspend fun setLastSyncTimestamp(value: Long)
    suspend fun setLastHealthReadingsSyncTimestamp(value: Long)
    suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>)
    suspend fun getETag(date: String): String?
    suspend fun setETag(date: String, etag: String)
    suspend fun isConfigured(): Boolean

    // Per-type sync setters (Task 4)
    suspend fun setLastGlucoseSyncTimestamp(timestampMs: Long)
    suspend fun setLastBpSyncTimestamp(timestampMs: Long)
    suspend fun setGlucoseSyncCount(count: Int)
    suspend fun setBpSyncCount(count: Int)
    suspend fun setGlucoseSyncCaughtUp(caughtUp: Boolean)
    suspend fun setBpSyncCaughtUp(caughtUp: Boolean)

    // Already-pushed ledger (Task 2)
    suspend fun getDirectPushedGlucoseTimestamps(): Set<Long>
    suspend fun addDirectPushedGlucoseTimestamp(timestampMs: Long)
    suspend fun getDirectPushedBpTimestamps(): Set<Long>
    suspend fun addDirectPushedBpTimestamp(timestampMs: Long)
    suspend fun pruneDirectPushedTimestamps(glucoseBeforeMs: Long, bpBeforeMs: Long)
}
