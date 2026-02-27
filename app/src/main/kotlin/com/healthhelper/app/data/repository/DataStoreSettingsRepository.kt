package com.healthhelper.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val apiKeyFlow: Flow<String> = flowOf("")
    override val baseUrlFlow: Flow<String> = flowOf("")
    override val syncIntervalFlow: Flow<Int> = flowOf(10)
    override val lastSyncedDateFlow: Flow<String> = flowOf("")
    override suspend fun setApiKey(value: String) {}
    override suspend fun setBaseUrl(value: String) {}
    override suspend fun setSyncInterval(value: Int) {}
    override suspend fun setLastSyncedDate(value: String) {}
    override suspend fun isConfigured(): Boolean = false
}
