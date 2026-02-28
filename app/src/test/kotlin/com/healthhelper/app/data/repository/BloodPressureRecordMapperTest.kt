package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloodPressureRecordMapperTest {

    private fun createReading(
        systolic: Int = 120,
        diastolic: Int = 80,
        bodyPosition: BodyPosition = BodyPosition.UNKNOWN,
        measurementLocation: MeasurementLocation = MeasurementLocation.UNKNOWN,
        timestamp: Instant = Instant.parse("2026-01-15T10:00:00Z"),
    ) = BloodPressureReading(
        systolic = systolic,
        diastolic = diastolic,
        bodyPosition = bodyPosition,
        measurementLocation = measurementLocation,
        timestamp = timestamp,
    )

    @Test
    @DisplayName("maps systolic pressure in millimeters of mercury")
    fun mapsSystolicPressure() {
        val record = mapToBloodPressureRecord(createReading(systolic = 135))
        assertEquals(135.0, record.systolic.inMillimetersOfMercury)
    }

    @Test
    @DisplayName("maps diastolic pressure in millimeters of mercury")
    fun mapsDiastolicPressure() {
        val record = mapToBloodPressureRecord(createReading(diastolic = 85))
        assertEquals(85.0, record.diastolic.inMillimetersOfMercury)
    }

    @Test
    @DisplayName("BodyPosition.STANDING_UP maps to BODY_POSITION_STANDING_UP")
    fun mapsStandingUp() {
        val record = mapToBloodPressureRecord(createReading(bodyPosition = BodyPosition.STANDING_UP))
        assertEquals(BloodPressureRecord.BODY_POSITION_STANDING_UP, record.bodyPosition)
    }

    @Test
    @DisplayName("BodyPosition.SITTING_DOWN maps to BODY_POSITION_SITTING_DOWN")
    fun mapsSittingDown() {
        val record = mapToBloodPressureRecord(createReading(bodyPosition = BodyPosition.SITTING_DOWN))
        assertEquals(BloodPressureRecord.BODY_POSITION_SITTING_DOWN, record.bodyPosition)
    }

    @Test
    @DisplayName("BodyPosition.LYING_DOWN maps to BODY_POSITION_LYING_DOWN")
    fun mapsLyingDown() {
        val record = mapToBloodPressureRecord(createReading(bodyPosition = BodyPosition.LYING_DOWN))
        assertEquals(BloodPressureRecord.BODY_POSITION_LYING_DOWN, record.bodyPosition)
    }

    @Test
    @DisplayName("BodyPosition.RECLINING maps to BODY_POSITION_RECLINING")
    fun mapsReclining() {
        val record = mapToBloodPressureRecord(createReading(bodyPosition = BodyPosition.RECLINING))
        assertEquals(BloodPressureRecord.BODY_POSITION_RECLINING, record.bodyPosition)
    }

    @Test
    @DisplayName("BodyPosition.UNKNOWN maps to BODY_POSITION_UNKNOWN")
    fun mapsBodyPositionUnknown() {
        val record = mapToBloodPressureRecord(createReading(bodyPosition = BodyPosition.UNKNOWN))
        assertEquals(BloodPressureRecord.BODY_POSITION_UNKNOWN, record.bodyPosition)
    }

    @Test
    @DisplayName("MeasurementLocation.LEFT_UPPER_ARM maps to MEASUREMENT_LOCATION_LEFT_UPPER_ARM")
    fun mapsLeftUpperArm() {
        val record = mapToBloodPressureRecord(createReading(measurementLocation = MeasurementLocation.LEFT_UPPER_ARM))
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, record.measurementLocation)
    }

    @Test
    @DisplayName("MeasurementLocation.RIGHT_UPPER_ARM maps to MEASUREMENT_LOCATION_RIGHT_UPPER_ARM")
    fun mapsRightUpperArm() {
        val record = mapToBloodPressureRecord(createReading(measurementLocation = MeasurementLocation.RIGHT_UPPER_ARM))
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM, record.measurementLocation)
    }

    @Test
    @DisplayName("MeasurementLocation.LEFT_WRIST maps to MEASUREMENT_LOCATION_LEFT_WRIST")
    fun mapsLeftWrist() {
        val record = mapToBloodPressureRecord(createReading(measurementLocation = MeasurementLocation.LEFT_WRIST))
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST, record.measurementLocation)
    }

    @Test
    @DisplayName("MeasurementLocation.RIGHT_WRIST maps to MEASUREMENT_LOCATION_RIGHT_WRIST")
    fun mapsRightWrist() {
        val record = mapToBloodPressureRecord(createReading(measurementLocation = MeasurementLocation.RIGHT_WRIST))
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST, record.measurementLocation)
    }

    @Test
    @DisplayName("MeasurementLocation.UNKNOWN maps to MEASUREMENT_LOCATION_UNKNOWN")
    fun mapsMeasurementLocationUnknown() {
        val record = mapToBloodPressureRecord(createReading(measurementLocation = MeasurementLocation.UNKNOWN))
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN, record.measurementLocation)
    }

    @Test
    @DisplayName("clientRecordId starts with bloodpressure-")
    fun clientRecordIdHasPrefix() {
        val record = mapToBloodPressureRecord(createReading())
        assertTrue(record.metadata.clientRecordId?.startsWith("bloodpressure-") == true)
    }

    @Test
    @DisplayName("sets time from reading timestamp")
    fun setsTimeFromTimestamp() {
        val timestamp = Instant.parse("2026-01-15T10:00:00Z")
        val record = mapToBloodPressureRecord(createReading(timestamp = timestamp))
        assertEquals(timestamp, record.time)
    }

    @Test
    @DisplayName("rounds fractional mmHg values to nearest integer when mapping record to reading")
    fun roundsFractionalMmHgToNearestInteger() {
        val record = BloodPressureRecord(
            time = Instant.parse("2026-01-15T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(120.9),
            diastolic = Pressure.millimetersOfMercury(79.6),
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToBloodPressureReading(record)
        assertEquals(121, reading.systolic)
        assertEquals(80, reading.diastolic)
    }

    @Test
    @DisplayName("sets zoneOffset from system default at reading timestamp")
    fun setsZoneOffset() {
        val timestamp = Instant.parse("2026-01-15T10:00:00Z")
        val record = mapToBloodPressureRecord(createReading(timestamp = timestamp))
        val expectedOffset = ZoneId.systemDefault().rules.getOffset(timestamp)
        assertEquals(expectedOffset, record.zoneOffset)
    }
}
