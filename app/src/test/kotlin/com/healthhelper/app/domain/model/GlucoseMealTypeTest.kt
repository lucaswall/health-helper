package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlucoseMealTypeTest {

    @Test
    @DisplayName("GlucoseMealType has exactly 5 values")
    fun hasExactlyFiveValues() {
        assertEquals(5, GlucoseMealType.entries.size)
    }

    @Test
    @DisplayName("GlucoseMealType has BREAKFAST value")
    fun hasBreakfast() {
        assertTrue(GlucoseMealType.entries.any { it == GlucoseMealType.BREAKFAST })
    }

    @Test
    @DisplayName("GlucoseMealType has LUNCH value")
    fun hasLunch() {
        assertTrue(GlucoseMealType.entries.any { it == GlucoseMealType.LUNCH })
    }

    @Test
    @DisplayName("GlucoseMealType has DINNER value")
    fun hasDinner() {
        assertTrue(GlucoseMealType.entries.any { it == GlucoseMealType.DINNER })
    }

    @Test
    @DisplayName("GlucoseMealType has SNACK value")
    fun hasSnack() {
        assertTrue(GlucoseMealType.entries.any { it == GlucoseMealType.SNACK })
    }

    @Test
    @DisplayName("GlucoseMealType has UNKNOWN value")
    fun hasUnknown() {
        assertTrue(GlucoseMealType.entries.any { it == GlucoseMealType.UNKNOWN })
    }
}
