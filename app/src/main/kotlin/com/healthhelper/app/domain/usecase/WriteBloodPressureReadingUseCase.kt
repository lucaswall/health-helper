package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.model.HeartRateReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HeartRateRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class WriteBloodPressureReadingUseCase @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository,
    private val heartRateRepository: HeartRateRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(reading: BloodPressureReading): HealthDataWriteResult {
        val hcSuccess = try {
            bloodPressureRepository.writeBloodPressureRecord(reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        // Written as its own HC record (HeartRateRecord) so a missing heart-rate permission
        // can never cost the user the blood pressure reading itself. Skipped when the BP write
        // failed, so a retry can't leave orphaned or duplicate heart-rate samples behind.
        val heartRateSuccess = reading.heartRate?.takeIf { hcSuccess }?.let { bpm ->
            try {
                heartRateRepository.writeHeartRateRecord(
                    HeartRateReading(
                        beatsPerMinute = bpm,
                        timestamp = reading.timestamp,
                        zoneOffset = reading.zoneOffset,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

        val fsResult = try {
            foodScannerHealthRepository.pushBloodPressureReading(reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (fsResult.isSuccess) {
            try {
                settingsRepository.addDirectPushedBpTimestamp(reading.timestamp.toEpochMilli())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: ledger write failure does not affect the result
            }
        } else if (hcSuccess && reading.heartRate != null) {
            // The background sync will push this reading from Health Connect, where the BP record
            // carries no pulse. Remember it so that push can include the heart rate.
            try {
                settingsRepository.addPendingBpHeartRate(reading.timestamp.toEpochMilli(), reading.heartRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: the server just won't get this reading's heart rate
            }
        }

        return HealthDataWriteResult(
            healthConnectSuccess = hcSuccess,
            foodScannerResult = fsResult,
            heartRateSuccess = heartRateSuccess,
        )
    }
}
