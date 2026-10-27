package com.healthhelper.app.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.healthhelper.app.domain.model.HealthReadingsSyncReport
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.usecase.SyncHealthReadingsUseCase
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
    private val permissionNotifier = mockk<PermissionNotifier>(relaxed = true)
    private val syncCoordinator = SyncCoordinator()

    private fun createWorker(): SyncWorker =
        SyncWorker(
            context,
            workerParams,
            syncNutritionUseCase,
            syncHealthReadingsUseCase,
            permissionNotifier,
            syncCoordinator,
        )

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

    @Test
    @DisplayName("notifyIfNeeded receives the missing read permissions from the health report")
    fun notifierReceivesMissingPermissions() = runTest {
        val missing = setOf("android.permission.health.READ_HYDRATION")
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(1, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } returns
            HealthReadingsSyncReport(missingReadPermissions = missing)

        createWorker().doWork()

        coVerify(exactly = 1) { permissionNotifier.notifyIfNeeded(missing) }
    }

    @Test
    @DisplayName("notifyIfNeeded is called with empty set when all permissions are granted")
    fun notifierCalledWithEmptySetWhenGranted() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(1, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()

        createWorker().doWork()

        coVerify(exactly = 1) { permissionNotifier.notifyIfNeeded(emptySet()) }
    }

    @Test
    @DisplayName("failed health readings sync does not touch the permission notification")
    fun failedHealthSyncSkipsNotifier() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(1, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } throws RuntimeException("boom")

        createWorker().doWork()

        coVerify(exactly = 0) { permissionNotifier.notifyIfNeeded(any()) }
    }

    @Test
    @DisplayName("notifier failure does not affect worker result")
    fun notifierFailureIsNonFatal() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(1, 1)
        coEvery { syncHealthReadingsUseCase.invoke() } returns HealthReadingsSyncReport()
        coEvery { permissionNotifier.notifyIfNeeded(any()) } throws IllegalStateException("nm")

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    @DisplayName("doWork skips sync and returns success when another sync is already running")
    fun skipsWhenAnotherSyncRunning() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder = launch { syncCoordinator.runIfIdle { release.await() } }
        runCurrent()

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { syncNutritionUseCase.invoke(any()) }
        coVerify(exactly = 0) { syncHealthReadingsUseCase.invoke() }
        release.complete(Unit)
        holder.join()
    }
}
