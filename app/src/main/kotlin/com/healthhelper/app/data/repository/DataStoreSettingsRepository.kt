package com.healthhelper.app.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val encryptedPrefs: SharedPreferences?,
) : SettingsRepository {

    @Serializable
    private data class SyncedMealDto(
        val foodName: String,
        val mealType: String,
        val calories: Int,
        val timestamp: String? = null,
    )

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_GLUCOSE_SYNC_TIMESTAMP = longPreferencesKey("last_glucose_sync_timestamp")
        val LAST_BP_SYNC_TIMESTAMP = longPreferencesKey("last_bp_sync_timestamp")
        val GLUCOSE_SYNC_COUNT = intPreferencesKey("glucose_sync_count")
        val BP_SYNC_COUNT = intPreferencesKey("bp_sync_count")
        val GLUCOSE_SYNC_CAUGHT_UP = booleanPreferencesKey("glucose_sync_caught_up")
        val BP_SYNC_CAUGHT_UP = booleanPreferencesKey("bp_sync_caught_up")
        val GLUCOSE_SYNC_RUN_TIMESTAMP = longPreferencesKey("glucose_sync_run_timestamp")
        val BP_SYNC_RUN_TIMESTAMP = longPreferencesKey("bp_sync_run_timestamp")
        val LAST_SYNCED_MEALS = stringPreferencesKey("last_synced_meals")
        val FOOD_LOG_ETAGS = stringPreferencesKey("food_log_etags")
        val DIRECT_PUSHED_GLUCOSE_TIMESTAMPS = stringPreferencesKey("direct_pushed_glucose_timestamps")
        val DIRECT_PUSHED_BP_TIMESTAMPS = stringPreferencesKey("direct_pushed_bp_timestamps")
        const val DEFAULT_SYNC_INTERVAL = 15
        const val ENCRYPTED_API_KEY = "api_key"
        const val ENCRYPTED_ANTHROPIC_KEY = "anthropic_api_key"
    }

    private val migrationMutex = Mutex()
    private var migrationComplete = false

    private suspend fun migrateIfNeeded() {
        if (encryptedPrefs == null) return
        migrationMutex.withLock {
            if (migrationComplete) return
            withContext(Dispatchers.IO) {
                val existingKey = encryptedPrefs.getString(ENCRYPTED_API_KEY, "")
                if (!existingKey.isNullOrEmpty()) {
                    // Clean residual plaintext key from DataStore (crash recovery)
                    dataStore.edit { it.remove(API_KEY) }
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

    override val apiKeyFlow: Flow<String> = if (encryptedPrefs == null) {
        flowOf("")
    } else {
        callbackFlow {
            try {
                migrateIfNeeded()
            } catch (e: CancellationException) {
                throw e
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
    }

    override val anthropicApiKeyFlow: Flow<String> = if (encryptedPrefs == null) {
        flowOf("")
    } else {
        callbackFlow {
            trySend(encryptedPrefs.getString(ENCRYPTED_ANTHROPIC_KEY, "") ?: "")
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ENCRYPTED_ANTHROPIC_KEY) {
                    trySend(encryptedPrefs.getString(ENCRYPTED_ANTHROPIC_KEY, "") ?: "")
                }
            }
            encryptedPrefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { encryptedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    override val baseUrlFlow: Flow<String> =
        dataStore.data.map { it[BASE_URL] ?: "" }

    override val syncIntervalFlow: Flow<Int> =
        dataStore.data.map { it[SYNC_INTERVAL] ?: DEFAULT_SYNC_INTERVAL }

    override val lastSyncedDateFlow: Flow<String> =
        dataStore.data.map { it[LAST_SYNCED_DATE] ?: "" }

    override val lastSyncTimestampFlow: Flow<Long> =
        dataStore.data.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }

    override val lastGlucoseSyncTimestampFlow: Flow<Long> =
        dataStore.data.map { it[LAST_GLUCOSE_SYNC_TIMESTAMP] ?: 0L }

    override val lastBpSyncTimestampFlow: Flow<Long> =
        dataStore.data.map { it[LAST_BP_SYNC_TIMESTAMP] ?: 0L }

    override val glucoseSyncCountFlow: Flow<Int> =
        dataStore.data.map { it[GLUCOSE_SYNC_COUNT] ?: 0 }

    override val bpSyncCountFlow: Flow<Int> =
        dataStore.data.map { it[BP_SYNC_COUNT] ?: 0 }

    override val glucoseSyncCaughtUpFlow: Flow<Boolean> =
        dataStore.data.map { it[GLUCOSE_SYNC_CAUGHT_UP] ?: false }

    override val bpSyncCaughtUpFlow: Flow<Boolean> =
        dataStore.data.map { it[BP_SYNC_CAUGHT_UP] ?: false }

    override val glucoseSyncRunTimestampFlow: Flow<Long> =
        dataStore.data.map { it[GLUCOSE_SYNC_RUN_TIMESTAMP] ?: 0L }

    override val bpSyncRunTimestampFlow: Flow<Long> =
        dataStore.data.map { it[BP_SYNC_RUN_TIMESTAMP] ?: 0L }

    override val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>> =
        dataStore.data.map { prefs ->
            val json = prefs[LAST_SYNCED_MEALS] ?: return@map emptyList()
            try {
                val dtos = Json.decodeFromString<List<SyncedMealDto>>(json)
                dtos.mapNotNull { dto ->
                    try {
                        val timestamp = dto.timestamp?.let {
                            try {
                                Instant.parse(it)
                            } catch (_: Exception) {
                                Instant.EPOCH
                            }
                        } ?: Instant.EPOCH
                        SyncedMealSummary(
                            foodName = dto.foodName,
                            mealType = try {
                                MealType.valueOf(dto.mealType)
                            } catch (e: IllegalArgumentException) {
                                MealType.UNKNOWN
                            },
                            calories = dto.calories,
                            timestamp = timestamp,
                        )
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to deserialize last synced meals, returning empty list")
                emptyList()
            }
        }

    override suspend fun setApiKey(value: String) {
        if (encryptedPrefs == null) {
            Timber.w("Cannot save API key: encrypted prefs unavailable")
            return
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_API_KEY, value).commit()
        }
    }

    override suspend fun setAnthropicApiKey(value: String) {
        if (encryptedPrefs == null) {
            Timber.w("Cannot save Anthropic API key: encrypted prefs unavailable")
            return
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_ANTHROPIC_KEY, value).commit()
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

    override suspend fun setLastGlucoseSyncTimestamp(value: Long) {
        dataStore.edit { it[LAST_GLUCOSE_SYNC_TIMESTAMP] = value }
    }

    override suspend fun setLastBpSyncTimestamp(value: Long) {
        dataStore.edit { it[LAST_BP_SYNC_TIMESTAMP] = value }
    }

    override suspend fun setGlucoseSyncCount(value: Int) {
        dataStore.edit { it[GLUCOSE_SYNC_COUNT] = value }
    }

    override suspend fun setBpSyncCount(value: Int) {
        dataStore.edit { it[BP_SYNC_COUNT] = value }
    }

    override suspend fun setGlucoseSyncCaughtUp(value: Boolean) {
        dataStore.edit { it[GLUCOSE_SYNC_CAUGHT_UP] = value }
    }

    override suspend fun setBpSyncCaughtUp(value: Boolean) {
        dataStore.edit { it[BP_SYNC_CAUGHT_UP] = value }
    }

    override suspend fun setGlucoseSyncRunTimestamp(value: Long) {
        dataStore.edit { it[GLUCOSE_SYNC_RUN_TIMESTAMP] = value }
    }

    override suspend fun setBpSyncRunTimestamp(value: Long) {
        dataStore.edit { it[BP_SYNC_RUN_TIMESTAMP] = value }
    }

    override suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>) {
        val dtos = meals.map {
            SyncedMealDto(
                it.foodName,
                it.mealType.name,
                it.calories,
                it.timestamp.toString(),
            )
        }
        val json = Json.encodeToString(dtos)
        dataStore.edit { it[LAST_SYNCED_MEALS] = json }
    }

    override suspend fun getETag(date: String): String? {
        val prefs = dataStore.data.first()
        val json = prefs[FOOD_LOG_ETAGS] ?: return null
        return try {
            val map = Json.decodeFromString<Map<String, String>>(json)
            map[date]
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize ETag map, returning null")
            null
        }
    }

    override suspend fun setETag(date: String, etag: String) {
        dataStore.edit { prefs ->
            val existingJson = prefs[FOOD_LOG_ETAGS]
            val existingMap = if (existingJson != null) {
                try {
                    Json.decodeFromString<Map<String, String>>(existingJson)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to deserialize ETag map, starting fresh")
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val cutoff = LocalDate.now().minusDays(7)
            val pruned = existingMap.filter { (key, _) ->
                try {
                    !LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE).isBefore(cutoff)
                } catch (e: DateTimeParseException) {
                    false
                }
            }.toMutableMap()

            pruned[date] = etag
            prefs[FOOD_LOG_ETAGS] = Json.encodeToString(pruned)
        }
    }

    override suspend fun isConfigured(): Boolean {
        migrateIfNeeded()
        return withContext(Dispatchers.IO) {
            val apiKey = encryptedPrefs?.getString(ENCRYPTED_API_KEY, "") ?: ""
            val prefs = dataStore.data.first()
            val baseUrl = prefs[BASE_URL] ?: ""
            apiKey.isNotEmpty() && baseUrl.isNotEmpty()
        }
    }

    override suspend fun getDirectPushedGlucoseTimestamps(): Set<Long> {
        val prefs = dataStore.data.first()
        val json = prefs[DIRECT_PUSHED_GLUCOSE_TIMESTAMPS] ?: return emptySet()
        return try {
            Json.decodeFromString<Set<Long>>(json)
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize direct pushed glucose timestamps, returning empty set")
            emptySet()
        }
    }

    override suspend fun addDirectPushedGlucoseTimestamp(timestampMs: Long) {
        dataStore.edit { prefs ->
            val existing = prefs[DIRECT_PUSHED_GLUCOSE_TIMESTAMPS]?.let {
                try {
                    Json.decodeFromString<Set<Long>>(it)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to deserialize direct pushed glucose timestamps, starting fresh")
                    emptySet()
                }
            } ?: emptySet()
            prefs[DIRECT_PUSHED_GLUCOSE_TIMESTAMPS] = Json.encodeToString(existing + timestampMs)
        }
    }

    override suspend fun getDirectPushedBpTimestamps(): Set<Long> {
        val prefs = dataStore.data.first()
        val json = prefs[DIRECT_PUSHED_BP_TIMESTAMPS] ?: return emptySet()
        return try {
            Json.decodeFromString<Set<Long>>(json)
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize direct pushed BP timestamps, returning empty set")
            emptySet()
        }
    }

    override suspend fun addDirectPushedBpTimestamp(timestampMs: Long) {
        dataStore.edit { prefs ->
            val existing = prefs[DIRECT_PUSHED_BP_TIMESTAMPS]?.let {
                try {
                    Json.decodeFromString<Set<Long>>(it)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to deserialize direct pushed BP timestamps, starting fresh")
                    emptySet()
                }
            } ?: emptySet()
            prefs[DIRECT_PUSHED_BP_TIMESTAMPS] = Json.encodeToString(existing + timestampMs)
        }
    }

    override suspend fun pruneDirectPushedTimestamps(glucoseBeforeMs: Long, bpBeforeMs: Long) {
        if (glucoseBeforeMs == 0L && bpBeforeMs == 0L) return
        dataStore.edit { prefs ->
            if (glucoseBeforeMs > 0L) {
                val existing = prefs[DIRECT_PUSHED_GLUCOSE_TIMESTAMPS]?.let {
                    try {
                        Json.decodeFromString<Set<Long>>(it)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to deserialize direct pushed glucose timestamps during prune")
                        emptySet()
                    }
                } ?: emptySet()
                val pruned = existing.filter { it >= glucoseBeforeMs }.toSet()
                prefs[DIRECT_PUSHED_GLUCOSE_TIMESTAMPS] = Json.encodeToString(pruned)
            }
            if (bpBeforeMs > 0L) {
                val existing = prefs[DIRECT_PUSHED_BP_TIMESTAMPS]?.let {
                    try {
                        Json.decodeFromString<Set<Long>>(it)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to deserialize direct pushed BP timestamps during prune")
                        emptySet()
                    }
                } ?: emptySet()
                val pruned = existing.filter { it >= bpBeforeMs }.toSet()
                prefs[DIRECT_PUSHED_BP_TIMESTAMPS] = Json.encodeToString(pruned)
            }
        }
    }
}
