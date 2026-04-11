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

    suspend fun setApiKey(value: String)
    suspend fun setAnthropicApiKey(value: String)
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
