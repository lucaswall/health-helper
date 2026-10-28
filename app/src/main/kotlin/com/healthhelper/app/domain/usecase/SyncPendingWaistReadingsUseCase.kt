package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.FoodScannerApiException
import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.WaistReadingRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * Sends every pending waist reading to Food Scanner. Safe to call repeatedly and concurrently:
 * the server upserts by measurement time, and only readings it accepted are cleared, so a reading
 * saved mid-push stays pending. When Food Scanner refuses a batch as invalid, the readings are
 * retried one by one so a single bad reading is set aside instead of blocking the queue forever.
 *
 * @return the number of readings still pending afterwards.
 */
class SyncPendingWaistReadingsUseCase @Inject constructor(
    private val waistReadingRepository: WaistReadingRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
) {
    suspend operator fun invoke(): Int {
        val pending = waistReadingRepository.getPending()
        if (pending.isEmpty()) return 0
        for (chunk in pending.chunked(MAX_READINGS_PER_REQUEST)) {
            val result = push(chunk)
            when {
                result.isSuccess -> waistReadingRepository.markSynced(chunk.timestamps())
                isRejection(result) -> if (!isolateRejected(chunk)) break
                // Offline, server error, rate limit, bad credentials: keep everything for the next run.
                else -> break
            }
        }
        return waistReadingRepository.getPending().size
    }

    /** Sends [chunk] one reading at a time. Returns false when a transient failure stops the run. */
    private suspend fun isolateRejected(chunk: List<WaistReading>): Boolean {
        for (reading in chunk) {
            val result = push(listOf(reading))
            when {
                result.isSuccess -> waistReadingRepository.markSynced(setOf(reading.timestamp.toEpochMilli()))
                isRejection(result) -> {
                    Timber.e("Food Scanner rejected a waist reading (HTTP %d); kept aside on the phone", httpStatus(result))
                    waistReadingRepository.markRejected(reading.timestamp.toEpochMilli())
                }
                else -> return false
            }
        }
        return true
    }

    private suspend fun push(readings: List<WaistReading>): Result<Int> = try {
        foodScannerHealthRepository.pushWaistReadings(readings)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** A 4xx that resending can never fix (validation), as opposed to auth, timeout or rate limit. */
    private fun isRejection(result: Result<Int>): Boolean {
        val status = httpStatus(result) ?: return false
        return status in 400..499 && status !in NON_REJECTION_STATUSES
    }

    private fun httpStatus(result: Result<Int>): Int? =
        (result.exceptionOrNull() as? FoodScannerApiException)?.httpStatus

    private fun List<WaistReading>.timestamps(): Set<Long> = map { it.timestamp.toEpochMilli() }.toSet()

    private companion object {
        const val MAX_READINGS_PER_REQUEST = 100
        val NON_REJECTION_STATUSES = setOf(401, 403, 408, 429)
    }
}
