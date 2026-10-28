package com.healthhelper.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.healthhelper.app.di.WaistDataStore
import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.repository.WaistReadingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import timber.log.Timber
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Waist readings live here until Food Scanner accepts them — the phone holds the only copy, so
 * nothing is ever discarded: entries that can't be read (format change, bad value) are moved to a
 * preserved key instead of being overwritten, and readings Food Scanner rejects are set aside.
 */
class DataStoreWaistReadingRepository @Inject constructor(
    @param:WaistDataStore private val dataStore: DataStore<Preferences>,
) : WaistReadingRepository {

    @Serializable
    private data class StoredWaist(
        val measuredAtMs: Long,
        val valueCm: Double,
        val zoneOffset: String? = null,
    )

    /** Readable entries plus the raw JSON of anything that couldn't be read. */
    private data class Parsed(val entries: List<StoredWaist>, val unreadable: List<JsonElement>)

    private val json = Json { ignoreUnknownKeys = true }

    override val lastReadingFlow: Flow<WaistReading?> =
        dataStore.data.map { prefs -> prefs[LAST]?.let(::decodeOne)?.toDomain() }

    override val pendingCountFlow: Flow<Int> =
        dataStore.data.map { prefs -> parse(prefs[PENDING]).entries.count { it.toDomain() != null } }

    override val rejectedCountFlow: Flow<Int> =
        dataStore.data.map { prefs -> parse(prefs[REJECTED]).entries.size }

    override suspend fun save(reading: WaistReading) {
        val stored = reading.toStored()
        dataStore.edit { prefs ->
            val pending = readForWrite(prefs, PENDING).filterNot { it.measuredAtMs == stored.measuredAtMs }
            prefs[PENDING] = encode(pending + stored)
            val last = prefs[LAST]?.let(::decodeOne)
            if (last == null || stored.measuredAtMs >= last.measuredAtMs) {
                prefs[LAST] = json.encodeToString(StoredWaist.serializer(), stored)
            }
        }
    }

    override suspend fun getPending(): List<WaistReading> =
        parse(dataStore.data.first()[PENDING]).entries.mapNotNull { it.toDomain() }

    override suspend fun markSynced(timestampsMs: Set<Long>) {
        if (timestampsMs.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[PENDING] = encode(readForWrite(prefs, PENDING).filterNot { it.measuredAtMs in timestampsMs })
        }
    }

    override suspend fun markRejected(timestampMs: Long) {
        dataStore.edit { prefs ->
            val pending = readForWrite(prefs, PENDING)
            val rejected = pending.filter { it.measuredAtMs == timestampMs }
            if (rejected.isEmpty()) return@edit
            prefs[PENDING] = encode(pending - rejected.toSet())
            prefs[REJECTED] = encode(readForWrite(prefs, REJECTED) + rejected)
        }
    }

    /**
     * Reads a list for rewriting. Anything unreadable is appended to [UNREADABLE] first, so the
     * write that follows can never destroy data the phone alone holds.
     */
    private fun readForWrite(prefs: MutablePreferences, key: Preferences.Key<String>): List<StoredWaist> {
        val parsed = parse(prefs[key])
        if (parsed.unreadable.isNotEmpty()) {
            val kept = (prefs[UNREADABLE]?.let(::parseArrayOrNull) ?: emptyList()) + parsed.unreadable
            prefs[UNREADABLE] = JsonArray(kept).toString()
            Timber.e("Preserved %d unreadable waist entr(y/ies) from %s", parsed.unreadable.size, key.name)
        }
        return parsed.entries
    }

    private fun parse(raw: String?): Parsed {
        if (raw == null) return Parsed(emptyList(), emptyList())
        val elements = parseArrayOrNull(raw)
            ?: return Parsed(emptyList(), listOf(JsonPrimitive(raw)))
        val entries = mutableListOf<StoredWaist>()
        val unreadable = mutableListOf<JsonElement>()
        elements.forEach { element ->
            try {
                entries += json.decodeFromJsonElement<StoredWaist>(element)
            } catch (e: IllegalArgumentException) {
                unreadable += element
            }
        }
        return Parsed(entries, unreadable)
    }

    private fun parseArrayOrNull(raw: String): List<JsonElement>? = try {
        json.parseToJsonElement(raw) as? JsonArray
    } catch (e: IllegalArgumentException) {
        // Message omitted on purpose: it would embed stored health values.
        Timber.w("Waist data is not a JSON array (%s)", e::class.simpleName)
        null
    }

    private fun encode(entries: List<StoredWaist>): String =
        JsonArray(entries.map { json.encodeToJsonElement(it) }).toString()

    private fun decodeOne(raw: String): StoredWaist? = try {
        json.decodeFromString(StoredWaist.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        Timber.w("Last waist reading unreadable (%s)", e::class.simpleName)
        null
    }

    private fun WaistReading.toStored() = StoredWaist(
        measuredAtMs = timestamp.toEpochMilli(),
        valueCm = valueCm,
        zoneOffset = zoneOffset?.id,
    )

    /** Null for entries this version can't use (e.g. a value outside today's range); they stay stored. */
    private fun StoredWaist.toDomain(): WaistReading? = try {
        WaistReading(
            valueCm = valueCm,
            timestamp = Instant.ofEpochMilli(measuredAtMs),
            zoneOffset = zoneOffset?.let(ZoneOffset::of),
        )
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: DateTimeException) {
        null
    }

    private companion object {
        val PENDING = stringPreferencesKey("waist_pending_readings")
        val REJECTED = stringPreferencesKey("waist_rejected_readings")
        val UNREADABLE = stringPreferencesKey("waist_unreadable_entries")
        val LAST = stringPreferencesKey("waist_last_reading")
    }
}
