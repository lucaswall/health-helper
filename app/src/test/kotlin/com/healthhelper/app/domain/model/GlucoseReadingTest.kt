package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlucoseReadingTest {

    @Test
    @DisplayName("valid mg/dL value creates successfully")
    fun validMgDlValueCreates() {
        val reading = GlucoseReading(valueMgDl = 100)
        assertEquals(100, reading.valueMgDl)
        assertEquals(RelationToMeal.UNKNOWN, reading.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, reading.glucoseMealType)
        assertEquals(SpecimenSource.UNKNOWN, reading.specimenSource)
        assertNotNull(reading.timestamp)
    }

    @Test
    @DisplayName("boundary value 18 is accepted")
    fun boundaryMin18Passes() {
        val reading = GlucoseReading(valueMgDl = 18)
        assertEquals(18, reading.valueMgDl)
    }

    @Test
    @DisplayName("boundary value 720 is accepted")
    fun boundaryMax720Passes() {
        val reading = GlucoseReading(valueMgDl = 720)
        assertEquals(720, reading.valueMgDl)
    }

    @Test
    @DisplayName("value below 18 mg/dL throws IllegalArgumentException")
    fun valueBelowMinThrows() {
        assertThrows<IllegalArgumentException> {
            GlucoseReading(valueMgDl = 17)
        }
    }

    @Test
    @DisplayName("value above 720 mg/dL throws IllegalArgumentException")
    fun valueAboveMaxThrows() {
        assertThrows<IllegalArgumentException> {
            GlucoseReading(valueMgDl = 721)
        }
    }

    @Test
    @DisplayName("toMmolL converts 100 mg/dL to approximately 5.55 mmol/L")
    fun toMmolLConverts100() {
        val reading = GlucoseReading(valueMgDl = 100)
        val result = reading.toMmolL()
        assertTrue(abs(result - 5.55) < 0.01, "Expected ~5.55 but was $result")
    }

    @Test
    @DisplayName("fromMmolL round-trip for 70 mg/dL")
    fun roundTripFor70() {
        val original = 70
        val reading = GlucoseReading(valueMgDl = original)
        val backToMgDl = GlucoseReading.fromMmolL(reading.toMmolL())
        assertEquals(original, backToMgDl)
    }

    @Test
    @DisplayName("fromMmolL round-trip for 100 mg/dL")
    fun roundTripFor100() {
        val original = 100
        val reading = GlucoseReading(valueMgDl = original)
        val backToMgDl = GlucoseReading.fromMmolL(reading.toMmolL())
        assertEquals(original, backToMgDl)
    }

    @Test
    @DisplayName("fromMmolL round-trip for 126 mg/dL")
    fun roundTripFor126() {
        val original = 126
        val reading = GlucoseReading(valueMgDl = original)
        val backToMgDl = GlucoseReading.fromMmolL(reading.toMmolL())
        assertEquals(original, backToMgDl)
    }

    @Test
    @DisplayName("fromMmolL round-trip for 200 mg/dL")
    fun roundTripFor200() {
        val original = 200
        val reading = GlucoseReading(valueMgDl = original)
        val backToMgDl = GlucoseReading.fromMmolL(reading.toMmolL())
        assertEquals(original, backToMgDl)
    }
}
