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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncNutritionUseCaseTest {

    private lateinit var foodLogRepository: FoodLogRepository
    private lateinit var nutritionRepository: NutritionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: SyncNutritionUseCase

    // Fixed clock so tests never straddle midnight between the test's and the use case's "today"
    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-15T15:00:00Z"), ZoneId.systemDefault())

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
        useCase = SyncNutritionUseCase(foodLogRepository, nutritionRepository, settingsRepository, clock)
    }

    private fun dateStr(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun configureSettings(
        configured: Boolean = true,
        apiKey: String = "fsk_test",
        baseUrl: String = "https://food.example.com",
        lastSyncedDate: String = LocalDate.now(clock).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
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
        // Defaults (more specific stubs declared later in a test take precedence): days a test
        // doesn't care about — e.g. the trailing re-check window — are unchanged (304).
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.NotModified)
        coEvery { nutritionRepository.deleteStaleNutritionRecords(any(), any()) } returns true
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
        val today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(today, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        // today + 7-day re-check window (unchanged days count as processed)
        assertEquals(8, (result as SyncResult.Success).daysProcessed)
    }

    @Test
    @DisplayName("multi-day sync aggregates record count")
    fun multiDaySync() = runTest {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-today\""))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-yesterday\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns false
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        // Failed day's ETag must not be committed (so it is re-fetched, not 304'd); written day's is
        coVerify(exactly = 0) { settingsRepository.setETag(todayStr, any()) }
        coVerify(exactly = 1) { settingsRepository.setETag(yesterdayStr, "\"etag-yesterday\"") }
        coVerify { settingsRepository.setLastSyncedDate(yesterdayStr) }
    }

    @Test
    @DisplayName("empty food log skips HC write")
    fun emptyFoodLogSkipsWrite() = runTest {
        val today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(emptyList()))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).recordsSynced)
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("emits progress updates")
    fun emitsProgress() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val progressUpdates = mutableListOf<SyncProgress>()
        useCase.invoke { progressUpdates.add(it) }

        assertTrue(progressUpdates.isNotEmpty())
        // today + 7-day re-check window
        assertEquals(8, progressUpdates.last().completedDays)
        assertEquals(8, progressUpdates.last().totalDays)
    }

    @Test
    @DisplayName("all days fail returns Error")
    fun allDaysFailReturnsError() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("fail"))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("intermediate date failure does not advance lastSyncedDate past the gap")
    fun intermediateFailureDoesNotAdvancePastGap() = runTest {
        // Setup: 3 past dates to sync (today + 3 past days)
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
    @DisplayName("respects lastSyncedDate — only today and the 7-day re-check window are fetched")
    fun respectsLastSyncedDate() = runTest {
        val today = LocalDate.now(clock)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        // lastSyncedDate = yesterday → only today needs syncing
        configureSettings(lastSyncedDate = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        useCase.invoke()

        // Today plus the trailing re-check window (today-7..today-1); nothing older
        coVerify(exactly = 8) { foodLogRepository.getFoodLog(any(), any(), any()) }
        coVerify { foodLogRepository.getFoodLog(any(), any(), todayStr) }
        coVerify { foodLogRepository.getFoodLog(any(), any(), dateStr(today.minusDays(1))) }
        coVerify { foodLogRepository.getFoodLog(any(), any(), dateStr(today.minusDays(7))) }
        coVerify(exactly = 0) { foodLogRepository.getFoodLog(any(), any(), dateStr(today.minusDays(8))) }
    }

    @Test
    @DisplayName("caps at 365 days back from today")
    fun capsAt365Days() = runTest {
        // Set lastSyncedDate to 400 days ago → should only sync back 365 days
        configureSettings(lastSyncedDate = LocalDate.now(clock).minusDays(400).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.success(FoodLogResult.Data(emptyList()))

        val progressUpdates = mutableListOf<SyncProgress>()
        useCase.invoke { progressUpdates.add(it) }

        // Should be at most 366 days (today + 365 past days)
        assertTrue(progressUpdates.last().totalDays <= 366)
    }

    @Test
    @DisplayName("API succeeds with entries but all HC writes fail returns Error")
    fun allHcWritesFailReturnsError() = runTest {
        val today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns false

        val result = useCase.invoke()

        // Should not be Success(0) — should indicate write failure
        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("API succeeds with entries, some HC writes succeed returns Success with partial count")
    fun partialHcWriteSuccess() = runTest {
        val today = LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-today\""))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-yesterday\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns false

        val result = useCase.invoke()

        // Partial success — some records written
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        // Yesterday's write failed: its ETag is not kept and lastSyncedDate does not move past it
        coVerify(exactly = 0) { settingsRepository.setETag(yesterdayStr, any()) }
        coVerify(exactly = 1) { settingsRepository.setETag(todayStr, "\"etag-today\"") }
        coVerify(exactly = 0) { settingsRepository.setLastSyncedDate(any()) }
    }

    @Test
    @DisplayName("invoke sets lastSyncTimestamp on successful sync")
    fun invokeSetsLastSyncTimestampOnSuccess() = runTest {
        val today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), today) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(today, any()) } returns true

        useCase.invoke()

        coVerify { settingsRepository.setLastSyncTimestamp(match { it > 0L }) }
    }

    @Test
    @DisplayName("invoke does not set lastSyncTimestamp when all days fail")
    fun invokeDoesNotSetLastSyncTimestampOnError() = runTest {
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("fail"))

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setLastSyncTimestamp(any()) }
    }

    @Test
    @DisplayName("malformed lastSyncedDate falls back to maxPastDate and does not throw")
    fun malformedLastSyncedDateFallsBack() = runTest {
        // A corrupt date string that cannot be parsed as ISO_LOCAL_DATE
        configureSettings(lastSyncedDate = "not-a-date")
        val today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)

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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-today\""))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry), "\"etag-yesterday\""))
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.Data(emptyList()))

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setLastSyncedMeals(any()) }
    }

    @Test
    @DisplayName("invoke maps FoodLogEntry to SyncedMealSummary correctly")
    fun mapsFoodLogEntryToSyncedMealSummaryCorrectly() = runTest {
        val today = LocalDate.now(clock)
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
        val todayStr = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        configureSettings(lastSyncedDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE))
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.success(FoodLogResult.NotModified)

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).recordsSynced)
        assertEquals(8, result.daysProcessed)
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("NotModified for a past date counts as successful in contiguous watermark")
    fun notModifiedCountsAsSuccessfulInContiguousWatermark() = runTest {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        configureSettings(lastSyncedDate = today.minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("503"))

        useCase.invoke()

        // Should abort after 5 consecutive failures, not call all 10
        coVerify(exactly = 5) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("success after 4 consecutive failures resets counter — sync continues through all dates")
    fun successAfter4FailuresResets() = runTest {
        val today = LocalDate.now(clock)
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

        // All 8 dates (today-6 widened to the 7-day re-check window) should be processed
        // (4 fail + success resets counter + remaining succeed)
        coVerify(exactly = 8) { foodLogRepository.getFoodLog(any(), any(), any()) }
    }

    @Test
    @DisplayName("intermittent failures (fail, succeed, fail, succeed) never trigger abort")
    fun intermittentFailuresNeverAbort() = runTest {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        configureSettings(lastSyncedDate = today.minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE))

        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } returns Result.failure(Exception("503"))

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
    }

    @Test
    @DisplayName("abort with some prior successes returns SyncResult.Success with partial count")
    fun abortWithSomeSuccessesReturnsPartialSuccess() = runTest {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        configureSettings(lastSyncedDate = yesterday.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))

        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns Result.failure(Exception("Network error"))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(any(), any()) } returns true

        val result = useCase.invoke()

        // All dates (today + 7-day re-check window) should be called — single failure doesn't abort
        coVerify(exactly = 8) { foodLogRepository.getFoodLog(any(), any(), any()) }
        assertTrue(result is SyncResult.Success)
    }

    // --- Timestamp population tests ---

    @Test
    @DisplayName("synced meal summaries include timestamp derived from entry date + time")
    fun syncedMealSummariesIncludeTimestamp() = runTest {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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

    // --- ETag commit / HC write failure tests ---

    @Test
    @DisplayName("ETag is committed after a successful HC write")
    fun etagCommittedAfterSuccessfulWrite() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry), "\"e1\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true

        useCase.invoke()

        coVerifyOrder {
            nutritionRepository.writeNutritionRecords(todayStr, any())
            settingsRepository.setETag(todayStr, "\"e1\"")
        }
    }

    @Test
    @DisplayName("HC write failure: ETag not committed and the day is not counted as successful")
    fun hcWriteFailureDoesNotCommitEtag() = runTest {
        val today = LocalDate.now(clock)
        val todayStr = dateStr(today)
        val yesterdayStr = dateStr(today.minusDays(1))
        // yesterday is unsynced
        configureSettings(lastSyncedDate = dateStr(today.minusDays(2)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), yesterdayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry), "\"e-y\""))
        coEvery { nutritionRepository.writeNutritionRecords(yesterdayStr, any()) } returns false

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Error)
        coVerify(exactly = 0) { settingsRepository.setETag(any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setLastSyncedDate(any()) }
        coVerify(exactly = 0) { nutritionRepository.deleteStaleNutritionRecords(yesterdayStr, any()) }
        coVerify(exactly = 0) { nutritionRepository.writeNutritionRecords(todayStr, any()) }
    }

    @Test
    @DisplayName("Data with no ETag does not call setETag")
    fun dataWithoutEtagDoesNotCommit() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true

        useCase.invoke()

        coVerify(exactly = 0) { settingsRepository.setETag(any(), any()) }
    }

    @Test
    @DisplayName("setETag throwing does not fail the sync")
    fun setEtagFailureIsNonFatal() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { settingsRepository.setETag(any(), any()) } throws IOException("disk full")
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry), "\"e1\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
    }

    // --- Stale record reconciliation tests ---

    @Test
    @DisplayName("successful write reconciles stale HC records with the fetched entries")
    fun successfulWriteReconcilesStaleRecords() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        val entries = listOf(testEntry, testEntry.copy(id = 2))
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(entries, "\"e1\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true

        useCase.invoke()

        coVerifyOrder {
            nutritionRepository.writeNutritionRecords(todayStr, entries)
            nutritionRepository.deleteStaleNutritionRecords(todayStr, entries)
        }
    }

    @Test
    @DisplayName("empty Data (all meals deleted on server) reconciles with an empty list and commits ETag")
    fun emptyDataReconcilesAndCommitsEtag() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(emptyList(), "\"empty\""))

        useCase.invoke()

        coVerify(exactly = 1) { nutritionRepository.deleteStaleNutritionRecords(todayStr, emptyList()) }
        coVerify(exactly = 1) { settingsRepository.setETag(todayStr, "\"empty\"") }
    }

    @Test
    @DisplayName("NotModified and API errors never reconcile (never delete because the API failed)")
    fun notModifiedOrApiErrorDoesNotReconcile() = runTest {
        val today = LocalDate.now(clock)
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), dateStr(today)) } returns
            Result.failure(IOException("network"))

        useCase.invoke()

        coVerify(exactly = 0) { nutritionRepository.deleteStaleNutritionRecords(any(), any()) }
    }

    @Test
    @DisplayName("reconcile failure is logged but the day still counts and its ETag is committed")
    fun reconcileFailureIsNonFatal() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry), "\"e1\""))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true
        coEvery { nutritionRepository.deleteStaleNutritionRecords(todayStr, any()) } returns false

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).recordsSynced)
        coVerify(exactly = 1) { settingsRepository.setETag(todayStr, "\"e1\"") }
    }

    // --- Re-check window tests ---

    @Test
    @DisplayName("meals added to an already-synced past day within the window are synced")
    fun recheckWindowPicksUpLateEdits() = runTest {
        val today = LocalDate.now(clock)
        val threeDaysAgo = dateStr(today.minusDays(3))
        configureSettings(lastSyncedDate = dateStr(today.minusDays(1)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), threeDaysAgo) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry), "\"new\""))
        coEvery { nutritionRepository.writeNutritionRecords(threeDaysAgo, any()) } returns true

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        coVerify(exactly = 1) { nutritionRepository.writeNutritionRecords(threeDaysAgo, any()) }
        // Re-checked days at/before the watermark never move it
        coVerify(exactly = 0) { settingsRepository.setLastSyncedDate(any()) }
    }

    @Test
    @DisplayName("failure on an already-synced re-check day does not block advancing the watermark")
    fun recheckFailureDoesNotBlockWatermark() = runTest {
        val today = LocalDate.now(clock)
        val yesterdayStr = dateStr(today.minusDays(1))
        configureSettings(lastSyncedDate = dateStr(today.minusDays(2)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), dateStr(today.minusDays(5))) } returns
            Result.failure(IOException("transient"))

        useCase.invoke()

        coVerify(exactly = 1) { settingsRepository.setLastSyncedDate(yesterdayStr) }
    }

    // --- Backfill ordering / checkpoint tests ---

    @Test
    @DisplayName("fetches today first, then past dates oldest to newest")
    fun fetchOrderTodayThenOldestFirst() = runTest {
        val today = LocalDate.now(clock)
        configureSettings(lastSyncedDate = dateStr(today.minusDays(10)))
        val fetched = mutableListOf<String>()
        coEvery { foodLogRepository.getFoodLog(any(), any(), any()) } answers {
            fetched.add(thirdArg())
            Result.success(FoodLogResult.NotModified)
        }

        useCase.invoke()

        val expected = listOf(dateStr(today)) + (9 downTo 1).map { dateStr(today.minusDays(it.toLong())) }
        assertEquals(expected, fetched)
    }

    @Test
    @DisplayName("lastSyncedDate is checkpointed per day, so an interrupted backfill keeps its progress")
    fun interruptedBackfillKeepsCheckpoint() = runTest {
        val today = LocalDate.now(clock)
        configureSettings(lastSyncedDate = dateStr(today.minusDays(10)))
        coEvery { foodLogRepository.getFoodLog(any(), any(), dateStr(today.minusDays(5))) } throws
            CancellationException("worker stopped")

        assertFailsWith<CancellationException> { useCase.invoke() }

        coVerifyOrder {
            settingsRepository.setLastSyncedDate(dateStr(today.minusDays(9)))
            settingsRepository.setLastSyncedDate(dateStr(today.minusDays(8)))
            settingsRepository.setLastSyncedDate(dateStr(today.minusDays(7)))
            settingsRepository.setLastSyncedDate(dateStr(today.minusDays(6)))
        }
        coVerify(exactly = 0) { settingsRepository.setLastSyncedDate(dateStr(today.minusDays(5))) }
    }

    @Test
    @DisplayName("setLastSyncedDate throwing does not escape invoke")
    fun setLastSyncedDateFailureIsNonFatal() = runTest {
        val today = LocalDate.now(clock)
        configureSettings(lastSyncedDate = dateStr(today.minusDays(2)))
        coEvery { settingsRepository.setLastSyncedDate(any()) } throws IOException("DataStore write failed")

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        coVerify { settingsRepository.setLastSyncTimestamp(any()) }
    }

    // --- lastSyncTimestamp tests ---

    @Test
    @DisplayName("successful sync with no new meals (all 304) still updates lastSyncTimestamp")
    fun successWithNoNewMealsSetsTimestamp() = runTest {
        configureSettings()

        val result = useCase.invoke()

        assertTrue(result is SyncResult.Success)
        coVerify(exactly = 1) { settingsRepository.setLastSyncTimestamp(clock.millis()) }
    }

    @Test
    @DisplayName("CancellationException from setLastSyncTimestamp propagates")
    fun cancellationFromTimestampWritePropagates() = runTest {
        configureSettings()
        coEvery { settingsRepository.setLastSyncTimestamp(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { useCase.invoke() }
    }

    @Test
    @DisplayName("CancellationException from setLastSyncedMeals propagates")
    fun cancellationFromMealsWritePropagates() = runTest {
        val todayStr = dateStr(LocalDate.now(clock))
        configureSettings()
        coEvery { foodLogRepository.getFoodLog(any(), any(), todayStr) } returns
            Result.success(FoodLogResult.Data(listOf(testEntry)))
        coEvery { nutritionRepository.writeNutritionRecords(todayStr, any()) } returns true
        coEvery { settingsRepository.setLastSyncedMeals(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { useCase.invoke() }
    }
}
