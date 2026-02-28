package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.repository.BloodPressureRepository
import javax.inject.Inject

class WriteBloodPressureReadingUseCase @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository,
) {
    suspend operator fun invoke(reading: BloodPressureReading): Boolean =
        bloodPressureRepository.writeBloodPressureRecord(reading)
}
