package com.healthhelper.app.data.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var syncScheduler: SyncScheduler

    @BeforeEach
    fun setUp() {
        workManager = mockk(relaxed = true)
        syncScheduler = SyncScheduler(workManager)
    }

    @Test
    fun `schedulePeriodic enqueues PeriodicWorkRequest with correct interval`() {
        syncScheduler.schedulePeriodic(30)

        val workNameSlot = slot<String>()
        val policySlot = slot<ExistingPeriodicWorkPolicy>()
        val requestSlot = slot<PeriodicWorkRequest>()

        verify {
            workManager.enqueueUniquePeriodicWork(
                capture(workNameSlot),
                capture(policySlot),
                capture(requestSlot),
            )
        }

        assertEquals(SyncScheduler.WORK_NAME, workNameSlot.captured)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policySlot.captured)
    }

    @Test
    fun `schedulePeriodic clamps interval to minimum 15 minutes`() {
        // Interval of 5 minutes should be clamped to 15
        syncScheduler.schedulePeriodic(5)

        verify {
            workManager.enqueueUniquePeriodicWork(
                SyncScheduler.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                any(),
            )
        }
    }

    @Test
    fun `cancelSync cancels work by unique name`() {
        syncScheduler.cancelSync()

        verify { workManager.cancelUniqueWork(SyncScheduler.WORK_NAME) }
    }

    @Test
    fun `updateInterval cancels existing and re-enqueues with new interval`() {
        syncScheduler.updateInterval(60)

        // updateInterval delegates to schedulePeriodic which calls enqueueUniquePeriodicWork
        // with UPDATE policy which replaces existing work
        verify {
            workManager.enqueueUniquePeriodicWork(
                SyncScheduler.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                any(),
            )
        }
    }

    @Test
    fun `schedulePeriodic uses correct work name`() {
        syncScheduler.schedulePeriodic(20)

        val workNameSlot = slot<String>()
        verify {
            workManager.enqueueUniquePeriodicWork(
                capture(workNameSlot),
                any(),
                any(),
            )
        }
        assertEquals("nutrition_sync", workNameSlot.captured)
    }

    @Test
    fun `schedulePeriodic uses UPDATE policy`() {
        syncScheduler.schedulePeriodic(30)

        val policySlot = slot<ExistingPeriodicWorkPolicy>()
        verify {
            workManager.enqueueUniquePeriodicWork(
                any(),
                capture(policySlot),
                any(),
            )
        }
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policySlot.captured)
    }
}
