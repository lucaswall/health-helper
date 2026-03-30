package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.GlucoseReading
import java.time.Instant

interface BloodGlucoseRepository {
    suspend fun writeBloodGlucoseRecord(reading: GlucoseReading): Boolean
    suspend fun getLastReading(): GlucoseReading?
    suspend fun getReadings(start: Instant, end: Instant): List<GlucoseReading>
}
