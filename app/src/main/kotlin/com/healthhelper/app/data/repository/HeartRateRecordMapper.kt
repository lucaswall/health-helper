package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.healthhelper.app.domain.model.HeartRateReading
import java.time.ZoneId

/**
 * Maps a spot pulse measurement to a single-sample [HeartRateRecord]. HC heart rate is a series
 * record, so the reading becomes a 1-second interval holding one sample at the measurement time
 * (the platform rejects intervals whose end isn't after their start).
 */
fun mapToHeartRateRecord(reading: HeartRateReading): HeartRateRecord {
    val zoneOffset = reading.zoneOffset ?: ZoneId.systemDefault().rules.getOffset(reading.timestamp)
    return HeartRateRecord(
        startTime = reading.timestamp,
        startZoneOffset = zoneOffset,
        endTime = reading.timestamp.plusSeconds(1),
        endZoneOffset = zoneOffset,
        samples = listOf(
            HeartRateRecord.Sample(
                time = reading.timestamp,
                beatsPerMinute = reading.beatsPerMinute.toLong(),
            ),
        ),
        metadata = Metadata.manualEntry(
            clientRecordId = "heartrate-${reading.timestamp.toEpochMilli()}",
        ),
    )
}
