package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.HydrationReading

interface FoodScannerHealthRepository {
    suspend fun pushGlucoseReading(reading: GlucoseReading): Result<Unit>
    suspend fun pushBloodPressureReading(reading: BloodPressureReading): Result<Unit>
    suspend fun pushGlucoseReadings(readings: List<GlucoseReading>): Result<Int>
    suspend fun pushBloodPressureReadings(readings: List<BloodPressureReading>): Result<Int>
    suspend fun pushHydrationReadings(readings: List<HydrationReading>): Result<Int>
}
