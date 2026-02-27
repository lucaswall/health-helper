package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncNutritionUseCaseTest {

    private lateinit var apiClient: FoodScannerApiClient
    private lateinit var nutritionRepository: NutritionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: SyncNutritionUseCase

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
        nutritionRepository = mockk()
        settingsRepository = mockk()
        useCase = SyncNutritionUseCase(apiClient, nutritionRepository, settingsRepository)
    }

    private fun configureSettings(
        configured: Boolean = true,
        apiKey: String = "fsk_test",
        baseUrl: String = "https://food.example.com",
        lastSyncedDate: String = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
    ) {
        coEvery { settingsRepository.isConfigured() } returns configured
        every { settingsRepository.apiKeyFlow } returns flowOf(apiKey)
        every { settingsRepository.baseUrlFlow } returns flowOf(baseUrl)
        every { settingsRepository.lastSyncedDateFlow } returns flowOf(lastSyncedDate)
        coEvery { settingsRepository.setLastSyncedDate(any()) } returns Unit
    }

    @Test
    @DisplayName("not configured returns NeedsConfiguration")
    fun notConfigured() = runTest {
        configureSettings(configured = false)

        val result = useCase.invoke()

        assertTrue(result is SyncResult.NeedsConfiguration)
    }

    @Test
    @DisplayName("single day sync fetches and writes records")
    fun singleDaySync() = runTest {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { apiClient.getFoodLog(any(), any(), today) } returns Result.success(listOf(testEntry))
        coEvery { nutritionRepository.writeNutritionRecords(today, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
    }

    @Test
    @DisplayName("multi-day sync aggregates record count")
    fun multiDaySync() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { apiClient.getFoodLog(any(), any(), any()) } returns Result.success(listOf(testEntry))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertTrue((result as SyncResult.Success).recordsSynced >= 2)
    }

    @Test
    @DisplayName("API error on one day continues syncing other days")
    fun apiErrorContinues() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { apiClient.getFoodLog(any(), any(), todayStr) } returns Result.failure(Exception("Network error"))
        coEvery { apiClient.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(listOf(testEntry))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
    }

    @Test
    @DisplayName("HC write failure on one day continues other days")
    fun hcWriteFailureContinues() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { apiClient.getFoodLog(any(), any(), any()) } returns Result.success(listOf(testEntry))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns false
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
    }

    @Test
    @DisplayName("empty food log skips HC write")
    fun emptyFoodLogSkipsWrite() = runTest {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { apiClient.getFoodLog(any(), any(), today) } returns Result.success(emptyList())

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).recordsSynced)
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("emits progress updates")
    fun emitsProgress() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { apiClient.getFoodLog(any(), any(), any()) } returns Result.success(listOf(testEntry))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val progressUpdates = mutableListOf<SyncProgress>()
        useCase.invoke { progressUpdates.add(it) }

        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(1, progressUpdates.last().completedDays)
        assertEquals(1, progressUpdates.last().totalDays)
    }

    @Test
    @DisplayName("all days fail returns Error")
    fun allDaysFailReturnsError() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { apiClient.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("fail"))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }
}
