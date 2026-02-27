package com.healthhelper.app.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlin.test.assertEquals
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
    @DisplayName("migration skips if encryptedPrefs already has the key")
    fun migrationSkipsIfEncryptedPrefsAlreadyHasKey() = testScope.runTest {
        // Pre-populate both DataStore and encryptedPrefs
        dataStore.edit { it[stringPreferencesKey("api_key")] = "datastore_key" }
        encryptedStore["api_key"] = "encrypted_key"

        val freshRepo = DataStoreSettingsRepository(dataStore, encryptedPrefs)
        val key = freshRepo.apiKeyFlow.first()

        // Should use encryptedPrefs value, DataStore not touched
        assertEquals("encrypted_key", key)
        val prefs = dataStore.data.first()
        assertEquals("datastore_key", prefs[stringPreferencesKey("api_key")])
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
    @DisplayName("default sync interval is 5")
    fun defaultSyncInterval() = testScope.runTest {
        assertEquals(5, repository.syncIntervalFlow.first())
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
}
