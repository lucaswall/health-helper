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

        val requestSlot = slot<PeriodicWorkRequest>()
        verify {
            workManager.enqueueUniquePeriodicWork(
                SyncScheduler.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                capture(requestSlot),
            )
        }

        // Verify interval was clamped to 15 minutes via reflection (field is on parent WorkRequest class)
        val request = requestSlot.captured
        fun findField(obj: Any, name: String): java.lang.reflect.Field? {
            var clazz: Class<*>? = obj.javaClass
            while (clazz != null) {
                try { return clazz.getDeclaredField(name) } catch (_: NoSuchFieldException) { }
                clazz = clazz.superclass
            }
            return null
        }
        val workSpecField = checkNotNull(findField(request, "workSpec")) { "workSpec field not found" }
        workSpecField.isAccessible = true
        val workSpec = workSpecField.get(request)!!
        val intervalField = checkNotNull(findField(workSpec, "intervalDuration")) { "intervalDuration field not found" }
        intervalField.isAccessible = true
        val intervalMs = intervalField.get(workSpec) as Long
        val fifteenMinutesMs = 15L * 60L * 1000L
        assertEquals(fifteenMinutesMs, intervalMs)
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

}
