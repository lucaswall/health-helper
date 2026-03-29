package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import java.time.ZoneId

fun mapToBloodGlucoseRecord(reading: GlucoseReading): BloodGlucoseRecord {
    val zoneOffset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)

    val relationToMealInt = when (reading.relationToMeal) {
        RelationToMeal.GENERAL -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
        RelationToMeal.FASTING -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        RelationToMeal.BEFORE_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
        RelationToMeal.AFTER_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
        RelationToMeal.UNKNOWN -> BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
    }

    val mealTypeInt = when (reading.glucoseMealType) {
        GlucoseMealType.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        GlucoseMealType.LUNCH -> MealType.MEAL_TYPE_LUNCH
        GlucoseMealType.DINNER -> MealType.MEAL_TYPE_DINNER
        GlucoseMealType.SNACK -> MealType.MEAL_TYPE_SNACK
        GlucoseMealType.UNKNOWN -> MealType.MEAL_TYPE_UNKNOWN
    }

    val specimenSourceInt = when (reading.specimenSource) {
        SpecimenSource.CAPILLARY_BLOOD -> BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD
        SpecimenSource.INTERSTITIAL_FLUID -> BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID
        SpecimenSource.PLASMA -> BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA
        SpecimenSource.SERUM -> BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM
        SpecimenSource.TEARS -> BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS
        SpecimenSource.WHOLE_BLOOD -> BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD
        SpecimenSource.UNKNOWN -> BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN
    }

    return BloodGlucoseRecord(
        time = reading.timestamp,
        zoneOffset = zoneOffset,
        level = BloodGlucose.millimolesPerLiter(reading.toMmolL()),
        relationToMeal = relationToMealInt,
        mealType = mealTypeInt,
        specimenSource = specimenSourceInt,
        metadata = Metadata.manualEntry(
            clientRecordId = "bloodglucose-${reading.timestamp.toEpochMilli()}",
        ),
    )
}

fun mapToGlucoseReading(record: BloodGlucoseRecord): GlucoseReading {
    val relationToMeal = when (record.relationToMeal) {
        BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL -> RelationToMeal.GENERAL
        BloodGlucoseRecord.RELATION_TO_MEAL_FASTING -> RelationToMeal.FASTING
        BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL -> RelationToMeal.BEFORE_MEAL
        BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL -> RelationToMeal.AFTER_MEAL
        else -> RelationToMeal.UNKNOWN
    }

    val glucoseMealType = when (record.mealType) {
        MealType.MEAL_TYPE_BREAKFAST -> GlucoseMealType.BREAKFAST
        MealType.MEAL_TYPE_LUNCH -> GlucoseMealType.LUNCH
        MealType.MEAL_TYPE_DINNER -> GlucoseMealType.DINNER
        MealType.MEAL_TYPE_SNACK -> GlucoseMealType.SNACK
        else -> GlucoseMealType.UNKNOWN
    }

    val specimenSource = when (record.specimenSource) {
        BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD -> SpecimenSource.CAPILLARY_BLOOD
        BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID -> SpecimenSource.INTERSTITIAL_FLUID
        BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA -> SpecimenSource.PLASMA
        BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM -> SpecimenSource.SERUM
        BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS -> SpecimenSource.TEARS
        BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD -> SpecimenSource.WHOLE_BLOOD
        else -> SpecimenSource.UNKNOWN
    }

    return GlucoseReading(
        valueMgDl = GlucoseReading.fromMmolL(record.level.inMillimolesPerLiter),
        relationToMeal = relationToMeal,
        glucoseMealType = glucoseMealType,
        specimenSource = specimenSource,
        timestamp = record.time,
    )
}
