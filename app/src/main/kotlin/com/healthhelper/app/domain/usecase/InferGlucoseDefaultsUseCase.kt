package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseDefaults
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class InferGlucoseDefaultsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(now: Instant = Instant.now()): GlucoseDefaults {
        val meals = try {
            settingsRepository.lastSyncedMealsFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to read synced meals for glucose defaults")
            return DEFAULTS
        }

        val mostRecent = meals.maxByOrNull { it.timestamp }
            ?: return DEFAULTS

        if (mostRecent.timestamp == Instant.EPOCH) {
            return DEFAULTS
        }

        val elapsed = Duration.between(mostRecent.timestamp, now)
        if (elapsed.isNegative) return DEFAULTS
        val hours = elapsed.toHours()

        return when {
            hours < 3 -> GlucoseDefaults(
                relationToMeal = RelationToMeal.AFTER_MEAL,
                glucoseMealType = mapMealType(mostRecent.mealType),
                specimenSource = SpecimenSource.CAPILLARY_BLOOD,
            )
            hours < 8 -> DEFAULTS
            else -> GlucoseDefaults(
                relationToMeal = RelationToMeal.FASTING,
                glucoseMealType = GlucoseMealType.UNKNOWN,
                specimenSource = SpecimenSource.CAPILLARY_BLOOD,
            )
        }
    }

    private fun mapMealType(mealType: com.healthhelper.app.domain.model.MealType): GlucoseMealType =
        when (mealType) {
            com.healthhelper.app.domain.model.MealType.BREAKFAST -> GlucoseMealType.BREAKFAST
            com.healthhelper.app.domain.model.MealType.LUNCH -> GlucoseMealType.LUNCH
            com.healthhelper.app.domain.model.MealType.DINNER -> GlucoseMealType.DINNER
            com.healthhelper.app.domain.model.MealType.SNACK -> GlucoseMealType.SNACK
            com.healthhelper.app.domain.model.MealType.UNKNOWN -> GlucoseMealType.UNKNOWN
        }

    private companion object {
        val DEFAULTS = GlucoseDefaults(
            relationToMeal = RelationToMeal.UNKNOWN,
            glucoseMealType = GlucoseMealType.UNKNOWN,
            specimenSource = SpecimenSource.CAPILLARY_BLOOD,
        )
    }
}
