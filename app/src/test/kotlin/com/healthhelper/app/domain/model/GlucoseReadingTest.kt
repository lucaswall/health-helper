package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GlucoseReadingTest {

    @Test
    @DisplayName("valid reading with default metadata creates successfully")
    fun validReadingWithDefaults() {
        val reading = GlucoseReading(valueMmolL = 5.6)
        assertEquals(5.6, reading.valueMmolL)
        assertEquals(RelationToMeal.UNKNOWN, reading.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, reading.glucoseMealType)
        assertEquals(SpecimenSource.UNKNOWN, reading.specimenSource)
        assertNotNull(reading.timestamp)
    }

    @Test
    @DisplayName("value below 1.0 mmol/L throws IllegalArgumentException")
    fun valueBelowMinThrows() {
        assertThrows<IllegalArgumentException> {
            GlucoseReading(valueMmolL = 0.9)
        }
    }

    @Test
    @DisplayName("value above 40.0 mmol/L throws IllegalArgumentException")
    fun valueAboveMaxThrows() {
        assertThrows<IllegalArgumentException> {
            GlucoseReading(valueMmolL = 40.1)
        }
    }

    @Test
    @DisplayName("boundary value 1.0 passes validation")
    fun boundaryMinPasses() {
        val reading = GlucoseReading(valueMmolL = 1.0)
        assertEquals(1.0, reading.valueMmolL)
    }

    @Test
    @DisplayName("boundary value 40.0 passes validation")
    fun boundaryMaxPasses() {
        val reading = GlucoseReading(valueMmolL = 40.0)
        assertEquals(40.0, reading.valueMmolL)
    }

    @Test
    @DisplayName("displayInMgDl converts 5.6 mmol/L to 101 mg/dL")
    fun displayInMgDlConverts() {
        val reading = GlucoseReading(valueMmolL = 5.6)
        assertEquals(101, reading.displayInMgDl())
    }
}
