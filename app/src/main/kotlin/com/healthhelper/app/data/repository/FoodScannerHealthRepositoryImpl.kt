package com.healthhelper.app.data.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import javax.inject.Inject

// Placeholder implementation — replaced by Worker-2's full implementation at merge time.
class FoodScannerHealthRepositoryImpl @Inject constructor() : FoodScannerHealthRepository {
    override suspend fun pushGlucoseReading(reading: GlucoseReading): Result<Unit> =
        Result.failure(NotImplementedError("FoodScannerHealthRepository not yet implemented"))

    override suspend fun pushBloodPressureReading(reading: BloodPressureReading): Result<Unit> =
        Result.failure(NotImplementedError("FoodScannerHealthRepository not yet implemented"))
}
