package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlucoseParseResultTest {

    @Test
    @DisplayName("Success holds value and detectedUnit")
    fun successHoldsValues() {
        val result = GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L)
        assertEquals(5.6, result.value)
        assertEquals(GlucoseUnit.MMOL_L, result.detectedUnit)
    }

    @Test
    @DisplayName("Error holds message")
    fun errorHoldsMessage() {
        val result = GlucoseParseResult.Error(message = "Could not parse")
        assertEquals("Could not parse", result.message)
    }

    @Test
    @DisplayName("Success is instance of GlucoseParseResult")
    fun successIsGlucoseParseResult() {
        val result: GlucoseParseResult = GlucoseParseResult.Success(value = 5.6, detectedUnit = GlucoseUnit.MMOL_L)
        assertTrue(result is GlucoseParseResult.Success)
    }

    @Test
    @DisplayName("Error is instance of GlucoseParseResult")
    fun errorIsGlucoseParseResult() {
        val result: GlucoseParseResult = GlucoseParseResult.Error(message = "error")
        assertTrue(result is GlucoseParseResult.Error)
    }
}
