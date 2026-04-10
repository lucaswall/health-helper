package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.HydrationReading
import java.time.Instant

interface HydrationRepository {
    suspend fun getReadings(start: Instant, end: Instant): List<HydrationReading>
}
