package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading
import java.time.Instant

interface BloodPressureRepository {
    suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean
    suspend fun getLastReading(): BloodPressureReading?
    suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading>
}
