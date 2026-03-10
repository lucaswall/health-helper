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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ETagStorageTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSettingsRepository

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
        ) { File(tempDir, "test_etag_settings.preferences_pb") }
        repository = DataStoreSettingsRepository(dataStore, encryptedPrefs)
    }

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val twoDaysAgo = LocalDate.now().minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Test
    @DisplayName("getETag returns null when no ETag stored")
    fun getETagReturnsNullWhenNoETagStored() = testScope.runTest {
        assertNull(repository.getETag(today))
    }

    @Test
    @DisplayName("setETag then getETag returns stored value")
    fun setETagThenGetETagReturnsStoredValue() = testScope.runTest {
        repository.setETag(today, "\"abc123\"")
        assertEquals("\"abc123\"", repository.getETag(today))
    }

    @Test
    @DisplayName("setETag overwrites previous ETag for same date")
    fun setETagOverwritesPreviousETagForSameDate() = testScope.runTest {
        repository.setETag(today, "\"abc123\"")
        repository.setETag(today, "\"def456\"")
        assertEquals("\"def456\"", repository.getETag(today))
    }

    @Test
    @DisplayName("setETag prunes entries older than 7 days")
    fun setETagPrunesEntriesOlderThan7Days() = testScope.runTest {
        val today = LocalDate.now()
        val recentDate = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val oldDate = today.minusDays(18).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Pre-populate with an old entry via raw DataStore write
        val key = stringPreferencesKey("food_log_etags")
        dataStore.edit {
            it[key] = """{"$oldDate":"\"old\"","$recentDate":"\"recent\""}"""
        }
        // Re-create repo to pick up the data
        repository = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        // setETag for today should prune the old entry
        repository.setETag(todayStr, "\"new\"")

        assertNull(repository.getETag(oldDate))
        assertEquals("\"recent\"", repository.getETag(recentDate))
        assertEquals("\"new\"", repository.getETag(todayStr))
    }

    @Test
    @DisplayName("getETag with malformed JSON in DataStore returns null")
    fun getETagWithMalformedJsonReturnsNull() = testScope.runTest {
        val key = stringPreferencesKey("food_log_etags")
        dataStore.edit { it[key] = "this is not valid json{{{" }
        repository = DataStoreSettingsRepository(dataStore, encryptedPrefs)

        assertNull(repository.getETag(today))
    }

    @Test
    @DisplayName("ETags for different dates are independent")
    fun eTagsForDifferentDatesAreIndependent() = testScope.runTest {
        repository.setETag(yesterday, "\"etag_yesterday\"")
        repository.setETag(today, "\"etag_today\"")

        assertEquals("\"etag_yesterday\"", repository.getETag(yesterday))
        assertEquals("\"etag_today\"", repository.getETag(today))
        assertNull(repository.getETag(twoDaysAgo))
    }
}
