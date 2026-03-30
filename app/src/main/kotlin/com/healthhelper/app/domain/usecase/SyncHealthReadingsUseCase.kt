package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

class SyncHealthReadingsUseCase @Inject constructor(
    private val bloodGlucoseRepository: BloodGlucoseRepository,
    private val bloodPressureRepository: BloodPressureRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun invoke() {
        if (!settingsRepository.isConfigured()) {
            Timber.d("SyncHealthReadings: settings not configured, skipping")
            return
        }

        val lastTimestamp = settingsRepository.lastGlucoseSyncTimestampFlow.first()
        val end = Instant.now()
        val start = if (lastTimestamp == 0L) {
            Instant.EPOCH
        } else {
            Instant.ofEpochMilli(lastTimestamp).minus(1, ChronoUnit.DAYS)
        }

        Timber.d(
            "SyncHealthReadings: reading from %s to %s (lastTimestamp=%d)",
            start,
            end,
            lastTimestamp,
        )

        val glucoseReadings = tryReadGlucose(start, end)
        val bpReadings = tryReadBloodPressure(start, end)

        Timber.d(
            "SyncHealthReadings: read %d glucose, %d BP readings",
            glucoseReadings.size,
            bpReadings.size,
        )

        val glucosePushed = if (glucoseReadings.isNotEmpty()) {
            pushInChunks(
                items = glucoseReadings,
                label = "glucose",
                push = { chunk -> foodScannerHealthRepository.pushGlucoseReadings(chunk) },
            )
        } else {
            glucoseReadings.size
        }

        val bpPushed = if (bpReadings.isNotEmpty()) {
            pushInChunks(
                items = bpReadings,
                label = "BP",
                push = { chunk -> foodScannerHealthRepository.pushBloodPressureReadings(chunk) },
            )
        } else {
            bpReadings.size
        }

        val glucoseFailed = glucoseReadings.isNotEmpty() && glucosePushed < glucoseReadings.size
        val bpFailed = bpReadings.isNotEmpty() && bpPushed < bpReadings.size

        if (!glucoseFailed && !bpFailed) {
            settingsRepository.setLastGlucoseSyncTimestamp(end.toEpochMilli())
            Timber.d(
                "SyncHealthReadings: done — pushed %d glucose, %d BP; updated timestamp to %d",
                glucoseReadings.size,
                bpReadings.size,
                end.toEpochMilli(),
            )
        } else {
            Timber.w(
                "SyncHealthReadings: partial failure — pushed %d/%d glucose, %d/%d BP; timestamp NOT updated",
                glucosePushed,
                glucoseReadings.size,
                bpPushed,
                bpReadings.size,
            )
        }
    }

    private suspend fun tryReadGlucose(start: Instant, end: Instant): List<GlucoseReading> {
        return try {
            bloodGlucoseRepository.getReadings(start, end)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.w(e, "SyncHealthReadings: SecurityException reading glucose — skipping")
            emptyList()
        } catch (e: Exception) {
            Timber.w(e, "SyncHealthReadings: failed to read glucose readings — skipping")
            emptyList()
        }
    }

    private suspend fun tryReadBloodPressure(start: Instant, end: Instant): List<BloodPressureReading> {
        return try {
            bloodPressureRepository.getReadings(start, end)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.w(e, "SyncHealthReadings: SecurityException reading BP — skipping")
            emptyList()
        } catch (e: Exception) {
            Timber.w(e, "SyncHealthReadings: failed to read BP readings — skipping")
            emptyList()
        }
    }

    private suspend fun <T> pushInChunks(
        items: List<T>,
        label: String,
        push: suspend (List<T>) -> Result<Int>,
    ): Int {
        var totalPushed = 0
        val chunks = items.chunked(CHUNK_SIZE)
        for ((index, chunk) in chunks.withIndex()) {
            val result = push(chunk)
            if (result.isFailure) {
                Timber.w(
                    "SyncHealthReadings: failed to push %s chunk %d/%d — %s",
                    label,
                    index + 1,
                    chunks.size,
                    result.exceptionOrNull()?.message,
                )
                return totalPushed
            }
            totalPushed += chunk.size
        }
        return totalPushed
    }

    companion object {
        private const val CHUNK_SIZE = 1000
    }
}
