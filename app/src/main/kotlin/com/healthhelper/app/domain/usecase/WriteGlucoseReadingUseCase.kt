package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.model.HealthDataWriteResult
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class WriteGlucoseReadingUseCase @Inject constructor(
    private val bloodGlucoseRepository: BloodGlucoseRepository,
    private val foodScannerHealthRepository: FoodScannerHealthRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(reading: GlucoseReading): HealthDataWriteResult {
        val hcSuccess = try {
            bloodGlucoseRepository.writeBloodGlucoseRecord(reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        val fsResult = try {
            foodScannerHealthRepository.pushGlucoseReading(reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (fsResult.isSuccess) {
            try {
                settingsRepository.addDirectPushedGlucoseTimestamp(reading.timestamp.toEpochMilli())
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
