package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface BloodPressureRepository {
    suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean
    suspend fun getLastReading(): BloodPressureReading?
    suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading>

    /**
     * Reads this app's blood pressure records in [start, end), sorted ascending. See
     * [HydrationRepository.getReadingsResult] for the [limit] / truncation and error contract.
     */
    suspend fun getReadingsResult(
        start: Instant,
        end: Instant,
        limit: Int = Int.MAX_VALUE,
    ): ReadingsResult<BloodPressureReading>
}
