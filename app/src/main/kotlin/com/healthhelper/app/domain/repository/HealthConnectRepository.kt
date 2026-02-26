package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.HealthRecord
import java.time.Instant

interface HealthConnectRepository {
    suspend fun readSteps(start: Instant, end: Instant): List<HealthRecord>
}
