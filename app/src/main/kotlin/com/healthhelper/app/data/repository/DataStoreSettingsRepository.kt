package com.healthhelper.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        const val DEFAULT_SYNC_INTERVAL = 10
    }

    override val apiKeyFlow: Flow<String> =
        dataStore.data.map { it[API_KEY] ?: "" }

    override val baseUrlFlow: Flow<String> =
        dataStore.data.map { it[BASE_URL] ?: "" }

    override val syncIntervalFlow: Flow<Int> =
        dataStore.data.map { it[SYNC_INTERVAL] ?: DEFAULT_SYNC_INTERVAL }

    override val lastSyncedDateFlow: Flow<String> =
        dataStore.data.map { it[LAST_SYNCED_DATE] ?: "" }

    override suspend fun setApiKey(value: String) {
        dataStore.edit { it[API_KEY] = value }
    }

    override suspend fun setBaseUrl(value: String) {
        dataStore.edit { it[BASE_URL] = value }
    }

    override suspend fun setSyncInterval(value: Int) {
        dataStore.edit { it[SYNC_INTERVAL] = value }
    }

    override suspend fun setLastSyncedDate(value: String) {
        dataStore.edit { it[LAST_SYNCED_DATE] = value }
    }

    override suspend fun isConfigured(): Boolean {
        val prefs = dataStore.data.first()
        val apiKey = prefs[API_KEY] ?: ""
        val baseUrl = prefs[BASE_URL] ?: ""
        return apiKey.isNotEmpty() && baseUrl.isNotEmpty()
    }
}
