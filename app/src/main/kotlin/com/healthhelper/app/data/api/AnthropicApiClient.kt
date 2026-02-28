package com.healthhelper.app.data.api

import com.healthhelper.app.domain.model.BloodPressureParseResult
import javax.inject.Inject

class AnthropicApiClient @Inject constructor() {
    suspend fun parseBloodPressureImage(apiKey: String, imageBytes: ByteArray): BloodPressureParseResult {
        return BloodPressureParseResult.Error("Not implemented")
    }
}
