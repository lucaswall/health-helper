package com.healthhelper.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val apiKeyFlow: Flow<String>
    val baseUrlFlow: Flow<String>
    val syncIntervalFlow: Flow<Int>
    val lastSyncedDateFlow: Flow<String>
    suspend fun setApiKey(value: String)
    suspend fun setBaseUrl(value: String)
    suspend fun setSyncInterval(value: Int)
    suspend fun setLastSyncedDate(value: String)
    suspend fun isConfigured(): Boolean
}
