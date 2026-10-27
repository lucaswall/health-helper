package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.HydrationReading
import com.healthhelper.app.domain.model.ReadingsResult
import java.time.Instant

interface HydrationRepository {
    suspend fun getReadings(start: Instant, end: Instant): List<HydrationReading>

    /**
     * Reads hydration records from all data origins in [start, end), sorted ascending.
     *
     * Stops once more than [limit] readings are collected (extending past the limit only to
     * include readings sharing the timestamp of the [limit]-th one) and marks the result truncated.
     *
     * @throws SecurityException when the read permission is missing.
     * @throws Exception for any other read failure (including Health Connect being unavailable).
     */
    suspend fun getReadingsResult(
        start: Instant,
        end: Instant,
        limit: Int = Int.MAX_VALUE,
    ): ReadingsResult<HydrationReading>

    /**
     * Total hydration volume in [start, end), de-duplicated across data origins by Health Connect.
     *
     * @return the total in mL, or null when the total is unavailable (Health Connect missing,
     *   timeout or read failure).
     * @throws SecurityException when the read permission is missing.
     */
    suspend fun getTotalVolumeMl(start: Instant, end: Instant): Int?
}
