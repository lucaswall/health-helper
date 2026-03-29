package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.MeasurementLocation
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FoodScannerHealthRepositoryImplTest {

    private lateinit var apiClient: FoodScannerApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: FoodScannerHealthRepositoryImpl

    // Old constructor (valueMmolL) used for worktree compat.
    // After Worker-1 merges, this should be GlucoseReading(valueMgDl = 90).
    // (5.0 * 18.018).roundToInt() = 90
    private val fixedTimestamp = Instant.parse("2026-03-29T10:00:00Z")
    private val testGlucoseReading = GlucoseReading(
        valueMmolL = 5.0,
        relationToMeal = RelationToMeal.BEFORE_MEAL,
        glucoseMealType = GlucoseMealType.BREAKFAST,
        specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        timestamp = fixedTimestamp,
    )
    private val testBpReading = BloodPressureReading(
        systolic = 120,
        diastolic = 80,
        bodyPosition = BodyPosition.SITTING_DOWN,
        measurementLocation = MeasurementLocation.LEFT_UPPER_ARM,
        timestamp = fixedTimestamp,
    )

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        settingsRepository = mockk()
        every { settingsRepository.baseUrlFlow } returns flowOf("https://example.com")
        every { settingsRepository.apiKeyFlow } returns flowOf("test-key")
        repository = FoodScannerHealthRepositoryImpl(apiClient, settingsRepository)
    }

    // --- pushGlucoseReading tests ---

    @Test
    @DisplayName("pushGlucoseReading success: API returns upserted=1, repo returns Result.success(Unit)")
    fun pushGlucoseReadingSuccess() = runTest {
        coEvery { apiClient.postGlucoseReadings(any(), any(), any()) } returns Result.success(1)

        val result = repository.pushGlucoseReading(testGlucoseReading)

        assertTrue(result.isSuccess)
        assertEquals(Unit, result.getOrThrow())
    }

    @Test
    @DisplayName("pushGlucoseReading failure: API returns failure, repo returns Result.failure with same exception")
    fun pushGlucoseReadingFailurePropagates() = runTest {
        val exception = RuntimeException("API error")
        coEvery { apiClient.postGlucoseReadings(any(), any(), any()) } returns Result.failure(exception)

        val result = repository.pushGlucoseReading(testGlucoseReading)

        assertTrue(result.isFailure)
        assertEquals("API error", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushGlucoseReading maps valueMgDl correctly to DTO")
    fun pushGlucoseReadingMapsValueMgDl() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        // (5.0 * 18.018).roundToInt() = 90
        assertEquals(90, slot.captured.readings[0].valueMgDl)
    }

    @Test
    @DisplayName("pushGlucoseReading maps relationToMeal as snake_case")
    fun pushGlucoseReadingMapsRelationToMeal() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        assertEquals("before_meal", slot.captured.readings[0].relationToMeal)
    }

    @Test
    @DisplayName("pushGlucoseReading maps mealType as snake_case")
    fun pushGlucoseReadingMapsMealType() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        assertEquals("breakfast", slot.captured.readings[0].mealType)
    }

    @Test
    @DisplayName("pushGlucoseReading maps specimenSource as snake_case")
    fun pushGlucoseReadingMapsSpecimenSource() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        assertEquals("capillary_blood", slot.captured.readings[0].specimenSource)
    }

    @Test
    @DisplayName("pushGlucoseReading maps timestamp as ISO 8601")
    fun pushGlucoseReadingMapsTimestamp() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        assertEquals(fixedTimestamp.toString(), slot.captured.readings[0].measuredAt)
    }

    @Test
    @DisplayName("pushGlucoseReading captures zone offset from ZoneId.systemDefault()")
    fun pushGlucoseReadingCapturesZoneOffset() = runTest {
        val slot = slot<GlucoseReadingRequest>()
        coEvery { apiClient.postGlucoseReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushGlucoseReading(testGlucoseReading)

        val zoneOffset = assertNotNull(slot.captured.readings[0].zoneOffset)
        // Must be ±HH:MM format or +00:00
        assertTrue(
            zoneOffset.matches(Regex("[+-]\\d{2}:\\d{2}")),
            "Expected zone offset in ±HH:MM format, got: $zoneOffset",
        )
    }

    @Test
    @DisplayName("pushGlucoseReading blank URL returns failure with descriptive message")
    fun pushGlucoseReadingBlankUrlFailure() = runTest {
        every { settingsRepository.baseUrlFlow } returns flowOf("")

        val result = repository.pushGlucoseReading(testGlucoseReading)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushGlucoseReading blank API key returns failure with descriptive message")
    fun pushGlucoseReadingBlankKeyFailure() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")

        val result = repository.pushGlucoseReading(testGlucoseReading)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushGlucoseReading does not call API when settings not configured")
    fun pushGlucoseReadingDoesNotCallApiWhenNotConfigured() = runTest {
        every { settingsRepository.baseUrlFlow } returns flowOf("")

        repository.pushGlucoseReading(testGlucoseReading)

        coVerify(exactly = 0) { apiClient.postGlucoseReadings(any(), any(), any()) }
    }

    // --- pushBloodPressureReading tests ---

    @Test
    @DisplayName("pushBloodPressureReading success: API returns upserted=1, repo returns Result.success(Unit)")
    fun pushBloodPressureReadingSuccess() = runTest {
        coEvery { apiClient.postBloodPressureReadings(any(), any(), any()) } returns Result.success(1)

        val result = repository.pushBloodPressureReading(testBpReading)

        assertTrue(result.isSuccess)
        assertEquals(Unit, result.getOrThrow())
    }

    @Test
    @DisplayName("pushBloodPressureReading failure: API returns failure, repo returns Result.failure")
    fun pushBloodPressureReadingFailurePropagates() = runTest {
        val exception = RuntimeException("BP API error")
        coEvery { apiClient.postBloodPressureReadings(any(), any(), any()) } returns Result.failure(exception)

        val result = repository.pushBloodPressureReading(testBpReading)

        assertTrue(result.isFailure)
        assertEquals("BP API error", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushBloodPressureReading maps systolic and diastolic correctly")
    fun pushBloodPressureReadingMapsSystolicDiastolic() = runTest {
        val slot = slot<BloodPressureReadingRequest>()
        coEvery { apiClient.postBloodPressureReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushBloodPressureReading(testBpReading)

        val dto = slot.captured.readings[0]
        assertEquals(120, dto.systolic)
        assertEquals(80, dto.diastolic)
    }

    @Test
    @DisplayName("pushBloodPressureReading maps bodyPosition as snake_case")
    fun pushBloodPressureReadingMapsBodyPosition() = runTest {
        val slot = slot<BloodPressureReadingRequest>()
        coEvery { apiClient.postBloodPressureReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushBloodPressureReading(testBpReading)

        assertEquals("sitting_down", slot.captured.readings[0].bodyPosition)
    }

    @Test
    @DisplayName("pushBloodPressureReading maps measurementLocation as snake_case")
    fun pushBloodPressureReadingMapsMeasurementLocation() = runTest {
        val slot = slot<BloodPressureReadingRequest>()
        coEvery { apiClient.postBloodPressureReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushBloodPressureReading(testBpReading)

        assertEquals("left_upper_arm", slot.captured.readings[0].measurementLocation)
    }

    @Test
    @DisplayName("pushBloodPressureReading maps timestamp as ISO 8601")
    fun pushBloodPressureReadingMapsTimestamp() = runTest {
        val slot = slot<BloodPressureReadingRequest>()
        coEvery { apiClient.postBloodPressureReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushBloodPressureReading(testBpReading)

        assertEquals(fixedTimestamp.toString(), slot.captured.readings[0].measuredAt)
    }

    @Test
    @DisplayName("pushBloodPressureReading captures zone offset from ZoneId.systemDefault()")
    fun pushBloodPressureReadingCapturesZoneOffset() = runTest {
        val slot = slot<BloodPressureReadingRequest>()
        coEvery { apiClient.postBloodPressureReadings(any(), any(), capture(slot)) } returns Result.success(1)

        repository.pushBloodPressureReading(testBpReading)

        val zoneOffset = assertNotNull(slot.captured.readings[0].zoneOffset)
        assertTrue(
            zoneOffset.matches(Regex("[+-]\\d{2}:\\d{2}")),
            "Expected zone offset in ±HH:MM format, got: $zoneOffset",
        )
    }

    @Test
    @DisplayName("pushBloodPressureReading blank URL returns failure with descriptive message")
    fun pushBloodPressureReadingBlankUrlFailure() = runTest {
        every { settingsRepository.baseUrlFlow } returns flowOf("")

        val result = repository.pushBloodPressureReading(testBpReading)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushBloodPressureReading blank API key returns failure with descriptive message")
    fun pushBloodPressureReadingBlankKeyFailure() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")

        val result = repository.pushBloodPressureReading(testBpReading)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("pushBloodPressureReading does not call API when settings not configured")
    fun pushBloodPressureReadingDoesNotCallApiWhenNotConfigured() = runTest {
        every { settingsRepository.apiKeyFlow } returns flowOf("")

        repository.pushBloodPressureReading(testBpReading)

        coVerify(exactly = 0) { apiClient.postBloodPressureReadings(any(), any(), any()) }
    }
}
