package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.HydrationRecord
import com.healthhelper.app.domain.model.HydrationReading

fun mapToHydrationReading(record: HydrationRecord): HydrationReading =
    HydrationReading(
        volumeMl = record.volume.inMilliliters.toInt(),
        timestamp = record.startTime,
        zoneOffset = record.startZoneOffset,
    )
