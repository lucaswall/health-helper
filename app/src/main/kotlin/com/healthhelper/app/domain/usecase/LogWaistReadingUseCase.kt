package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.repository.WaistReadingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

enum class WaistSaveResult {
    /** Food Scanner has the reading. */
    SYNCED,

    /** Saved on this phone; the background sync will send it to Food Scanner. */
    PENDING,

    /** Saved on this phone, but Food Scanner isn't set up yet, so nothing can sync until it is. */
    PENDING_NOT_CONFIGURED,

    /** Could not be saved at all. */
    FAILED,
}

/** Saves a waist reading on the phone first, then tries to send everything pending to Food Scanner. */
class LogWaistReadingUseCase @Inject constructor(
    private val waistReadingRepository: WaistReadingRepository,
    private val syncPendingWaistReadings: SyncPendingWaistReadingsUseCase,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(reading: WaistReading): WaistSaveResult {
        try {
            // Once started, the local save completes even if the screen is closed mid-way.
            withContext(NonCancellable) { waistReadingRepository.save(reading) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Saving waist reading locally failed")
            return WaistSaveResult.FAILED
        }
        val configured = try {
            settingsRepository.isConfigured()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        if (!configured) return WaistSaveResult.PENDING_NOT_CONFIGURED
        val stillPending = try {
            syncPendingWaistReadings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Sending waist readings failed; they stay pending")
            1
        }
        return if (stillPending == 0) WaistSaveResult.SYNCED else WaistSaveResult.PENDING
    }
}
