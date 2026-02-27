package com.healthhelper.app.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val encryptedPrefs: SharedPreferences,
) : SettingsRepository {

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        const val DEFAULT_SYNC_INTERVAL = 15
        const val ENCRYPTED_API_KEY = "api_key"
    }

    private val migrationMutex = Mutex()
    private var migrationComplete = false

    private suspend fun migrateIfNeeded() {
        migrationMutex.withLock {
            if (migrationComplete) return
            // If encrypted prefs already has the key, skip migration
            val existingKey = encryptedPrefs.getString(ENCRYPTED_API_KEY, "")
            if (!existingKey.isNullOrEmpty()) {
                migrationComplete = true
                return
            }
            // Check DataStore for a legacy api_key to migrate
            val prefs = dataStore.data.first()
            val legacyKey = prefs[API_KEY]
            if (!legacyKey.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    encryptedPrefs.edit().putString(ENCRYPTED_API_KEY, legacyKey).commit()
                }
                // Verify write-back before clearing DataStore
                val verified = encryptedPrefs.getString(ENCRYPTED_API_KEY, "")
                if (verified == legacyKey) {
                    dataStore.edit { it.remove(API_KEY) }
                } else {
                    Timber.e("Migration verification failed: encrypted prefs read-back mismatch")
                    return
                }
            }
            migrationComplete = true
        }
    }

    override val apiKeyFlow: Flow<String> = callbackFlow {
        migrateIfNeeded()
        trySend(encryptedPrefs.getString(ENCRYPTED_API_KEY, "") ?: "")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ENCRYPTED_API_KEY) {
                trySend(encryptedPrefs.getString(ENCRYPTED_API_KEY, "") ?: "")
            }
        }
        encryptedPrefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { encryptedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override val baseUrlFlow: Flow<String> =
        dataStore.data.map { it[BASE_URL] ?: "" }

    override val syncIntervalFlow: Flow<Int> =
        dataStore.data.map { it[SYNC_INTERVAL] ?: DEFAULT_SYNC_INTERVAL }

    override val lastSyncedDateFlow: Flow<String> =
        dataStore.data.map { it[LAST_SYNCED_DATE] ?: "" }

    override suspend fun setApiKey(value: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_API_KEY, value).commit()
        }
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
        val apiKey = encryptedPrefs.getString(ENCRYPTED_API_KEY, "") ?: ""
        val prefs = dataStore.data.first()
        val baseUrl = prefs[BASE_URL] ?: ""
        return apiKey.isNotEmpty() && baseUrl.isNotEmpty()
    }
}
