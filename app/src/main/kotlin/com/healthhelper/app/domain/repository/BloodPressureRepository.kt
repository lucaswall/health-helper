package com.healthhelper.app.domain.repository

import com.healthhelper.app.domain.model.BloodPressureReading

interface BloodPressureRepository {
    suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean
    suspend fun getLastReading(): BloodPressureReading?
}
