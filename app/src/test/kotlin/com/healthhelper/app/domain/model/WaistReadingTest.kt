package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class WaistReadingTest {

    @Test
    @DisplayName("values at the range bounds are accepted")
    fun boundsAccepted() {
        assertEquals(40.0, WaistReading(valueCm = 40.0).valueCm)
        assertEquals(250.0, WaistReading(valueCm = 250.0).valueCm)
    }

    @Test
    @DisplayName("values outside the range throw")
    fun outOfRangeThrows() {
        assertThrows<IllegalArgumentException> { WaistReading(valueCm = 39.9) }
        assertThrows<IllegalArgumentException> { WaistReading(valueCm = 250.1) }
    }

    @Test
    @DisplayName("roundToTenth keeps one decimal")
    fun roundsToTenth() {
        assertEquals(104.5, WaistReading.roundToTenth(104.46))
        assertEquals(104.4, WaistReading.roundToTenth(104.44))
        assertEquals(100.0, WaistReading.roundToTenth(100.0))
    }
}
