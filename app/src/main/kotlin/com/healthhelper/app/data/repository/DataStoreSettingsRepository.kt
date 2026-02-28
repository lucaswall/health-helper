package com.healthhelper.app.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncedMealSummary
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val encryptedPrefs: SharedPreferences,
) : SettingsRepository {

    @Serializable
    private data class SyncedMealDto(
        val foodName: String,
        val mealType: String,
        val calories: Int,
    )

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_SYNCED_MEALS = stringPreferencesKey("last_synced_meals")
        const val DEFAULT_SYNC_INTERVAL = 5
        const val ENCRYPTED_API_KEY = "api_key"
    }

    private val migrationMutex = Mutex()
    private var migrationComplete = false

    private suspend fun migrateIfNeeded() {
        migrationMutex.withLock {
            if (migrationComplete) return
            withContext(Dispatchers.IO) {
                val existingKey = encryptedPrefs.getString(ENCRYPTED_API_KEY, "")
                if (!existingKey.isNullOrEmpty()) {
                    migrationComplete = true
                    return@withContext
                }
                val prefs = dataStore.data.first()
                val legacyKey = prefs[API_KEY]
                if (!legacyKey.isNullOrEmpty()) {
                    encryptedPrefs.edit().putString(ENCRYPTED_API_KEY, legacyKey).commit()
                    val verified = encryptedPrefs.getString(ENCRYPTED_API_KEY, "")
                    if (verified == legacyKey) {
                        dataStore.edit { it.remove(API_KEY) }
                    } else {
                        Timber.e("Migration verification failed: encrypted prefs read-back mismatch")
                        return@withContext
                    }
                }
                migrationComplete = true
            }
        }
    }

    override val apiKeyFlow: Flow<String> = callbackFlow {
        try {
            migrateIfNeeded()
        } catch (e: Exception) {
            Timber.e(e, "API key migration failed")
        }
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

    override val lastSyncTimestampFlow: Flow<Long> =
        dataStore.data.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }

    override val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>> =
        dataStore.data.map { prefs ->
            val json = prefs[LAST_SYNCED_MEALS] ?: return@map emptyList()
            try {
                val dtos = Json.decodeFromString<List<SyncedMealDto>>(json)
                dtos.map { dto ->
                    SyncedMealSummary(
                        foodName = dto.foodName,
                        mealType = try {
                            MealType.valueOf(dto.mealType)
                        } catch (e: IllegalArgumentException) {
                            MealType.UNKNOWN
                        },
                        calories = dto.calories,
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

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

    override suspend fun setLastSyncTimestamp(value: Long) {
        dataStore.edit { it[LAST_SYNC_TIMESTAMP] = value }
    }

    override suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>) {
        val dtos = meals.map { SyncedMealDto(it.foodName, it.mealType.name, it.calories) }
        val json = Json.encodeToString(dtos)
        dataStore.edit { it[LAST_SYNCED_MEALS] = json }
    }

    override suspend fun isConfigured(): Boolean {
        migrateIfNeeded()
        return withContext(Dispatchers.IO) {
            val apiKey = encryptedPrefs.getString(ENCRYPTED_API_KEY, "") ?: ""
            val prefs = dataStore.data.first()
            val baseUrl = prefs[BASE_URL] ?: ""
            apiKey.isNotEmpty() && baseUrl.isNotEmpty()
        }
    }
}
