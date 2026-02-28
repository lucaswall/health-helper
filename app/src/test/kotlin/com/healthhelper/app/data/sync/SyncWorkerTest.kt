package com.healthhelper.app.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.healthhelper.app.domain.model.SyncResult
import com.healthhelper.app.domain.usecase.SyncNutritionUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SyncWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val syncNutritionUseCase = mockk<SyncNutritionUseCase>()

    private fun createWorker(): SyncWorker =
        SyncWorker(context, workerParams, syncNutritionUseCase)

    @Test
    fun `doWork returns success when sync succeeds`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Success(5, 1)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns retry when sync errors`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.Error("fail")

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork returns failure when needs configuration`() = runTest {
        coEvery { syncNutritionUseCase.invoke(any()) } returns SyncResult.NeedsConfiguration

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
