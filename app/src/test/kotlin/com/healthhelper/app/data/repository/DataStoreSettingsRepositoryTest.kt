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

    // --- lastHealthReadingsSyncTimestamp tests ---

    @Test
    @DisplayName("lastHealthReadingsSyncTimestampFlow defaults to 0L")
    fun defaultLastHealthReadingsSyncTimestamp() = testScope.runTest {
        assertEquals(0L, repository.lastHealthReadingsSyncTimestampFlow.first())
    }

    @Test
    @DisplayName("setLastHealthReadingsSyncTimestamp stores value and can be read back")
    fun storeAndRetrieveLastHealthReadingsSyncTimestamp() = testScope.runTest {
        repository.setLastHealthReadingsSyncTimestamp(1_711_700_000_000L)
        assertEquals(1_711_700_000_000L, repository.lastHealthReadingsSyncTimestampFlow.first())
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
