package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface BloodGlucoseRepository {
    suspend fun writeBloodGlucoseRecord(reading: GlucoseReading): Boolean
    suspend fun getLastReading(): GlucoseReading?
    suspend fun getReadings(start: Instant, end: Instant): List<GlucoseReading>

    /**
     * Reads this app's glucose records in [start, end), sorted ascending. See
     * [HydrationRepository.getReadingsResult] for the [limit] / truncation and error contract.
     */
    suspend fun getReadingsResult(
        start: Instant,
        end: Instant,
        limit: Int = Int.MAX_VALUE,
    ): ReadingsResult<GlucoseReading>
}
