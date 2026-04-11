package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface BloodGlucoseRepository {
    suspend fun writeBloodGlucoseRecord(reading: GlucoseReading): Boolean
    suspend fun getLastReading(): GlucoseReading?
    suspend fun getReadings(start: Instant, end: Instant): List<GlucoseReading>
    suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<GlucoseReading> =
        ReadingsResult(getReadings(start, end))
}
