package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import javax.inject.Inject

class GetLastBloodPressureReadingUseCase @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository,
) {
    suspend fun invoke(): BloodPressureReading? = try {
        bloodPressureRepository.getLastReading()
    } catch (e: Exception) {
        null
    }
}
