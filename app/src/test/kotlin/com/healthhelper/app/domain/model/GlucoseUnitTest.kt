package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlucoseUnitTest {

    @Test
    @DisplayName("GlucoseUnit has MMOL_L value")
    fun hasMmolL() {
        assertTrue(GlucoseUnit.entries.any { it == GlucoseUnit.MMOL_L })
    }

    @Test
    @DisplayName("GlucoseUnit has MG_DL value")
    fun hasMgDl() {
        assertTrue(GlucoseUnit.entries.any { it == GlucoseUnit.MG_DL })
    }

    @Test
    @DisplayName("GlucoseUnit has exactly 2 values")
    fun hasExactlyTwoValues() {
        assertEquals(2, GlucoseUnit.entries.size)
    }
}
