package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import java.time.ZoneId
import kotlin.math.roundToInt

fun mapToBloodPressureRecord(reading: BloodPressureReading): BloodPressureRecord {
    val zoneOffset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)

    val bodyPositionInt = when (reading.bodyPosition) {
        BodyPosition.STANDING_UP -> BloodPressureRecord.BODY_POSITION_STANDING_UP
        BodyPosition.SITTING_DOWN -> BloodPressureRecord.BODY_POSITION_SITTING_DOWN
        BodyPosition.LYING_DOWN -> BloodPressureRecord.BODY_POSITION_LYING_DOWN
        BodyPosition.RECLINING -> BloodPressureRecord.BODY_POSITION_RECLINING
        BodyPosition.UNKNOWN -> BloodPressureRecord.BODY_POSITION_UNKNOWN
    }

    val measurementLocationInt = when (reading.measurementLocation) {
        MeasurementLocation.LEFT_UPPER_ARM -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM
        MeasurementLocation.RIGHT_UPPER_ARM -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM
        MeasurementLocation.LEFT_WRIST -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST
        MeasurementLocation.RIGHT_WRIST -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST
        MeasurementLocation.UNKNOWN -> BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
    }

    return BloodPressureRecord(
        time = reading.timestamp,
        zoneOffset = zoneOffset,
        systolic = Pressure.millimetersOfMercury(reading.systolic.toDouble()),
        diastolic = Pressure.millimetersOfMercury(reading.diastolic.toDouble()),
        bodyPosition = bodyPositionInt,
        measurementLocation = measurementLocationInt,
        metadata = Metadata.manualEntry(
            clientRecordId = "bloodpressure-${reading.timestamp.toEpochMilli()}",
        ),
    )
}

fun mapToBloodPressureReading(record: BloodPressureRecord): BloodPressureReading {
    val bodyPosition = when (record.bodyPosition) {
        BloodPressureRecord.BODY_POSITION_STANDING_UP -> BodyPosition.STANDING_UP
        BloodPressureRecord.BODY_POSITION_SITTING_DOWN -> BodyPosition.SITTING_DOWN
        BloodPressureRecord.BODY_POSITION_LYING_DOWN -> BodyPosition.LYING_DOWN
        BloodPressureRecord.BODY_POSITION_RECLINING -> BodyPosition.RECLINING
        else -> BodyPosition.UNKNOWN
    }

    val measurementLocation = when (record.measurementLocation) {
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM -> MeasurementLocation.LEFT_UPPER_ARM
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM -> MeasurementLocation.RIGHT_UPPER_ARM
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST -> MeasurementLocation.LEFT_WRIST
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST -> MeasurementLocation.RIGHT_WRIST
        else -> MeasurementLocation.UNKNOWN
    }

    return BloodPressureReading(
        systolic = record.systolic.inMillimetersOfMercury.roundToInt(),
        diastolic = record.diastolic.inMillimetersOfMercury.roundToInt(),
        bodyPosition = bodyPosition,
        measurementLocation = measurementLocation,
        timestamp = record.time,
    )
}
