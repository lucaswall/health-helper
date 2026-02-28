package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.BloodPressureReading
import javax.inject.Inject

class GetLastBloodPressureReadingUseCase @Inject constructor() {
    suspend fun invoke(): BloodPressureReading? = null
}
