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
        // Aggregated total: Health Connect de-duplicates overlapping sources by the user's
        // data-source priority. Null means the read failed or timed out — never report 0 then.
        val total = hydrationRepository.getTotalVolumeMl(startOfToday, now)
        if (total == null) TodayHydrationResult.Unavailable else TodayHydrationResult.Total(total)
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
