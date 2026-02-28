package com.healthhelper.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncNutritionUseCase: SyncNutritionUseCase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker: starting nutrition sync (attempt %d)", runAttemptCount)
        return when (val result = syncNutritionUseCase.invoke()) {
            is SyncResult.Success -> {
                Timber.d("SyncWorker: success, synced %d records", result.recordsSynced)
                Result.success()
            }
            is SyncResult.Error -> {
                Timber.w("SyncWorker: error — %s, will retry", result.message)
                Result.retry()
            }
            is SyncResult.NeedsConfiguration -> {
                Timber.w("SyncWorker: needs configuration")
                Result.failure()
            }
        }
    }
}
