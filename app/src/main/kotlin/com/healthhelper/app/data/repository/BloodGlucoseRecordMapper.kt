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
import kotlin.math.roundToInt

private const val GLUCOSE_CLIENT_RECORD_PREFIX = "bloodglucose-"

/**
 * clientRecordVersion of this app's glucose records, by how the mg/dL value was encoded as mmol/L
 * (the only unit Health Connect stores):
 * - 0 (legacy) and 2 (current): mg/dL / 18.018, the physically accurate factor — HC's own UI shows
 *   mmol/L only, so this keeps it correct there.
 * - 1: mg/dL / 18.0 via the Jetpack mg/dL helper (builds from 2026-10-28 only).
 * Food Scanner, the source of truth, always receives the integer mg/dL the user saved; decoding
 * each record with the factor it was encoded with keeps the background-sync re-push exact.
 */
private const val GLUCOSE_HC_FACTOR_RECORD_VERSION = 1L
internal const val GLUCOSE_RECORD_VERSION = 2L

fun mapToBloodGlucoseRecord(reading: GlucoseReading): BloodGlucoseRecord {
    val zoneOffset = reading.zoneOffset ?: ZoneId.systemDefault().rules.getOffset(reading.timestamp)

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
            clientRecordId = "$GLUCOSE_CLIENT_RECORD_PREFIX${reading.timestamp.toEpochMilli()}",
            clientRecordVersion = GLUCOSE_RECORD_VERSION,
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
        valueMgDl = readMgDl(record).coerceIn(18, 720),
        relationToMeal = relationToMeal,
        glucoseMealType = glucoseMealType,
        specimenSource = specimenSource,
        timestamp = record.time,
        zoneOffset = record.zoneOffset,
    )
}

/**
 * Decodes with the factor the record was encoded with, so this app's records read back as exactly
 * the integer the user saved. Other apps' records use HC's mg/dL conversion (18.0), which is how
 * apps writing mg/dL through the Jetpack client encode them.
 */
private fun readMgDl(record: BloodGlucoseRecord): Int {
    val isOwnRecord = record.metadata.clientRecordId?.startsWith(GLUCOSE_CLIENT_RECORD_PREFIX) == true
    return if (isOwnRecord && record.metadata.clientRecordVersion != GLUCOSE_HC_FACTOR_RECORD_VERSION) {
        GlucoseReading.fromMmolL(record.level.inMillimolesPerLiter)
    } else {
        record.level.inMilligramsPerDeciliter.roundToInt()
    }
}
