package com.healthhelper.app.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.healthhelper.app.domain.model.HealthReadingsSyncReport
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.repository.SettingsRepository
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SyncWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val syncNutritionUseCase = mockk<SyncNutritionUseCase>()
    private val syncHealthReadingsUseCase = mockk<SyncHealthReadingsUseCase>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true).also {
        every { it.missingPermissionsAtLastNotificationFlow } returns flowOf(emptySet())
        every { it.lastPermissionNotificationTimestampFlow } returns flowOf(0L)
    }

    private fun createWorker(): SyncWorker =
        SyncWorker(context, workerParams, syncNutritionUseCase, syncHealthReadingsUseCase, settingsRepository)

    @Test
    @DisplayName("doWork returns success when sync succeeds")
    fun doWorkReturnsSuccessWhenSyncSucceeds() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    @DisplayName("doWork returns retry when sync errors")
    fun doWorkReturnsRetryWhenSyncErrors() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Error("fail")
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    @DisplayName("doWork returns failure when needs configuration")
    fun doWorkReturnsFailureWhenNeedsConfiguration() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    @DisplayName("health readings sync called after nutrition sync succeeds")
    fun healthReadingsSyncCalledAfterNutritionSync() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(3, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()

        createWorker().doWork()

        coVerify { syncHealthReadingsUseCase.invoke() }
    }

    @Test
    @DisplayName("health readings sync called after nutrition sync errors")
    fun healthReadingsSyncCalledAfterNutritionError() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Error("fail")
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()

        createWorker().doWork()

        coVerify { syncHealthReadingsUseCase.invoke() }
    }

    @Test
    @DisplayName("health readings sync failure does not affect worker result")
    fun healthReadingsSyncFailureDoesNotAffectWorkerResult() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } throws RuntimeException("health sync failed")

        val result = createWorker().doWork()

        // Nutrition sync result determines worker outcome
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    @DisplayName("health readings sync skipped when nutrition sync returns NeedsConfiguration")
    fun healthReadingsSyncSkippedWhenNeedsConfiguration() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration

        createWorker().doWork()

        coVerify(exactly = 0) { syncHealthReadingsUseCase.invoke() }
    }

    @Test
    @DisplayName("CancellationException from health readings sync propagates")
    fun cancellationExceptionFromHealthReadingsPropagates() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            createWorker().doWork()
        }
    }
}
