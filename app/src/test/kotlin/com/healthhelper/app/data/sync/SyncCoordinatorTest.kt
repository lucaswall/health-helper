package com.healthhelper.app.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SyncCoordinatorTest {

    @Test
    @DisplayName("runIfIdle runs block and returns its result when idle")
    fun runsWhenIdle() = runTest {
        val coordinator = SyncCoordinator()

        val result = coordinator.runIfIdle { 42 }

        assertEquals(42, result)
        assertFalse(coordinator.isSyncing)
    }

    @Test
    @DisplayName("runIfIdle returns null without running block while another sync is in progress")
    fun skipsWhenBusy() = runTest {
        val coordinator = SyncCoordinator()
        val release = CompletableDeferred<Unit>()
        val job = launch { coordinator.runIfIdle { release.await() } }
        runCurrent()
        assertTrue(coordinator.isSyncing)

        var ran = false
        val second = coordinator.runIfIdle { ran = true }

        assertNull(second)
        assertFalse(ran)
        release.complete(Unit)
        job.join()
        assertFalse(coordinator.isSyncing)
    }

    @Test
    @DisplayName("lock is released when block throws")
    fun releasesOnException() = runTest {
        val coordinator = SyncCoordinator()

        runCatching { coordinator.runIfIdle { error("boom") } }

        assertFalse(coordinator.isSyncing)
        assertEquals(1, coordinator.runIfIdle { 1 })
    }
}
