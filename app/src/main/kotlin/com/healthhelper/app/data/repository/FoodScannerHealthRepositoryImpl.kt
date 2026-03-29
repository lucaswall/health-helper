package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.api.dto.BloodPressureReadingDto
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingDto
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class FoodScannerHealthRepositoryImpl @Inject constructor(
    private val apiClient: FoodScannerApiClient,
    private val settingsRepository: SettingsRepository,
) : FoodScannerHealthRepository {

    override suspend fun pushGlucoseReading(reading: GlucoseReading): Result<Unit> {
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        val zoneOffset = zoneOffsetString(reading.timestamp)
        val dto = GlucoseReadingDto(
            measuredAt = reading.timestamp.toString(),
            valueMgDl = reading.valueMgDl,
            zoneOffset = zoneOffset,
            relationToMeal = reading.relationToMeal.name.lowercase(),
            mealType = reading.glucoseMealType.name.lowercase(),
            specimenSource = reading.specimenSource.name.lowercase(),
        )
        return apiClient.postGlucoseReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = GlucoseReadingRequest(readings = listOf(dto)),
        ).map { Unit }
    }

    override suspend fun pushBloodPressureReading(reading: BloodPressureReading): Result<Unit> {
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        val zoneOffset = zoneOffsetString(reading.timestamp)
        val dto = BloodPressureReadingDto(
            measuredAt = reading.timestamp.toString(),
            systolic = reading.systolic,
            diastolic = reading.diastolic,
            zoneOffset = zoneOffset,
            bodyPosition = reading.bodyPosition.name.lowercase(),
            measurementLocation = reading.measurementLocation.name.lowercase(),
        )
        return apiClient.postBloodPressureReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = BloodPressureReadingRequest(readings = listOf(dto)),
        ).map { Unit }
    }

    private fun zoneOffsetString(timestamp: java.time.Instant): String {
        val raw = ZoneId.systemDefault().rules.getOffset(timestamp).toString()
        return if (raw == "Z") "+00:00" else raw
    }
}
