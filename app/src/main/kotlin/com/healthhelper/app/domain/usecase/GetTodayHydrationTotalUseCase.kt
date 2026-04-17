package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.repository.HydrationRepository
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface TodayHydrationResult {
    data class Total(val volumeMl: Int) : TodayHydrationResult
    data object PermissionDenied : TodayHydrationResult
    data object Unavailable : TodayHydrationResult
}

class GetTodayHydrationTotalUseCase @Inject constructor(
    private val hydrationRepository: HydrationRepository,
) {
    suspend operator fun invoke(): TodayHydrationResult = try {
        val startOfToday = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val now = Instant.now()
        TodayHydrationResult.Total(
            hydrationRepository.getReadings(startOfToday, now).sumOf { it.volumeMl },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        Timber.w(e, "GetTodayHydrationTotal: permission denied")
        TodayHydrationResult.PermissionDenied
    } catch (e: Exception) {
        Timber.e(e, "GetTodayHydrationTotal: failed")
        TodayHydrationResult.Unavailable
    }
}
