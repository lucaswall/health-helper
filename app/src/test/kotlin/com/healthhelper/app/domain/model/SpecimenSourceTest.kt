package com.healthhelper.app.domain.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecimenSourceTest {

    @Test
    @DisplayName("SpecimenSource has exactly 7 values")
    fun hasExactlySevenValues() {
        assertEquals(7, SpecimenSource.entries.size)
    }

    @Test
    @DisplayName("SpecimenSource has CAPILLARY_BLOOD value")
    fun hasCapillaryBlood() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.CAPILLARY_BLOOD })
    }

    @Test
    @DisplayName("SpecimenSource has INTERSTITIAL_FLUID value")
    fun hasInterstitialFluid() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.INTERSTITIAL_FLUID })
    }

    @Test
    @DisplayName("SpecimenSource has PLASMA value")
    fun hasPlasma() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.PLASMA })
    }

    @Test
    @DisplayName("SpecimenSource has SERUM value")
    fun hasSerum() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.SERUM })
    }

    @Test
    @DisplayName("SpecimenSource has TEARS value")
    fun hasTears() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.TEARS })
    }

    @Test
    @DisplayName("SpecimenSource has WHOLE_BLOOD value")
    fun hasWholeBlood() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.WHOLE_BLOOD })
    }

    @Test
    @DisplayName("SpecimenSource has UNKNOWN value")
    fun hasUnknown() {
        assertTrue(SpecimenSource.entries.any { it == SpecimenSource.UNKNOWN })
    }
}
