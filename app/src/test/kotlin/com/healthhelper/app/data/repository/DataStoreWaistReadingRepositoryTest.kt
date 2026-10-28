package com.healthhelper.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.domain.model.WaistReading
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
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreWaistReadingRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreWaistReadingRepository

    private val t1 = Instant.parse("2026-10-20T11:00:00Z")
    private val t2 = Instant.parse("2026-10-27T11:00:00Z")

    @BeforeEach
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = testScope) { File(tempDir, "waist.preferences_pb") }
        repository = DataStoreWaistReadingRepository(dataStore)
    }

    @Test
    @DisplayName("starts empty")
    fun startsEmpty() = testScope.runTest {
        assertNull(repository.lastReadingFlow.first())
        assertEquals(0, repository.pendingCountFlow.first())
        assertEquals(emptyList(), repository.getPending())
    }

    @Test
    @DisplayName("save keeps the reading pending and as the last reading, including its offset")
    fun saveKeepsPendingAndLast() = testScope.runTest {
        val reading = WaistReading(valueCm = 104.5, timestamp = t1, zoneOffset = ZoneOffset.ofHours(-3))

        repository.save(reading)

        assertEquals(listOf(reading), repository.getPending())
        assertEquals(reading, repository.lastReadingFlow.first())
        assertEquals(1, repository.pendingCountFlow.first())
    }

    @Test
    @DisplayName("an older reading does not replace the last reading")
    fun olderReadingKeepsLast() = testScope.runTest {
        repository.save(WaistReading(valueCm = 103.0, timestamp = t2))
        repository.save(WaistReading(valueCm = 106.0, timestamp = t1))

        assertEquals(103.0, repository.lastReadingFlow.first()?.valueCm)
        assertEquals(2, repository.getPending().size)
    }

    @Test
    @DisplayName("saving the same measurement time again replaces the pending entry")
    fun sameTimestampReplaces() = testScope.runTest {
        repository.save(WaistReading(valueCm = 104.0, timestamp = t1))
        repository.save(WaistReading(valueCm = 104.5, timestamp = t1))

        assertEquals(listOf(104.5), repository.getPending().map { it.valueCm })
    }

    @Test
    @DisplayName("markSynced removes only the sent readings and keeps the last reading")
    fun markSyncedRemovesSentOnly() = testScope.runTest {
        repository.save(WaistReading(valueCm = 104.0, timestamp = t1))
        repository.save(WaistReading(valueCm = 103.5, timestamp = t2))

        repository.markSynced(setOf(t1.toEpochMilli()))

        assertEquals(listOf(t2), repository.getPending().map { it.timestamp })
        assertEquals(103.5, repository.lastReadingFlow.first()?.valueCm)
    }

    @Test
    @DisplayName("a corrupt pending list reads as empty instead of crashing")
    fun corruptPendingIsEmpty() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("waist_pending_readings")] = "not json{" }

        assertEquals(emptyList(), repository.getPending())
        assertEquals(0, repository.pendingCountFlow.first())
    }

    @Test
    @DisplayName("saving over unreadable pending data preserves the raw data instead of overwriting it")
    fun saveOverCorruptPreservesRaw() = testScope.runTest {
        dataStore.edit { it[stringPreferencesKey("waist_pending_readings")] = "not json{" }

        repository.save(WaistReading(valueCm = 104.5, timestamp = t1))

        val prefs = dataStore.data.first()
        assertEquals(listOf(104.5), repository.getPending().map { it.valueCm })
        val preserved = prefs[stringPreferencesKey("waist_unreadable_entries")]!!
        kotlin.test.assertTrue(preserved.contains("not json{"), preserved)
    }

    @Test
    @DisplayName("one unreadable entry is preserved while the readable ones keep syncing")
    fun partialCorruptionKeepsGoodEntries() = testScope.runTest {
        dataStore.edit {
            it[stringPreferencesKey("waist_pending_readings")] =
                """[{"measuredAtMs":${t1.toEpochMilli()},"valueCm":104.5},{"measuredAtMs":"oops"}]"""
        }

        assertEquals(listOf(104.5), repository.getPending().map { it.valueCm })
        repository.markSynced(setOf(t1.toEpochMilli()))

        assertEquals(emptyList(), repository.getPending())
        val preserved = dataStore.data.first()[stringPreferencesKey("waist_unreadable_entries")]!!
        kotlin.test.assertTrue(preserved.contains("oops"), preserved)
    }

    @Test
    @DisplayName("entries written by a newer version with extra fields are still read")
    fun unknownFieldsIgnored() = testScope.runTest {
        dataStore.edit {
            it[stringPreferencesKey("waist_pending_readings")] =
                """[{"measuredAtMs":${t1.toEpochMilli()},"valueCm":104.5,"site":"who_midpoint"}]"""
        }

        assertEquals(listOf(104.5), repository.getPending().map { it.valueCm })
    }

    @Test
    @DisplayName("markRejected moves a reading aside and counts it")
    fun markRejectedMovesAside() = testScope.runTest {
        repository.save(WaistReading(valueCm = 104.0, timestamp = t1))
        repository.save(WaistReading(valueCm = 103.5, timestamp = t2))

        repository.markRejected(t1.toEpochMilli())

        assertEquals(listOf(t2), repository.getPending().map { it.timestamp })
        assertEquals(1, repository.rejectedCountFlow.first())
        assertEquals(1, repository.pendingCountFlow.first())
    }

    @Test
    @DisplayName("an entry this version can't use stays stored but is not counted or sent")
    fun unusableEntryNotCounted() = testScope.runTest {
        dataStore.edit {
            it[stringPreferencesKey("waist_pending_readings")] =
                """[{"measuredAtMs":${t1.toEpochMilli()},"valueCm":999.0},{"measuredAtMs":${t2.toEpochMilli()},"valueCm":103.0}]"""
        }

        assertEquals(1, repository.pendingCountFlow.first())
        assertEquals(listOf(103.0), repository.getPending().map { it.valueCm })
        repository.markSynced(setOf(t2.toEpochMilli()))
        val raw = dataStore.data.first()[stringPreferencesKey("waist_pending_readings")]!!
        kotlin.test.assertTrue(raw.contains("999.0"), raw)
    }
}
