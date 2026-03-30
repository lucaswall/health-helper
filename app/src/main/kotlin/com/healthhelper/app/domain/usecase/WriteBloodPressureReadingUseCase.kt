package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class WriteBloodPressureReadingUseCase @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository,
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
        }

        return HealthDataWriteResult(
            healthConnectSuccess = hcSuccess,
            foodScannerResult = fsResult,
        )
    }
}
