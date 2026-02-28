package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import javax.inject.Inject

class WriteBloodPressureReadingUseCase @Inject constructor() {
    suspend fun invoke(reading: BloodPressureReading): Boolean = false
}
