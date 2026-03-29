package com.healthhelper.app.data.repository

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloodGlucoseRecordMapperTest {

    private val testTimestamp = Instant.parse("2026-02-28T10:00:00Z")

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps 100 mg/dL to approximately 5.55 mmol/L")
    fun mapsValueCorrectly() {
        val reading = GlucoseReading(valueMgDl = 100, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(5.55, record.level.inMillimolesPerLiter, 0.01)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps GENERAL relation to meal")
    fun mapsRelationToMealGeneral() {
        val reading = GlucoseReading(valueMgDl = 101, relationToMeal = RelationToMeal.GENERAL, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL, record.relationToMeal)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps FASTING relation to meal")
    fun mapsRelationToMealFasting() {
        val reading = GlucoseReading(valueMgDl = 101, relationToMeal = RelationToMeal.FASTING, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_FASTING, record.relationToMeal)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps BEFORE_MEAL relation to meal")
    fun mapsRelationToMealBeforeMeal() {
        val reading = GlucoseReading(valueMgDl = 101, relationToMeal = RelationToMeal.BEFORE_MEAL, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL, record.relationToMeal)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps AFTER_MEAL relation to meal")
    fun mapsRelationToMealAfterMeal() {
        val reading = GlucoseReading(valueMgDl = 101, relationToMeal = RelationToMeal.AFTER_MEAL, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL, record.relationToMeal)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps UNKNOWN relation to meal")
    fun mapsRelationToMealUnknown() {
        val reading = GlucoseReading(valueMgDl = 101, relationToMeal = RelationToMeal.UNKNOWN, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN, record.relationToMeal)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps BREAKFAST meal type")
    fun mapsMealTypeBreakfast() {
        val reading = GlucoseReading(valueMgDl = 101, glucoseMealType = GlucoseMealType.BREAKFAST, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, record.mealType)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps LUNCH meal type")
    fun mapsMealTypeLunch() {
        val reading = GlucoseReading(valueMgDl = 101, glucoseMealType = GlucoseMealType.LUNCH, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(MealType.MEAL_TYPE_LUNCH, record.mealType)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps DINNER meal type")
    fun mapsMealTypeDinner() {
        val reading = GlucoseReading(valueMgDl = 101, glucoseMealType = GlucoseMealType.DINNER, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(MealType.MEAL_TYPE_DINNER, record.mealType)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps SNACK meal type")
    fun mapsMealTypeSnack() {
        val reading = GlucoseReading(valueMgDl = 101, glucoseMealType = GlucoseMealType.SNACK, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(MealType.MEAL_TYPE_SNACK, record.mealType)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps UNKNOWN meal type")
    fun mapsMealTypeUnknown() {
        val reading = GlucoseReading(valueMgDl = 101, glucoseMealType = GlucoseMealType.UNKNOWN, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(MealType.MEAL_TYPE_UNKNOWN, record.mealType)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps CAPILLARY_BLOOD specimen source")
    fun mapsSpecimenSourceCapillaryBlood() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.CAPILLARY_BLOOD, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps INTERSTITIAL_FLUID specimen source")
    fun mapsSpecimenSourceInterstitialFluid() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.INTERSTITIAL_FLUID, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps PLASMA specimen source")
    fun mapsSpecimenSourcePlasma() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.PLASMA, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps SERUM specimen source")
    fun mapsSpecimenSourceSerum() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.SERUM, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps TEARS specimen source")
    fun mapsSpecimenSourceTears() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.TEARS, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps WHOLE_BLOOD specimen source")
    fun mapsSpecimenSourceWholeBlood() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.WHOLE_BLOOD, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord maps UNKNOWN specimen source")
    fun mapsSpecimenSourceUnknown() {
        val reading = GlucoseReading(valueMgDl = 101, specimenSource = SpecimenSource.UNKNOWN, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN, record.specimenSource)
    }

    @Test
    @DisplayName("mapToBloodGlucoseRecord sets clientRecordId with bloodglucose prefix")
    fun setsClientRecordId() {
        val reading = GlucoseReading(valueMgDl = 101, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(reading)
        assertTrue(record.metadata.clientRecordId?.startsWith("bloodglucose-") == true)
    }

    // --- Reverse mapping tests ---

    @Test
    @DisplayName("mapToGlucoseReading extracts 5.55 mmol/L as 100 mg/dL")
    fun reverseMapExtractsValue() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.55),
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(100, reading.valueMgDl)
    }

    @Test
    @DisplayName("round-trip: create reading -> map to HC -> map back -> valueMgDl matches")
    fun roundTripPreservesValue() {
        val original = GlucoseReading(valueMgDl = 100, timestamp = testTimestamp)
        val record = mapToBloodGlucoseRecord(original)
        val restored = mapToGlucoseReading(record)
        assertEquals(original.valueMgDl, restored.valueMgDl)
    }

    @Test
    @DisplayName("mapToGlucoseReading reverse maps GENERAL relation to meal")
    fun reverseMapRelationToMealGeneral() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(RelationToMeal.GENERAL, reading.relationToMeal)
    }

    @Test
    @DisplayName("mapToGlucoseReading reverse maps FASTING relation to meal")
    fun reverseMapRelationToMealFasting() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_FASTING,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(RelationToMeal.FASTING, reading.relationToMeal)
    }

    @Test
    @DisplayName("mapToGlucoseReading maps unknown HC relation to meal to UNKNOWN")
    fun reverseMapUnknownRelationToMeal() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            relationToMeal = 999,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(RelationToMeal.UNKNOWN, reading.relationToMeal)
    }

    @Test
    @DisplayName("mapToGlucoseReading reverse maps BREAKFAST meal type")
    fun reverseMapMealTypeBreakfast() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            mealType = MealType.MEAL_TYPE_BREAKFAST,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(GlucoseMealType.BREAKFAST, reading.glucoseMealType)
    }

    @Test
    @DisplayName("mapToGlucoseReading maps unknown HC meal type to UNKNOWN")
    fun reverseMapUnknownMealType() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            mealType = 999,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(GlucoseMealType.UNKNOWN, reading.glucoseMealType)
    }

    @Test
    @DisplayName("mapToGlucoseReading reverse maps CAPILLARY_BLOOD specimen source")
    fun reverseMapSpecimenSourceCapillaryBlood() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(SpecimenSource.CAPILLARY_BLOOD, reading.specimenSource)
    }

    @Test
    @DisplayName("mapToGlucoseReading maps unknown HC specimen source to UNKNOWN")
    fun reverseMapUnknownSpecimenSource() {
        val record = BloodGlucoseRecord(
            time = testTimestamp,
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            specimenSource = 999,
            metadata = Metadata.manualEntry(),
        )
        val reading = mapToGlucoseReading(record)
        assertEquals(SpecimenSource.UNKNOWN, reading.specimenSource)
    }
}
