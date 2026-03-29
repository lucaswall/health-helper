package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading

interface FoodScannerHealthRepository {
    suspend fun pushGlucoseReading(reading: GlucoseReading): Result<Unit>
    suspend fun pushBloodPressureReading(reading: BloodPressureReading): Result<Unit>
}
