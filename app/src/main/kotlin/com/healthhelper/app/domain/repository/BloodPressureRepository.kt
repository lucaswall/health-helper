package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface BloodPressureRepository {
    suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean
    suspend fun getLastReading(): BloodPressureReading?
    suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading>
    suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<BloodPressureReading> =
        ReadingsResult(getReadings(start, end))
}
