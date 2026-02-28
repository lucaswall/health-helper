package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodLogApiResponse
import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.FoodLogResult
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodScannerFoodLogRepositoryTest {

    private lateinit var apiClient: FoodScannerApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: FoodScannerFoodLogRepository

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

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        settingsRepository = mockk()
        coEvery { settingsRepository.getETag(any()) } returns null
        coEvery { settingsRepository.setETag(any(), any()) } returns Unit
        repository = FoodScannerFoodLogRepository(apiClient, settingsRepository)
    }

    @Test
    @DisplayName("successful API response maps to FoodLogResult.Data with correct entries")
    fun successfulResponseMapsToData() = runTest {
        val apiResponse = FoodLogApiResponse(listOf(testEntry), etag = "\"abc\"", notModified = false)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        val result = repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertTrue(data is FoodLogResult.Data)
        assertEquals(1, (data as FoodLogResult.Data).entries.size)
        assertEquals("Test Food", data.entries[0].foodName)
    }

    @Test
    @DisplayName("304 API response maps to FoodLogResult.NotModified")
    fun notModifiedResponseMapsToNotModified() = runTest {
        val apiResponse = FoodLogApiResponse(emptyList(), etag = null, notModified = true)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        val result = repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is FoodLogResult.NotModified)
    }

    @Test
    @DisplayName("ETag from settings is passed to API client")
    fun etagFromSettingsPassedToApiClient() = runTest {
        coEvery { settingsRepository.getETag("2026-02-28") } returns "\"cached_etag\""
        val apiResponse = FoodLogApiResponse(emptyList(), etag = null, notModified = true)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        coVerify { apiClient.getFoodLog(any(), any(), "2026-02-28", "\"cached_etag\"") }
    }

    @Test
    @DisplayName("new ETag from API response is stored via setETag")
    fun newEtagStoredViaSetETag() = runTest {
        val apiResponse = FoodLogApiResponse(listOf(testEntry), etag = "\"new_etag\"", notModified = false)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        coVerify { settingsRepository.setETag("2026-02-28", "\"new_etag\"") }
    }

    @Test
    @DisplayName("null ETag from API response does NOT call setETag")
    fun nullEtagDoesNotCallSetETag() = runTest {
        val apiResponse = FoodLogApiResponse(listOf(testEntry), etag = null, notModified = false)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        coVerify(exactly = 0) { settingsRepository.setETag(any(), any()) }
    }

    @Test
    @DisplayName("API failure propagates as Result.failure")
    fun apiFailurePropagates() = runTest {
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.failure(Exception("Network error"))

        val result = repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    @DisplayName("getETag failure does not prevent API call (graceful degradation)")
    fun getETagFailureDoesNotPreventApiCall() = runTest {
        coEvery { settingsRepository.getETag(any()) } throws RuntimeException("DataStore corrupted")
        val apiResponse = FoodLogApiResponse(listOf(testEntry), etag = "\"new\"", notModified = false)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        val result = repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        assertTrue(result.isSuccess)
        // API called with null etag (graceful degradation)
        coVerify { apiClient.getFoodLog(any(), any(), any(), null) }
    }

    @Test
    @DisplayName("setETag failure does not prevent successful result (graceful degradation)")
    fun setETagFailureDoesNotPreventSuccess() = runTest {
        coEvery { settingsRepository.setETag(any(), any()) } throws RuntimeException("Disk full")
        val apiResponse = FoodLogApiResponse(listOf(testEntry), etag = "\"new\"", notModified = false)
        coEvery { apiClient.getFoodLog(any(), any(), any(), any()) } returns Result.success(apiResponse)

        val result = repository.getFoodLog("https://api.example.com", "key", "2026-02-28")

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertTrue(data is FoodLogResult.Data)
        assertEquals(1, (data as FoodLogResult.Data).entries.size)
    }
}
