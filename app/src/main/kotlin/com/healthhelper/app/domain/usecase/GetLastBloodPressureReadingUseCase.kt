package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class GetLastBloodPressureReadingUseCase @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository,
) {
    suspend operator fun invoke(): BloodPressureReading? = try {
        bloodPressureRepository.getLastReading()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
