package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.MealType
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.model.SyncedMealSummary
import com.healthhelper.app.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

class InferGlucoseDefaultsUseCaseTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: InferGlucoseDefaultsUseCase

    @BeforeEach
    fun setUp() {
        settingsRepository = mockk(relaxed = true)
        useCase = InferGlucoseDefaultsUseCase(settingsRepository)
    }

    private fun meal(
        mealType: MealType = MealType.BREAKFAST,
        timestamp: Instant = Instant.now(),
    ) = SyncedMealSummary(
        foodName = "Test Food",
        mealType = mealType,
        calories = 300,
        timestamp = timestamp,
    )

    @Test
    @DisplayName("last meal < 3 hours ago returns AFTER_MEAL + mapped GlucoseMealType + CAPILLARY_BLOOD")
    fun recentMealReturnsAfterMeal() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(2, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.LUNCH, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.AFTER_MEAL, result.relationToMeal)
        assertEquals(GlucoseMealType.LUNCH, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }

    @Test
    @DisplayName("last meal 3-8 hours ago returns UNKNOWN relation + UNKNOWN meal type + CAPILLARY_BLOOD")
    fun middleWindowReturnsUnknown() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(5, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.BREAKFAST, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.UNKNOWN, result.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }

    @Test
    @DisplayName("last meal >= 8 hours ago returns FASTING + UNKNOWN meal type + CAPILLARY_BLOOD")
    fun oldMealReturnsFasting() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(9, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.DINNER, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.FASTING, result.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }

    @Test
    @DisplayName("no synced meals returns all UNKNOWN + CAPILLARY_BLOOD")
    fun noMealsReturnsAllUnknown() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.UNKNOWN, result.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }

    @Test
    @DisplayName("meal with Instant.EPOCH timestamp treated as no data")
    fun epochTimestampTreatedAsNoData() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.BREAKFAST, Instant.EPOCH)))

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.UNKNOWN, result.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }

    @Test
    @DisplayName("MealType BREAKFAST maps to GlucoseMealType BREAKFAST")
    fun breakfastMapping() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(1, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.BREAKFAST, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(GlucoseMealType.BREAKFAST, result.glucoseMealType)
    }

    @Test
    @DisplayName("MealType DINNER maps to GlucoseMealType DINNER")
    fun dinnerMapping() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(1, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.DINNER, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(GlucoseMealType.DINNER, result.glucoseMealType)
    }

    @Test
    @DisplayName("MealType SNACK maps to GlucoseMealType SNACK")
    fun snackMapping() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(1, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.SNACK, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(GlucoseMealType.SNACK, result.glucoseMealType)
    }

    @Test
    @DisplayName("MealType UNKNOWN maps to GlucoseMealType UNKNOWN")
    fun unknownMapping() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        val mealTime = now.minus(1, ChronoUnit.HOURS)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.UNKNOWN, mealTime)))

        val result = useCase.invoke(now)

        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
    }

    @Test
    @DisplayName("SpecimenSource is always CAPILLARY_BLOOD regardless of meal state")
    fun specimenSourceAlwaysCapillaryBlood() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        // No meals
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, useCase.invoke(now).specimenSource)

        // Recent meal
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.LUNCH, now.minus(1, ChronoUnit.HOURS))))
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, useCase.invoke(now).specimenSource)

        // Old meal (fasting)
        every { settingsRepository.lastSyncedMealsFlow } returns flowOf(listOf(meal(MealType.LUNCH, now.minus(10, ChronoUnit.HOURS))))
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, useCase.invoke(now).specimenSource)
    }

    @Test
    @DisplayName("SettingsRepository read exception returns all UNKNOWN + CAPILLARY_BLOOD")
    fun repositoryExceptionReturnsDefaults() = runTest {
        val now = Instant.parse("2026-03-01T14:00:00Z")
        every { settingsRepository.lastSyncedMealsFlow } returns flow { throw RuntimeException("DataStore error") }

        val result = useCase.invoke(now)

        assertEquals(RelationToMeal.UNKNOWN, result.relationToMeal)
        assertEquals(GlucoseMealType.UNKNOWN, result.glucoseMealType)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, result.specimenSource)
    }
}
