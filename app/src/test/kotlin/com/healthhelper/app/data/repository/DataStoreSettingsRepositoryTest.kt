package com.healthhelper.app.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncedMealSummary
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSettingsRepository

    // In-memory store for encrypted prefs mock
    private val encryptedStore = mutableMapOf<String, String>()
    private val encryptedPrefs = mockk<SharedPreferences>()
    private val encryptedEditor = mockk<SharedPreferences.Editor>()

    @BeforeEach
    fun setUp() {
        encryptedStore.clear()

        every { encryptedPrefs.getString(any(), any<String>()) } answers {
            encryptedStore[firstArg()] ?: (arg<String>(1))
        }
        every { encryptedPrefs.edit() } returns encryptedEditor
        every { encryptedEditor.putString(any(), any()) } answers {
            encryptedStore[firstArg()] = secondArg()
            encryptedEditor
        }
        every { encryptedEditor.remove(any()) } answers {
            encryptedStore.remove(firstArg())
            encryptedEditor
        }
        every { encryptedEditor.apply() } just Runs
        every { encryptedEditor.commit() } returns true
        every { encryptedPrefs.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { encryptedPrefs.unregisterOnSharedPreferenceChangeListener(any()) } just Runs

        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
        ) { File(tempDir, "test_settings.preferences_pb") }
        repository = DataStoreSettingsRepository(dataStore, encryptedPrefs)
    }

    // --- apiKey (encrypted prefs) tests ---

    @Test
    @DisplayName("apiKeyFlow reads from encryptedPrefs not DataStore")
    fun apiKeyFlowReadsFromEncryptedPrefs() = testScope.runTest {
        encryptedStore["api_key"] = "encrypted_key"
        assertEquals("encrypted_key", repository.apiKeyFlow.first())
    }

    @Test
    @DisplayName("setApiKey writes to encryptedPrefs with commit for durability")
    fun setApiKeyWritesToEncryptedPrefs() = testScope.runTest {
        repository.setApiKey("fsk_abc123")
        verify { encryptedEditor.putString("api_key", "fsk_abc123") }
        verify { encryptedEditor.commit() }
    }

    @Test
    @DisplayName("migration moves apiKey from DataStore to encryptedPrefs")
    fun migrationMovesApiKeyFromDataStore() = testScope.runTest {
        // Pre-populate DataStore with a legacy api_key
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }

        // Create fresh repo with empty encryptedPrefs
        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        // Trigger migration by collecting apiKeyFlow
        val key = freshRepo.apiKeyFlow.first()

        // Migration should have moved the key
        assertEquals("legacy_key", key)
        assertEquals("legacy_key", encryptedStore["api_key"])

        // DataStore should no longer have the api_key
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("api_key")])
    }

    @Test
    @DisplayName("migration cleans residual plaintext key from DataStore when encryptedPrefs already has key")
    fun migrationCleansResidualPlaintextKey() = testScope.runTest {
        // Simulate crash recovery: EncryptedPrefs has the key (write succeeded)
        // but DataStore still has the plaintext key (remove didn't complete)
        dataStore.edit { it[stringPreferencesKey("api_key")] = "datastore_key" }
        encryptedStore["api_key"] = "encrypted_key"

        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)
        val key = freshRepo.apiKeyFlow.first()

        // Should use encryptedPrefs value
        assertEquals("encrypted_key", key)
        // DataStore plaintext key should be cleaned up
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("api_key")])
    }

    @Test
    @DisplayName("migration does not mark complete when verification fails")
    fun migrationDoesNotCompleteOnVerificationFailure() = testScope.runTest {
        // Pre-populate DataStore with a legacy api_key
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }

        // Configure mock to return wrong value on read-back (simulate write failure)
        var writeCount = 0
        every { encryptedPrefs.getString("api_key", any<String>()) } answers {
            // First call in migrateIfNeeded checks if key exists → return empty
            // Second call is verification → return wrong value
            if (writeCount++ == 0) "" else "wrong_value"
        }

        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        // Trigger migration
        freshRepo.apiKeyFlow.first()

        // DataStore should still have the legacy key (not cleared)
        val prefs = dataStore.data.first()
        assertEquals("legacy_key", prefs[stringPreferencesKey("api_key")])
    }

    // --- Existing tests (adapted for new constructor + behavior) ---

    @Test
    @DisplayName("default API key is empty string")
    fun defaultApiKey() = testScope.runTest {
        assertEquals("", repository.apiKeyFlow.first())
    }

    @Test
    @DisplayName("default base URL is empty string")
    fun defaultBaseUrl() = testScope.runTest {
        assertEquals("", repository.baseUrlFlow.first())
    }

    @Test
    @DisplayName("default sync interval is 15")
    fun defaultSyncInterval() = testScope.runTest {
        assertEquals(15, repository.syncIntervalFlow.first())
    }

    @Test
    @DisplayName("default last synced date is empty string")
    fun defaultLastSyncedDate() = testScope.runTest {
        assertEquals("", repository.lastSyncedDateFlow.first())
    }

    @Test
    @DisplayName("stores and retrieves API key via encryptedPrefs")
    fun storeAndRetrieveApiKey() = testScope.runTest {
        repository.setApiKey("fsk_abc123")
        assertEquals("fsk_abc123", repository.apiKeyFlow.first())
    }

    @Test
    @DisplayName("stores and retrieves base URL")
    fun storeAndRetrieveBaseUrl() = testScope.runTest {
        repository.setBaseUrl("https://food.example.com")
        assertEquals("https://food.example.com", repository.baseUrlFlow.first())
    }

    @Test
    @DisplayName("stores and retrieves sync interval")
    fun storeAndRetrieveSyncInterval() = testScope.runTest {
        repository.setSyncInterval(30)
        assertEquals(30, repository.syncIntervalFlow.first())
    }

    @Test
    @DisplayName("stores and retrieves last synced date")
    fun storeAndRetrieveLastSyncedDate() = testScope.runTest {
        repository.setLastSyncedDate("2026-02-27")
        assertEquals("2026-02-27", repository.lastSyncedDateFlow.first())
    }

    @Test
    @DisplayName("isConfigured returns false when API key is empty")
    fun notConfiguredWithoutApiKey() = testScope.runTest {
        repository.setBaseUrl("https://food.example.com")
        assertFalse(repository.isConfigured())
    }

    @Test
    @DisplayName("isConfigured returns false when base URL is empty")
    fun notConfiguredWithoutBaseUrl() = testScope.runTest {
        repository.setApiKey("fsk_abc123")
        assertFalse(repository.isConfigured())
    }

    @Test
    @DisplayName("isConfigured returns true when both API key and base URL are set")
    fun configuredWhenBothSet() = testScope.runTest {
        repository.setApiKey("fsk_abc123")
        repository.setBaseUrl("https://food.example.com")
        assertTrue(repository.isConfigured())
    }

    @Test
    @DisplayName("isConfigured returns false when both are empty")
    fun notConfiguredWhenBothEmpty() = testScope.runTest {
        assertFalse(repository.isConfigured())
    }

    @Test
    @DisplayName("lastSyncTimestampFlow emits 0L by default")
    fun defaultLastSyncTimestamp() = testScope.runTest {
        assertEquals(0L, repository.lastSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("setLastSyncTimestamp stores value and emits it")
    fun storeAndRetrieveLastSyncTimestamp() = testScope.runTest {
        repository.setLastSyncTimestamp(1_000_000L)
        assertEquals(1_000_000L, repository.lastSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("isConfigured returns true when API key exists only in DataStore (pre-migration)")
    fun isConfiguredReturnsTruePreMigration() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }
        repository = DataStoreSettingsRepository(dataStore, encryptedPrefs)
        repository.setBaseUrl("https://food.example.com")
        assertTrue(repository.isConfigured())
    }

    // --- lastSyncedMeals tests ---

    @Test
    @DisplayName("lastSyncedMealsFlow emits empty list by default")
    fun lastSyncedMealsFlowEmitsEmptyListByDefault() = testScope.runTest {
        assertEquals(emptyList(), repository.lastSyncedMealsFlow.first())
    }

    @Test
    @DisplayName("setLastSyncedMeals stores meals and emits them")
    fun setLastSyncedMealsStoresMealsAndEmitsThem() = testScope.runTest {
        val meals = listOf(
            SyncedMealSummary(foodName = "Oatmeal", mealType = MealType.BREAKFAST, calories = 300),
            SyncedMealSummary(foodName = "Salad", mealType = MealType.LUNCH, calories = 450),
        )
        repository.setLastSyncedMeals(meals)
        val result = repository.lastSyncedMealsFlow.first()
        assertEquals(2, result.size)
        assertEquals("Oatmeal", result[0].foodName)
        assertEquals(MealType.BREAKFAST, result[0].mealType)
        assertEquals(300, result[0].calories)
        assertEquals("Salad", result[1].foodName)
        assertEquals(MealType.LUNCH, result[1].mealType)
        assertEquals(450, result[1].calories)
    }

    @Test
    @DisplayName("setLastSyncedMeals with empty list clears stored meals")
    fun setLastSyncedMealsWithEmptyListClearsStoredMeals() = testScope.runTest {
        val meals = listOf(
            SyncedMealSummary(foodName = "Pizza", mealType = MealType.DINNER, calories = 800),
        )
        repository.setLastSyncedMeals(meals)
        repository.setLastSyncedMeals(emptyList())
        assertEquals(emptyList(), repository.lastSyncedMealsFlow.first())
    }

    // --- anthropicApiKey (encrypted prefs) tests ---

    @Test
    @DisplayName("default anthropic API key is empty string")
    fun defaultAnthropicApiKey() = testScope.runTest {
        assertEquals("", repository.anthropicApiKeyFlow.first())
    }

    @Test
    @DisplayName("anthropicApiKeyFlow reads from encryptedPrefs")
    fun anthropicApiKeyFlowReadsFromEncryptedPrefs() = testScope.runTest {
        encryptedStore["anthropic_api_key"] = "sk-ant-test"
        assertEquals("sk-ant-test", repository.anthropicApiKeyFlow.first())
    }

    @Test
    @DisplayName("setAnthropicApiKey writes to encryptedPrefs with commit for durability")
    fun setAnthropicApiKeyWritesToEncryptedPrefs() = testScope.runTest {
        repository.setAnthropicApiKey("sk-ant-abc123")
        verify { encryptedEditor.putString("anthropic_api_key", "sk-ant-abc123") }
        verify { encryptedEditor.commit() }
    }

    // --- null encryptedPrefs tests (Fix 1: HEA-110) ---

    @Test
    @DisplayName("apiKeyFlow emits empty string when encryptedPrefs is null")
    fun apiKeyFlowEmitsEmptyWhenEncryptedPrefsNull() = testScope.runTest {
        val repoNullPrefs = DataStoreSettingsRepository(dataStore, null)
        assertEquals("", repoNullPrefs.apiKeyFlow.first())
    }

    @Test
    @DisplayName("anthropicApiKeyFlow emits empty string when encryptedPrefs is null")
    fun anthropicApiKeyFlowEmitsEmptyWhenEncryptedPrefsNull() = testScope.runTest {
        val repoNullPrefs = DataStoreSettingsRepository(dataStore, null)
        assertEquals("", repoNullPrefs.anthropicApiKeyFlow.first())
    }

    @Test
    @DisplayName("setApiKey is no-op when encryptedPrefs is null")
    fun setApiKeyIsNoOpWhenEncryptedPrefsNull() = testScope.runTest {
        val repoNullPrefs = DataStoreSettingsRepository(dataStore, null)
        // Should not throw
        repoNullPrefs.setApiKey("should-be-ignored")
        assertEquals("", repoNullPrefs.apiKeyFlow.first())
    }

    @Test
    @DisplayName("setAnthropicApiKey is no-op when encryptedPrefs is null")
    fun setAnthropicApiKeyIsNoOpWhenEncryptedPrefsNull() = testScope.runTest {
        val repoNullPrefs = DataStoreSettingsRepository(dataStore, null)
        // Should not throw
        repoNullPrefs.setAnthropicApiKey("should-be-ignored")
        assertEquals("", repoNullPrefs.anthropicApiKeyFlow.first())
    }

    @Test
    @DisplayName("migration is skipped when encryptedPrefs is null")
    fun migrationSkippedWhenEncryptedPrefsNull() = testScope.runTest {
        // Put a legacy key in DataStore
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }
        val repoNullPrefs = DataStoreSettingsRepository(dataStore, null)
        // Should not crash, returns empty
        assertEquals("", repoNullPrefs.apiKeyFlow.first())
        // Legacy key remains in DataStore (migration was skipped)
        val prefs = dataStore.data.first()
        assertEquals("legacy_key", prefs[stringPreferencesKey("api_key")])
    }

    @Test
    @DisplayName("apiKeyFlow propagates CancellationException from migration instead of swallowing it")
    fun apiKeyFlowPropagatesCancellationException() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }

        // First getString call (in migrateIfNeeded) throws CancellationException
        // Second call (post-migration trySend) would return "" but should never be reached
        var callCount = 0
        every { encryptedPrefs.getString("api_key", any<String>()) } answers {
            callCount++
            if (callCount == 1) throw CancellationException("coroutine cancelled")
            ""
        }

        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        assertFailsWith<CancellationException> {
            freshRepo.apiKeyFlow.first()
        }
    }

    // --- Per-type sync timestamps and status metadata tests ---

    @Test
    @DisplayName("lastGlucoseSyncTimestampFlow emits 0L initially")
    fun lastGlucoseSyncTimestampFlowDefaultsToZero() = testScope.runTest {
        assertEquals(0L, repository.lastGlucoseSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("setLastGlucoseSyncTimestamp stores value and flow emits it")
    fun setLastGlucoseSyncTimestampStoresAndEmits() = testScope.runTest {
        repository.setLastGlucoseSyncTimestamp(1_711_700_000_000L)
        assertEquals(1_711_700_000_000L, repository.lastGlucoseSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("lastBpSyncTimestampFlow emits 0L initially")
    fun lastBpSyncTimestampFlowDefaultsToZero() = testScope.runTest {
        assertEquals(0L, repository.lastBpSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("setLastBpSyncTimestamp stores value and flow emits it")
    fun setLastBpSyncTimestampStoresAndEmits() = testScope.runTest {
        repository.setLastBpSyncTimestamp(1_711_700_000_000L)
        assertEquals(1_711_700_000_000L, repository.lastBpSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("glucoseSyncCountFlow emits 0 initially")
    fun glucoseSyncCountFlowDefaultsToZero() = testScope.runTest {
        assertEquals(0, repository.glucoseSyncCountFlow.first())
    }

    @Test
    @DisplayName("setGlucoseSyncCount stores value and flow emits it")
    fun setGlucoseSyncCountStoresAndEmits() = testScope.runTest {
        repository.setGlucoseSyncCount(42)
        assertEquals(42, repository.glucoseSyncCountFlow.first())
    }

    @Test
    @DisplayName("bpSyncCountFlow emits 0 initially")
    fun bpSyncCountFlowDefaultsToZero() = testScope.runTest {
        assertEquals(0, repository.bpSyncCountFlow.first())
    }

    @Test
    @DisplayName("setBpSyncCount stores value and flow emits it")
    fun setBpSyncCountStoresAndEmits() = testScope.runTest {
        repository.setBpSyncCount(7)
        assertEquals(7, repository.bpSyncCountFlow.first())
    }

    @Test
    @DisplayName("glucoseSyncCaughtUpFlow emits false initially")
    fun glucoseSyncCaughtUpFlowDefaultsToFalse() = testScope.runTest {
        assertEquals(false, repository.glucoseSyncCaughtUpFlow.first())
    }

    @Test
    @DisplayName("setGlucoseSyncCaughtUp(true) stores value and flow emits true")
    fun setGlucoseSyncCaughtUpTrueStoresAndEmits() = testScope.runTest {
        repository.setGlucoseSyncCaughtUp(true)
        assertEquals(true, repository.glucoseSyncCaughtUpFlow.first())
    }

    @Test
    @DisplayName("bpSyncCaughtUpFlow emits false initially")
    fun bpSyncCaughtUpFlowDefaultsToFalse() = testScope.runTest {
        assertEquals(false, repository.bpSyncCaughtUpFlow.first())
    }

    @Test
    @DisplayName("setBpSyncCaughtUp(true) stores value and flow emits true")
    fun setBpSyncCaughtUpTrueStoresAndEmits() = testScope.runTest {
        repository.setBpSyncCaughtUp(true)
        assertEquals(true, repository.bpSyncCaughtUpFlow.first())
    }

    // --- Direct pushed glucose timestamps (ledger) tests ---

    @Test
    @DisplayName("getDirectPushedGlucoseTimestamps returns empty set initially")
    fun directPushedGlucoseTimestampsEmptyInitially() = testScope.runTest {
        assertEquals(emptySet<Long>(), repository.getDirectPushedGlucoseTimestamps())
    }

    @Test
    @DisplayName("addDirectPushedGlucoseTimestamp then get returns set containing the timestamp")
    fun addDirectPushedGlucoseTimestampAndGet() = testScope.runTest {
        val ts = 1_700_000_000_000L
        repository.addDirectPushedGlucoseTimestamp(ts)
        assertTrue(repository.getDirectPushedGlucoseTimestamps().contains(ts))
    }

    @Test
    @DisplayName("multiple addDirectPushedGlucoseTimestamp calls accumulate in the set")
    fun multipleAddDirectPushedGlucoseTimestampsAccumulate() = testScope.runTest {
        val ts1 = 1_700_000_000_000L
        val ts2 = 1_700_000_001_000L
        val ts3 = 1_700_000_002_000L
        repository.addDirectPushedGlucoseTimestamp(ts1)
        repository.addDirectPushedGlucoseTimestamp(ts2)
        repository.addDirectPushedGlucoseTimestamp(ts3)
        assertEquals(setOf(ts1, ts2, ts3), repository.getDirectPushedGlucoseTimestamps())
    }

    @Test
    @DisplayName("pruneDirectPushedTimestamps removes glucose entries older than threshold and keeps newer ones")
    fun pruneDirectPushedGlucoseTimestamps() = testScope.runTest {
        val old = 1_000L
        val recent = 3_000L
        val threshold = 2_000L
        repository.addDirectPushedGlucoseTimestamp(old)
        repository.addDirectPushedGlucoseTimestamp(recent)
        repository.pruneDirectPushedTimestamps(glucoseBeforeMs = threshold, bpBeforeMs = 0L)
        val result = repository.getDirectPushedGlucoseTimestamps()
        assertFalse(result.contains(old))
        assertTrue(result.contains(recent))
    }

    @Test
    @DisplayName("pruneDirectPushedTimestamps on empty glucose set is a no-op")
    fun pruneDirectPushedGlucoseTimestampsOnEmptySetIsNoOp() = testScope.runTest {
        repository.pruneDirectPushedTimestamps(glucoseBeforeMs = 999_999L, bpBeforeMs = 0L)
        assertEquals(emptySet<Long>(), repository.getDirectPushedGlucoseTimestamps())
    }

    // --- Direct pushed BP timestamps (ledger) tests ---

    @Test
    @DisplayName("getDirectPushedBpTimestamps returns empty set initially")
    fun directPushedBpTimestampsEmptyInitially() = testScope.runTest {
        assertEquals(emptySet<Long>(), repository.getDirectPushedBpTimestamps())
    }

    @Test
    @DisplayName("addDirectPushedBpTimestamp then get returns set containing the timestamp")
    fun addDirectPushedBpTimestampAndGet() = testScope.runTest {
        val ts = 1_700_000_000_000L
        repository.addDirectPushedBpTimestamp(ts)
        assertTrue(repository.getDirectPushedBpTimestamps().contains(ts))
    }

    @Test
    @DisplayName("multiple addDirectPushedBpTimestamp calls accumulate in the set")
    fun multipleAddDirectPushedBpTimestampsAccumulate() = testScope.runTest {
        val ts1 = 1_700_000_000_000L
        val ts2 = 1_700_000_001_000L
        val ts3 = 1_700_000_002_000L
        repository.addDirectPushedBpTimestamp(ts1)
        repository.addDirectPushedBpTimestamp(ts2)
        repository.addDirectPushedBpTimestamp(ts3)
        assertEquals(setOf(ts1, ts2, ts3), repository.getDirectPushedBpTimestamps())
    }

    @Test
    @DisplayName("pruneDirectPushedTimestamps removes BP entries older than threshold and keeps newer ones")
    fun pruneDirectPushedBpTimestamps() = testScope.runTest {
        val old = 1_000L
        val recent = 3_000L
        val threshold = 2_000L
        repository.addDirectPushedBpTimestamp(old)
        repository.addDirectPushedBpTimestamp(recent)
        repository.pruneDirectPushedTimestamps(glucoseBeforeMs = 0L, bpBeforeMs = threshold)
        val result = repository.getDirectPushedBpTimestamps()
        assertFalse(result.contains(old))
        assertTrue(result.contains(recent))
    }

    @Test
    @DisplayName("pruneDirectPushedTimestamps on empty BP set is a no-op")
    fun pruneDirectPushedBpTimestampsOnEmptySetIsNoOp() = testScope.runTest {
        repository.pruneDirectPushedTimestamps(glucoseBeforeMs = 0L, bpBeforeMs = 999_999L)
        assertEquals(emptySet<Long>(), repository.getDirectPushedBpTimestamps())
    }

    // --- Corrupted JSON graceful degradation for ledger ---

    @Test
    @DisplayName("corrupted JSON in DataStore returns empty set for glucose timestamps")
    fun corruptedJsonReturnsEmptySetForGlucoseTimestamps() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("direct_pushed_glucose_timestamps")] = "not-valid-json{{{" }
        assertEquals(emptySet<Long>(), repository.getDirectPushedGlucoseTimestamps())
    }

    @Test
    @DisplayName("corrupted JSON in DataStore returns empty set for BP timestamps")
    fun corruptedJsonReturnsEmptySetForBpTimestamps() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("direct_pushed_bp_timestamps")] = "not-valid-json{{{" }
        assertEquals(emptySet<Long>(), repository.getDirectPushedBpTimestamps())
    }

    @Test
    @DisplayName("apiKeyFlow handles migration failure gracefully")
    fun apiKeyFlowHandlesMigrationFailure() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("api_key")] = "legacy_key" }

        // First getString call (migration check) throws, second (post-migration read) returns ""
        var callCount = 0
        every { encryptedPrefs.getString("api_key", any<String>()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("Keystore unavailable")
            ""
        }

        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        // Should not crash — migration failure is caught, returns empty default
        val key = freshRepo.apiKeyFlow.first()
        assertEquals("", key)
        assertEquals(2, callCount, "getString should be called exactly twice: once in migration (throws), once in post-migration read")
    }
}
