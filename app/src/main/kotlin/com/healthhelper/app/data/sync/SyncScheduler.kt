package com.healthhelper.app.data.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    companion object {
        const val WORK_NAME = "nutrition_sync"
        private const val MIN_INTERVAL_MINUTES = 15L
    }

    fun schedulePeriodic(intervalMinutes: Int) {
        val clampedInterval = maxOf(intervalMinutes.toLong(), MIN_INTERVAL_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(clampedInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelSync() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun updateInterval(minutes: Int) {
        schedulePeriodic(minutes)
    }

    fun getNextSyncTimeFlow(): Flow<Long?> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { workInfos ->
            val info = workInfos.firstOrNull() ?: return@map null
            val nextTime = info.nextScheduleTimeMillis
            if (nextTime == Long.MAX_VALUE) null else nextTime
        }
}
