package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.StepsResult
import com.healthhelper.app.domain.repository.HealthConnectRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class ReadStepsUseCase @Inject constructor(
    private val repository: HealthConnectRepository,
) {
    suspend operator fun invoke(
        now: Instant = Instant.now(),
        daysBack: Long = 7,
    ): StepsResult {
        require(daysBack >= 1) { "daysBack must be >= 1, was $daysBack" }
        val start = now.minus(daysBack, ChronoUnit.DAYS)
        return repository.readSteps(start, now)
    }
}
