package com.healthhelper.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.healthhelper.app.domain.model.HealthReadingsSyncReport
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncNutritionUseCase: SyncNutritionUseCase,
    private val syncHealthReadingsUseCase: SyncHealthReadingsUseCase,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, workerParams) {

    private val permissionNotifier = PermissionNotifier(context, settingsRepository)

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker: starting nutrition sync (attempt %d)", runAttemptCount)
        val nutritionResult = syncNutritionUseCase.invoke()

        if (nutritionResult is SyncResult.NeedsConfiguration) {
            Timber.w("SyncWorker: needs configuration")
            return Result.failure()
        }

        val healthReport = try {
            syncHealthReadingsUseCase.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SyncWorker: health readings sync failed (non-fatal)")
            HealthReadingsSyncReport()
        }

        try {
            permissionNotifier.notifyIfNeeded(healthReport.missingReadPermissions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SyncWorker: permission notification failed (non-fatal)")
        }

        return when (nutritionResult) {
            is SyncResult.Success -> {
                Timber.d("SyncWorker: success, synced %d records", nutritionResult.recordsSynced)
                Result.success()
            }
            is SyncResult.Error -> {
                Timber.w("SyncWorker: error — %s, will retry", nutritionResult.message)
                Result.retry()
            }
            is SyncResult.NeedsConfiguration -> Result.failure() // unreachable — handled above
        }
    }
}
