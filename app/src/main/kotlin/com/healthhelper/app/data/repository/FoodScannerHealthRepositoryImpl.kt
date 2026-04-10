package com.healthhelper.app.data.repository

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.api.dto.BloodPressureReadingDto
import com.healthhelper.app.data.api.dto.BloodPressureReadingRequest
import com.healthhelper.app.data.api.dto.GlucoseReadingDto
import com.healthhelper.app.data.api.dto.GlucoseReadingRequest
import com.healthhelper.app.data.api.dto.HydrationReadingDto
import com.healthhelper.app.data.api.dto.HydrationReadingRequest
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.HydrationReading
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
        return apiClient.postGlucoseReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = GlucoseReadingRequest(readings = listOf(toGlucoseReadingDto(reading))),
        ).map { Unit }
    }

    override suspend fun pushBloodPressureReading(reading: BloodPressureReading): Result<Unit> {
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        return apiClient.postBloodPressureReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = BloodPressureReadingRequest(readings = listOf(toBloodPressureReadingDto(reading))),
        ).map { Unit }
    }

    override suspend fun pushGlucoseReadings(readings: List<GlucoseReading>): Result<Int> {
        if (readings.isEmpty()) return Result.success(0)
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        return apiClient.postGlucoseReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = GlucoseReadingRequest(readings = readings.map { toGlucoseReadingDto(it) }),
        )
    }

    override suspend fun pushBloodPressureReadings(readings: List<BloodPressureReading>): Result<Int> {
        if (readings.isEmpty()) return Result.success(0)
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        return apiClient.postBloodPressureReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = BloodPressureReadingRequest(readings = readings.map { toBloodPressureReadingDto(it) }),
        )
    }

    override suspend fun pushHydrationReadings(readings: List<HydrationReading>): Result<Int> {
        if (readings.isEmpty()) return Result.success(0)
        val baseUrl = settingsRepository.baseUrlFlow.first()
        val apiKey = settingsRepository.apiKeyFlow.first()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return Result.failure(Exception("Food-scanner settings not configured"))
        }
        return apiClient.postHydrationReadings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = HydrationReadingRequest(readings = readings.map { toHydrationReadingDto(it) }),
        )
    }

    private fun toGlucoseReadingDto(reading: GlucoseReading): GlucoseReadingDto =
        GlucoseReadingDto(
            measuredAt = reading.timestamp.toString(),
            valueMgDl = reading.valueMgDl,
            zoneOffset = zoneOffsetString(reading.timestamp),
            relationToMeal = reading.relationToMeal.name.lowercase(),
            mealType = reading.glucoseMealType.name.lowercase(),
            specimenSource = reading.specimenSource.name.lowercase(),
        )

    private fun toBloodPressureReadingDto(reading: BloodPressureReading): BloodPressureReadingDto =
        BloodPressureReadingDto(
            measuredAt = reading.timestamp.toString(),
            systolic = reading.systolic,
            diastolic = reading.diastolic,
            zoneOffset = zoneOffsetString(reading.timestamp),
            bodyPosition = reading.bodyPosition.name.lowercase(),
            measurementLocation = reading.measurementLocation.name.lowercase(),
        )

    private fun toHydrationReadingDto(reading: HydrationReading): HydrationReadingDto =
        HydrationReadingDto(
            measuredAt = reading.timestamp.toString(),
            volumeMl = reading.volumeMl,
            zoneOffset = reading.zoneOffset?.toString()?.let { if (it == "Z") "+00:00" else it }
                ?: zoneOffsetString(reading.timestamp),
        )

    private fun zoneOffsetString(timestamp: java.time.Instant): String {
        val raw = ZoneId.systemDefault().rules.getOffset(timestamp).toString()
        return if (raw == "Z") "+00:00" else raw
    }
}
