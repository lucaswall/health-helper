package com.healthhelper.app.data.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthReadingsDtosTest {

    // Same settings as the app's HttpClient (AppModule.provideHttpClient).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val dto = BloodPressureReadingDto(
        measuredAt = "2026-10-28T09:15:00Z",
        systolic = 120,
        diastolic = 80,
    )

    @Test
    @DisplayName("heartRate is sent when known")
    fun heartRateSerialized() {
        assertTrue(json.encodeToString(dto.copy(heartRate = 72)).contains("\"heartRate\":72"))
    }

    @Test
    @DisplayName("unknown heartRate is omitted so the server keeps a previously stored value")
    fun nullHeartRateOmitted() {
        assertFalse(json.encodeToString(dto).contains("heartRate"))
    }
}
