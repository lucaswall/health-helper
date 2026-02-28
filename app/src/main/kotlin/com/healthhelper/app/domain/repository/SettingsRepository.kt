package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.SyncedMealSummary
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val apiKeyFlow: Flow<String>
    val baseUrlFlow: Flow<String>
    val syncIntervalFlow: Flow<Int>
    val lastSyncedDateFlow: Flow<String>
    val lastSyncTimestampFlow: Flow<Long>
    val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>>
    val anthropicApiKeyFlow: Flow<String>
    suspend fun setApiKey(value: String)
    suspend fun setBaseUrl(value: String)
    suspend fun setSyncInterval(value: Int)
    suspend fun setLastSyncedDate(value: String)
    suspend fun setLastSyncTimestamp(value: Long)
    suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>)
    suspend fun isConfigured(): Boolean
}
