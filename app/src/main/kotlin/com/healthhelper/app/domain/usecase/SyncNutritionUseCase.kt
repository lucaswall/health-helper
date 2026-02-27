package com.healthhelper.app.domain.usecase

import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.domain.model.SyncProgress
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import javax.inject.Inject

class SyncNutritionUseCase @Inject constructor(
    private val apiClient: FoodScannerApiClient,
    private val nutritionRepository: NutritionRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun invoke(onProgress: (SyncProgress) -> Unit = {}): SyncResult {
        return SyncResult.NeedsConfiguration
    }
}
