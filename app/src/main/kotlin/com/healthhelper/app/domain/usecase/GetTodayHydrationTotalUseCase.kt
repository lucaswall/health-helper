package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.repository.HydrationRepository
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class GetTodayHydrationTotalUseCase @Inject constructor(
    private val hydrationRepository: HydrationRepository,
) {
    suspend operator fun invoke(): Int = try {
        val startOfToday = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val now = Instant.now()
        hydrationRepository.getReadings(startOfToday, now).sumOf { it.volumeMl }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "GetTodayHydrationTotal: failed")
        0
    }
}
