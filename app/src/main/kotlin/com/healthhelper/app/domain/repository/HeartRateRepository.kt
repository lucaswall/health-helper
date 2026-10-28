package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.HeartRateReading

interface HeartRateRepository {
    suspend fun writeHeartRateRecord(reading: HeartRateReading): Boolean
}
