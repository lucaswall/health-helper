package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.repository.HealthConnectRepository
import com.healthhelper.app.domain.model.HealthRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class ReadStepsUseCase @Inject constructor(
    private val repository: HealthConnectRepository,
) {
    suspend operator fun invoke(
        now: Instant = Instant.now(),
        daysBack: Long = 7,
    ): List<HealthRecord> {
        val start = now.minus(daysBack, ChronoUnit.DAYS)
        return repository.readSteps(start, now)
    }
}
