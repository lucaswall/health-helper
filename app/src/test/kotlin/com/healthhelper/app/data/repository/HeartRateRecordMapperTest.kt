package com.healthhelper.app.data.repository

import com.healthhelper.app.domain.model.HeartRateReading
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartRateRecordMapperTest {

    private val timestamp = Instant.parse("2026-10-28T09:15:00Z")

    @Test
    @DisplayName("maps bpm to a single sample at the measurement time")
    fun mapsSingleSample() {
        val record = mapToHeartRateRecord(HeartRateReading(beatsPerMinute = 72, timestamp = timestamp))

        assertEquals(1, record.samples.size)
        assertEquals(72L, record.samples.single().beatsPerMinute)
        assertEquals(timestamp, record.samples.single().time)
    }

    @Test
    @DisplayName("record spans one second starting at the measurement time")
    fun spansOneSecond() {
        val record = mapToHeartRateRecord(HeartRateReading(beatsPerMinute = 72, timestamp = timestamp))

        assertEquals(timestamp, record.startTime)
        assertEquals(timestamp.plusSeconds(1), record.endTime)
        assertTrue(record.endTime.isAfter(record.startTime))
    }

    @Test
    @DisplayName("uses the reading's zone offset when present")
    fun usesReadingZoneOffset() {
        val offset = ZoneOffset.ofHours(-3)
        val record = mapToHeartRateRecord(
            HeartRateReading(beatsPerMinute = 72, timestamp = timestamp, zoneOffset = offset),
        )

        assertEquals(offset, record.startZoneOffset)
        assertEquals(offset, record.endZoneOffset)
    }

    @Test
    @DisplayName("clientRecordId is derived from the timestamp so re-saves upsert")
    fun clientRecordIdFromTimestamp() {
        val record = mapToHeartRateRecord(HeartRateReading(beatsPerMinute = 72, timestamp = timestamp))

        assertEquals("heartrate-${timestamp.toEpochMilli()}", record.metadata.clientRecordId)
    }
}
