package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.HydrationRecord
import com.healthhelper.app.domain.model.HydrationReading
import kotlin.math.roundToInt

fun mapToHydrationReading(record: HydrationRecord): HydrationReading =
    HydrationReading(
        volumeMl = record.volume.inMilliliters.roundToInt(),
        timestamp = record.startTime,
        zoneOffset = record.startZoneOffset,
    )
