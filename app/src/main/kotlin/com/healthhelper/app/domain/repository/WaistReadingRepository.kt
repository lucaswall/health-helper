package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.WaistReading
import kotlinx.coroutines.flow.Flow

/**
 * On-device store for waist readings. Health Connect has no waist record type, so this is the
 * only local copy: a reading is saved here first and stays pending until Food Scanner (the
 * source of truth) has accepted it, so nothing is lost when the phone is offline.
 */
interface WaistReadingRepository {
    /** Most recently measured reading saved on this phone, or null if none. */
    val lastReadingFlow: Flow<WaistReading?>

    /** Number of readings not yet accepted by Food Scanner. */
    val pendingCountFlow: Flow<Int>

    /** Readings Food Scanner refused as invalid; kept on the phone, never retried automatically. */
    val rejectedCountFlow: Flow<Int>

    /** Saves [reading] as pending (and as the last reading when it is the newest). */
    suspend fun save(reading: WaistReading)

    suspend fun getPending(): List<WaistReading>

    /** Removes the pending readings measured at [timestampsMs] (epoch millis). */
    suspend fun markSynced(timestampsMs: Set<Long>)

    /** Moves the pending reading measured at [timestampMs] aside so it can't block the others. */
    suspend fun markRejected(timestampMs: Long)
}
