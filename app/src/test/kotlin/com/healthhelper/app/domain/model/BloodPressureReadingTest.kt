package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BloodPressureReadingTest {

    @Test
    @DisplayName("valid reading (120, 80) with defaults succeeds")
    fun validReadingSucceeds() {
        val reading = BloodPressureReading(systolic = 120, diastolic = 80)
        assertEquals(120, reading.systolic)
        assertEquals(80, reading.diastolic)
        assertEquals(BodyPosition.UNKNOWN, reading.bodyPosition)
        assertEquals(MeasurementLocation.UNKNOWN, reading.measurementLocation)
        assertNotNull(reading.timestamp)
    }

    @Test
    @DisplayName("systolic below 60 throws IllegalArgumentException")
    fun systolicBelowMinThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 59, diastolic = 30)
        }
    }

    @Test
    @DisplayName("systolic above 300 throws IllegalArgumentException")
    fun systolicAboveMaxThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 301, diastolic = 80)
        }
    }

    @Test
    @DisplayName("diastolic below 30 throws IllegalArgumentException")
    fun diastolicBelowMinThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 80, diastolic = 29)
        }
    }

    @Test
    @DisplayName("diastolic above 200 throws IllegalArgumentException")
    fun diastolicAboveMaxThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 210, diastolic = 201)
        }
    }

    @Test
    @DisplayName("systolic equal to diastolic throws IllegalArgumentException")
    fun systolicEqualsDiastolicThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 80, diastolic = 80)
        }
    }

    @Test
    @DisplayName("systolic less than diastolic throws IllegalArgumentException")
    fun systolicLessThanDiastolicThrows() {
        assertThrows<IllegalArgumentException> {
            BloodPressureReading(systolic = 79, diastolic = 80)
        }
    }

    @Test
    @DisplayName("BodyPosition enum has STANDING_UP value")
    fun bodyPositionHasStandingUp() {
        assertTrue(BodyPosition.values().any { it == BodyPosition.STANDING_UP })
    }

    @Test
    @DisplayName("BodyPosition enum has SITTING_DOWN value")
    fun bodyPositionHasSittingDown() {
        assertTrue(BodyPosition.values().any { it == BodyPosition.SITTING_DOWN })
    }

    @Test
    @DisplayName("BodyPosition enum has LYING_DOWN value")
    fun bodyPositionHasLyingDown() {
        assertTrue(BodyPosition.values().any { it == BodyPosition.LYING_DOWN })
    }

    @Test
    @DisplayName("BodyPosition enum has RECLINING value")
    fun bodyPositionHasReclining() {
        assertTrue(BodyPosition.values().any { it == BodyPosition.RECLINING })
    }

    @Test
    @DisplayName("BodyPosition enum has UNKNOWN value")
    fun bodyPositionHasUnknown() {
        assertTrue(BodyPosition.values().any { it == BodyPosition.UNKNOWN })
    }

    @Test
    @DisplayName("MeasurementLocation enum has LEFT_UPPER_ARM value")
    fun measurementLocationHasLeftUpperArm() {
        assertTrue(MeasurementLocation.values().any { it == MeasurementLocation.LEFT_UPPER_ARM })
    }

    @Test
    @DisplayName("MeasurementLocation enum has RIGHT_UPPER_ARM value")
    fun measurementLocationHasRightUpperArm() {
        assertTrue(MeasurementLocation.values().any { it == MeasurementLocation.RIGHT_UPPER_ARM })
    }

    @Test
    @DisplayName("MeasurementLocation enum has LEFT_WRIST value")
    fun measurementLocationHasLeftWrist() {
        assertTrue(MeasurementLocation.values().any { it == MeasurementLocation.LEFT_WRIST })
    }

    @Test
    @DisplayName("MeasurementLocation enum has RIGHT_WRIST value")
    fun measurementLocationHasRightWrist() {
        assertTrue(MeasurementLocation.values().any { it == MeasurementLocation.RIGHT_WRIST })
    }

    @Test
    @DisplayName("MeasurementLocation enum has UNKNOWN value")
    fun measurementLocationHasUnknown() {
        assertTrue(MeasurementLocation.values().any { it == MeasurementLocation.UNKNOWN })
    }

    @Test
    @DisplayName("BloodPressureParseResult.Success holds systolic and diastolic")
    fun parseResultSuccessHoldsValues() {
        val result = BloodPressureParseResult.Success(systolic = 120, diastolic = 80)
        assertEquals(120, result.systolic)
        assertEquals(80, result.diastolic)
    }

    @Test
    @DisplayName("BloodPressureParseResult.Error holds message")
    fun parseResultErrorHoldsMessage() {
        val result = BloodPressureParseResult.Error(message = "Could not parse")
        assertEquals("Could not parse", result.message)
    }
}
