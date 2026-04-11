package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface HydrationRepository {
    suspend fun getReadings(start: Instant, end: Instant): List<HydrationReading>
    suspend fun getReadingsResult(start: Instant, end: Instant): ReadingsResult<HydrationReading> =
        ReadingsResult(getReadings(start, end))
}
