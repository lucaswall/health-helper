package com.healthhelper.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSettingsRepository

    @BeforeEach
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
        ) { File(tempDir, "test_settings.preferences_pb") }
        repository = DataStoreSettingsRepository(dataStore)
    }

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
    @DisplayName("default sync interval is 10")
    fun defaultSyncInterval() = testScope.runTest {
        assertEquals(10, repository.syncIntervalFlow.first())
    }

    @Test
    @DisplayName("default last synced date is empty string")
    fun defaultLastSyncedDate() = testScope.runTest {
        assertEquals("", repository.lastSyncedDateFlow.first())
    }

    @Test
    @DisplayName("stores and retrieves API key")
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
