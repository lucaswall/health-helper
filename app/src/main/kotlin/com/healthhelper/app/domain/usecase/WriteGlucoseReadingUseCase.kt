package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.GlucoseReading
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import javax.inject.Inject

class WriteGlucoseReadingUseCase @Inject constructor(
    private val bloodGlucoseRepository: BloodGlucoseRepository,
) {
    suspend operator fun invoke(reading: GlucoseReading): Boolean =
        bloodGlucoseRepository.writeBloodGlucoseRecord(reading)
}
