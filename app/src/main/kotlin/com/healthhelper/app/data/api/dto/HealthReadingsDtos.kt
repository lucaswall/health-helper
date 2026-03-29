package com.healthhelper.app.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GlucoseReadingDto(
    val measuredAt: String,
    val valueMgDl: Int,
    val zoneOffset: String? = null,
    val relationToMeal: String? = null,
    val mealType: String? = null,
    val specimenSource: String? = null,
)

@Serializable
data class GlucoseReadingRequest(
    val readings: List<GlucoseReadingDto>,
)

@Serializable
data class BloodPressureReadingDto(
    val measuredAt: String,
    val systolic: Int,
    val diastolic: Int,
    val zoneOffset: String? = null,
    val bodyPosition: String? = null,
    val measurementLocation: String? = null,
)

@Serializable
data class BloodPressureReadingRequest(
    val readings: List<BloodPressureReadingDto>,
)

@Serializable
data class UpsertResponse(
    val upserted: Int,
)
