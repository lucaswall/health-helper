package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.response.InsertRecordsResponse
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthConnectNutritionRepositoryTest {

    private val testEntry = FoodLogEntry(
        id = 1,
        foodName = "Test Food",
        mealType = MealType.BREAKFAST,
        time = "08:00:00",
        calories = 300.0,
        proteinG = 10.0,
        carbsG = 40.0,
        fatG = 8.0,
        fiberG = 3.0,
        sodiumMg = 150.0,
        saturatedFatG = null,
        transFatG = null,
        sugarsG = null,
        caloriesFromFat = null,
    )

    @Test
    @DisplayName("writeNutritionRecords returns false when HealthConnectClient is null")
    fun nullClientReturnsFalse() = runTest {
        val repository = HealthConnectNutritionRepository(healthConnectClient = null)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeNutritionRecords")
    fun cancellationExceptionPropagates() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")
        val repository = HealthConnectNutritionRepository(mockClient)

        assertFailsWith<CancellationException> {
            repository.writeNutritionRecords("2026-01-15", emptyList())
        }
    }

    @Test
    @DisplayName("writeNutritionRecords returns false when insertRecords throws SecurityException")
    fun securityExceptionReturnsFalse() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("writeNutritionRecords returns false when insertRecords throws general Exception")
    fun generalExceptionReturnsFalse() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("writeNutritionRecords returns true on successful write")
    fun successfulWriteReturnsTrue() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertTrue(result)
    }

    @Test
    @DisplayName("writeNutritionRecords handles empty entries list")
    fun emptyEntriesListReturnsTrue() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", emptyList())
        assertTrue(result)
    }
}
