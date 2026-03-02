package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.FoodLogResult
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncNutritionUseCaseTest {

    private lateinit var foodLogRepository: FoodLogRepository
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
        foodLogRepository = mockk()
        nutritionRepository = mockk()
        settingsRepository = mockk()
        useCase = SyncNutritionUseCase(foodLogRepository, nutritionRepository, settingsRepository)
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
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")
        coEvery { settingsRepository.setLastSyncedDate(any()) } returns Unit
        coEvery { settingsRepository.setLastSyncTimestamp(any()) } returns Unit
        coEvery { settingsRepository.setLastSyncedMeals(any()) } returns Unit
        coEvery { settingsRepository.getETag(any()) } returns null
        coEvery { settingsRepository.setETag(any(), any()) } returns Unit
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
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(today, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        assertEquals(1, (result as SyncResult.Success).daysProcessed)
    }

    @Test
    @DisplayName("multi-day sync aggregates record count")
    fun multiDaySync() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertTrue((result as SyncResult.Success).recordsSynced >= 2)
        assertTrue((result as SyncResult.Success).daysProcessed >= 2)
    }

    @Test
    @DisplayName("API error on one day continues syncing other days")
    fun apiErrorContinues() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.failure(Exception("Network error"))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        // today failed, but yesterday succeeded → 1 successful day
    }

    @Test
    @DisplayName("HC write failure on one day continues other days")
    fun hcWriteFailureContinues() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns false
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
    }

    @Test
    @DisplayName("empty food log skips HC write")
    fun emptyFoodLogSkipsWrite() = runTest {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(emptyList()))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).recordsSynced)
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("emits progress updates")
    fun emitsProgress() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
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
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("fail"))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("intermediate date failure does not advance lastSyncedDate past the gap")
    fun intermediateFailureDoesNotAdvancePastGap() = runTest {
        // Setup: 3 past dates to sync (today + 3 past days)
        val today = LocalDate.now()
        val day1 = today.minusDays(1) // newest past
        val day2 = today.minusDays(2)
        val day3 = today.minusDays(3) // oldest past
        configureSettings(lastSyncedDate = today.minusDays(4).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day1Str = day1.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day2Str = day2.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day3Str = day3.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // day1 succeeds, day2 FAILS, day3 succeeds
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), day1Str) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), day2Str) } returns Result.failure(Exception("transient error"))
        coEvery { foodLogRepository.getFoodLog(any(), any(), day3Str) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // lastSyncedDate should NOT advance past the gap at day2.
        // It should be set to day3 (the contiguous range from oldest is just day3).
        coVerify { settingsRepository.setLastSyncedDate(day3Str) }
    }

    @Test
    @DisplayName("all past dates succeed sets lastSyncedDate to newest past date")
    fun allPastDatesSucceedSetsNewestDate() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val dayBefore = today.minusDays(2)
        configureSettings(lastSyncedDate = today.minusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // All succeeded contiguously, so lastSyncedDate = yesterday (newest past date)
        coVerify { settingsRepository.setLastSyncedDate(yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    }

    @Test
    @DisplayName("newest past date fails does not update lastSyncedDate if no older dates to sync")
    fun newestPastDateFailsNoUpdate() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.failure(Exception("fail"))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // Yesterday failed, no older unsynced dates → lastSyncedDate should NOT be updated
        coVerify(exactly = 0) { settingsRepository.setLastSyncedDate(any()) }
    }

    @Test
    @DisplayName("respects lastSyncedDate — does not re-sync already synced dates except today")
    fun respectsLastSyncedDate() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        // lastSyncedDate = yesterday → only today needs syncing
        configureSettings(lastSyncedDate = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // Only today should be fetched (yesterday already synced)
        coVerify(exactly = 1) { foodLogRepository.getFoodLog(any(), any(), any()) }
        coVerify { foodLogRepository.getFoodLog(any(), any(), todayStr) }
    }

    @Test
    @DisplayName("caps at 365 days back from today")
    fun capsAt365Days() = runTest {
        // Set lastSyncedDate to 400 days ago → should only sync back 365 days
        configureSettings(lastSyncedDate = LocalDate.now().minusDays(400).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(emptyList()))

        val progressUpdates = mutableListOf<SyncProgress>()
        useCase.invoke { progressUpdates.add(it) }

        // Should be at most 366 days (today + 365 past days)
        assertTrue(progressUpdates.last().totalDays <= 366)
    }

    @Test
    @DisplayName("API succeeds with entries but all HC writes fail returns Error")
    fun allHcWritesFailReturnsError() = runTest {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns false

        val result = useCase.invoke()

        // Should not be Success(0) — should indicate write failure
        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("API succeeds with entries, some HC writes succeed returns Success with partial count")
    fun partialHcWriteSuccess() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns false

        val result = useCase.invoke()

        // Partial success — some records written
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
    }

    @Test
    @DisplayName("invoke sets lastSyncTimestamp on successful sync")
    fun invokeSetsLastSyncTimestampOnSuccess() = runTest {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(today, any()) } returns true

        useCase.invoke()

        coVerify { settingsRepository.setLastSyncTimestamp(match { it > 0L }) }
    }

    @Test
    @DisplayName("invoke does not set lastSyncTimestamp when all days fail")
    fun invokeDoesNotSetLastSyncTimestampOnError() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("fail"))

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setLastSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("malformed lastSyncedDate falls back to maxPastDate and does not throw")
    fun malformedLastSyncedDateFallsBack() = runTest {
        // A corrupt date string that cannot be parsed as ISO_LOCAL_DATE
        configureSettings(lastSyncedDate = "not-a-date")
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // API returns empty list so we get a clean Success(0) result
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(emptyList()))

        // Should NOT throw DateTimeParseException; corrupt date triggers full 366-day fallback
        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(366, (result as SyncResult.Success).daysProcessed)
    }

    // --- Settings read exception handling tests ---

    @Test
    @DisplayName("isConfigured throws IOException: returns SyncResult.Error instead of propagating")
    fun isConfiguredThrowsReturnsError() = runTest {
        coEvery { settingsRepository.isConfigured() } throws IOException("DataStore corrupted")

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("apiKeyFlow.first() throws: returns SyncResult.Error instead of propagating")
    fun apiKeyFlowThrowsReturnsError() = runTest {
        coEvery { settingsRepository.isConfigured() } returns true
        every { settingsRepository.apiKeyFlow } returns flow { throw IOException("DataStore IO error") }
        every { settingsRepository.baseUrlFlow } returns flowOf("https://food.example.com")
        every { settingsRepository.lastSyncedDateFlow } returns flowOf("")

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    // --- setLastSyncTimestamp correctness tests ---

    @Test
    @DisplayName("all API calls succeed but all HC writes fail: setLastSyncTimestamp NOT called")
    fun allHcWritesFailDoesNotSetTimestamp() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns false

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
        coVerify(exactly = 0) { settingsRepository.setLastSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("some HC writes succeed (totalRecordsSynced > 0): setLastSyncTimestamp IS called")
    fun someHcWritesSucceedSetsTimestamp() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns false

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        coVerify { settingsRepository.setLastSyncTimestamp(match { it > 0L }) }
    }

    // --- Meal collection tests ---

    @Test
    @DisplayName("invoke persists last 3 meals sorted by date descending then time descending")
    fun persistsLast3MealsSortedByDateThenTime() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val entry1 = testEntry.copy(foodName = "Breakfast Today", mealType = MealType.BREAKFAST, time = "08:00:00", calories = 300.0)
        val entry2 = testEntry.copy(foodName = "Lunch Today", mealType = MealType.LUNCH, time = "12:00:00", calories = 500.0)
        val entry3 = testEntry.copy(foodName = "Dinner Yesterday", mealType = MealType.DINNER, time = "19:00:00", calories = 700.0)
        val entry4 = testEntry.copy(foodName = "Breakfast Yesterday", mealType = MealType.BREAKFAST, time = "07:00:00", calories = 250.0)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(entry1, entry2)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(entry3, entry4)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // Most recent 3: today's lunch (12:00), today's breakfast (08:00), yesterday's dinner (19:00)
        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(3, meals.size)
                    assertEquals("Lunch Today", meals[0].foodName)
                    assertEquals("Breakfast Today", meals[1].foodName)
                    assertEquals("Dinner Yesterday", meals[2].foodName)
                },
            )
        }
    }

    @Test
    @DisplayName("invoke persists fewer than 3 meals when fewer were synced")
    fun persistsFewerThan3MealsWhenFewerSynced() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(1, meals.size)
                },
            )
        }
    }

    @Test
    @DisplayName("invoke does not call setLastSyncedMeals when no records synced")
    fun doesNotSetMealsWhenNoRecordsSynced() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(emptyList()))

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setLastSyncedMeals(any()) }
    }

    @Test
    @DisplayName("invoke maps FoodLogEntry to SyncedMealSummary correctly")
    fun mapsFoodLogEntryToSyncedMealSummaryCorrectly() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        val entry = testEntry.copy(
            foodName = "Pasta",
            mealType = MealType.DINNER,
            calories = 499.7,
        )
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(entry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(1, meals.size)
                    assertEquals("Pasta", meals[0].foodName)
                    assertEquals(MealType.DINNER, meals[0].mealType)
                    assertEquals(500, meals[0].calories) // roundToInt() rounds
                },
            )
        }
    }

    // --- NotModified / ETag tests ---

    @Test
    @DisplayName("NotModified for today skips HC write, returns Success with 0 records and 1 day")
    fun notModifiedForTodaySkipsHcWrite() = runTest {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.NotModified)

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).recordsSynced)
        assertEquals(1, result.daysProcessed)
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("NotModified for a past date counts as successful in contiguous watermark")
    fun notModifiedCountsAsSuccessfulInContiguousWatermark() = runTest {
        val today = LocalDate.now()
        val day1 = today.minusDays(1)
        val day2 = today.minusDays(2) // middle — NotModified
        val day3 = today.minusDays(3) // oldest
        configureSettings(lastSyncedDate = today.minusDays(4).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day1Str = day1.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day2Str = day2.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day3Str = day3.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), day1Str) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), day2Str) } returns Result.success(FoodLogResult.NotModified)
        coEvery { foodLogRepository.getFoodLog(any(), any(), day3Str) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // NotModified at day2 counts as success → lastSyncedDate advances through it to day1
        coVerify { settingsRepository.setLastSyncedDate(day1Str) }
    }

    @Test
    @DisplayName("mix of Data and NotModified: HC write called only for Data dates")
    fun mixedDataAndNotModified() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.NotModified)
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        // HC write called only for yesterday
        coVerify(exactly = 1) { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) }
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(todayStr, any()) }
    }

    @Test
    @DisplayName("all dates return NotModified: setLastSyncedMeals is NOT called")
    fun allNotModifiedPreservesExistingMeals() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.NotModified)

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setLastSyncedMeals(any()) }
    }

    // --- Consecutive failure abort + backoff tests ---

    @Test
    @DisplayName("5 consecutive API failures aborts the sync loop — getFoodLog called exactly 5 times")
    fun fiveConsecutiveFailuresAborts() = runTest {
        // Setup: 10 dates to sync (today + 9 past days), all fail
        val today = LocalDate.now()
        configureSettings(lastSyncedDate = today.minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("503"))

        useCase.invoke()

        // Should abort after 5 consecutive failures, not call all 10
        coVerify(exactly = 5) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("success after 4 consecutive failures resets counter — sync continues through all dates")
    fun successAfter4FailuresResets() = runTest {
        val today = LocalDate.now()
        // 6 dates: today + 5 past days
        configureSettings(lastSyncedDate = today.minusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE))

        var callCount = 0
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } answers {
            callCount++
            // Fail first 4, succeed on 5th, then succeed remaining
            if (callCount in 1..4) {
                Result.failure(Exception("503"))
            } else {
                Result.success(FoodLogResult.Data(listOf(testEntry)))
            }
        }
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // All 6 dates should be processed (4 fail + success resets counter + remaining succeed)
        coVerify(exactly = 6) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("intermittent failures (fail, succeed, fail, succeed) never trigger abort")
    fun intermittentFailuresNeverAbort() = runTest {
        val today = LocalDate.now()
        // 8 dates: today + 7 past days
        configureSettings(lastSyncedDate = today.minusDays(8).format(DateTimeFormatter.ISO_LOCAL_DATE))

        var callCount = 0
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } answers {
            callCount++
            if (callCount % 2 == 1) {
                Result.failure(Exception("transient"))
            } else {
                Result.success(FoodLogResult.Data(listOf(testEntry)))
            }
        }
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // All 8 dates should be processed — alternating never reaches 5 consecutive
        coVerify(exactly = 8) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("abort with no prior successes returns SyncResult.Error")
    fun abortWithNoSuccessesReturnsError() = runTest {
        val today = LocalDate.now()
        configureSettings(lastSyncedDate = today.minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("503"))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("abort with some prior successes returns SyncResult.Success with partial count")
    fun abortWithSomeSuccessesReturnsPartialSuccess() = runTest {
        val today = LocalDate.now()
        // 10 dates: today + 9 past days
        configureSettings(lastSyncedDate = today.minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE))

        var callCount = 0
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } answers {
            callCount++
            // First 2 succeed, then 5 consecutive failures → abort
            if (callCount <= 2) {
                Result.success(FoodLogResult.Data(listOf(testEntry)))
            } else {
                Result.failure(Exception("503"))
            }
        }
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(2, (result as SyncResult.Success).recordsSynced)
        // 2 succeed + 5 fail = 7 calls total
        coVerify(exactly = 7) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("existing apiErrorContinues test still passes — single failure does not abort")
    fun singleFailureDoesNotAbort() = runTest {
        // This is conceptually the same as apiErrorContinues but explicit about abort
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.failure(Exception("Network error"))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        // Both dates should be called — single failure doesn't abort
        coVerify(exactly = 2) { foodLogRepository.getFoodLog(any(), any(), any()) }
        assertTrue(result is SyncResult.Success)
    }

    // --- Timestamp population tests ---

    @Test
    @DisplayName("synced meal summaries include timestamp derived from entry date + time")
    fun syncedMealSummariesIncludeTimestamp() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        val entry = testEntry.copy(foodName = "Lunch", time = "12:30:00")
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(entry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        val expectedInstant = today.atTime(LocalTime.of(12, 30, 0)).atZone(ZoneId.systemDefault()).toInstant()
        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(1, meals.size)
                    assertEquals(expectedInstant, meals[0].timestamp)
                },
            )
        }
    }

    @Test
    @DisplayName("synced meal summaries with null time get timestamp at noon of the entry date")
    fun syncedMealSummariesWithNullTimeDefaultToNoon() = runTest {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        val entry = testEntry.copy(foodName = "Lunch", time = null)
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(entry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        val expectedInstant = today.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant()
        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(1, meals.size)
                    assertEquals(expectedInstant, meals[0].timestamp)
                },
            )
        }
    }

    @Test
    @DisplayName("some Data with entries, others NotModified: setLastSyncedMeals IS called with Data entries only")
    fun someDataSomeNotModifiedCallsSetMeals() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val entry = testEntry.copy(foodName = "Lunch", mealType = MealType.LUNCH)
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.NotModified)
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(entry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        coVerify {
            settingsRepository.setLastSyncedMeals(
                withArg { meals ->
                    assertEquals(1, meals.size)
                    assertEquals("Lunch", meals[0].foodName)
                },
            )
        }
    }
}
