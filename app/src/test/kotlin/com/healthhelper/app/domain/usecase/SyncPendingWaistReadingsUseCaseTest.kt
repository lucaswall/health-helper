package com.healthhelper.app.domain.usecase

import com.healthhelper.app.domain.model.AuthenticationException
import com.healthhelper.app.domain.model.FoodScannerApiException
import com.healthhelper.app.domain.model.ServerException
import com.healthhelper.app.domain.model.WaistReading
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.WaistReadingRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

class SyncPendingWaistReadingsUseCaseTest {

    private val repository = mockk<WaistReadingRepository>()
    private val foodScanner = mockk<FoodScannerHealthRepository>()
    private val useCase = SyncPendingWaistReadingsUseCase(repository, foodScanner)

    private val r1 = WaistReading(valueCm = 104.5, timestamp = Instant.parse("2026-10-20T11:00:00Z"))
    private val r2 = WaistReading(valueCm = 103.5, timestamp = Instant.parse("2026-10-27T11:00:00Z"))

    private fun rejected() = Result.failure<Int>(FoodScannerApiException("HTTP error 400", 400))

    @Test
    @DisplayName("nothing pending: no request")
    fun nothingPending() = runTest {
        coEvery { repository.getPending() } returns emptyList()

        assertEquals(0, useCase())
        coVerify(exactly = 0) { foodScanner.pushWaistReadings(any()) }
    }

    @Test
    @DisplayName("success clears exactly the sent readings")
    fun successClearsSent() = runTest {
        coEvery { repository.getPending() } returnsMany listOf(listOf(r1, r2), emptyList())
        coEvery { foodScanner.pushWaistReadings(listOf(r1, r2)) } returns Result.success(2)
        coJustRun { repository.markSynced(any()) }

        assertEquals(0, useCase())
        coVerify { repository.markSynced(setOf(r1.timestamp.toEpochMilli(), r2.timestamp.toEpochMilli())) }
    }

    @Test
    @DisplayName("a reading saved during the push stays pending")
    fun readingSavedDuringPushStaysPending() = runTest {
        val r3 = WaistReading(valueCm = 102.0, timestamp = Instant.parse("2026-10-28T11:00:00Z"))
        coEvery { repository.getPending() } returnsMany listOf(listOf(r1), listOf(r3))
        coEvery { foodScanner.pushWaistReadings(listOf(r1)) } returns Result.success(1)
        coJustRun { repository.markSynced(any()) }

        assertEquals(1, useCase())
        coVerify { repository.markSynced(setOf(r1.timestamp.toEpochMilli())) }
    }

    @Test
    @DisplayName("an offline or server failure keeps everything pending")
    fun transientFailureKeepsPending() = runTest {
        coEvery { repository.getPending() } returns listOf(r1, r2)
        coEvery { foodScanner.pushWaistReadings(any()) } returns Result.failure(ServerException(503))

        assertEquals(2, useCase())
        coVerify(exactly = 1) { foodScanner.pushWaistReadings(any()) }
        coVerify(exactly = 0) { repository.markSynced(any()) }
        coVerify(exactly = 0) { repository.markRejected(any()) }
    }

    @Test
    @DisplayName("bad credentials are not treated as a rejected reading")
    fun authFailureIsNotRejection() = runTest {
        coEvery { repository.getPending() } returns listOf(r1)
        coEvery { foodScanner.pushWaistReadings(any()) } returns Result.failure(AuthenticationException())

        assertEquals(1, useCase())
        coVerify(exactly = 0) { repository.markRejected(any()) }
    }

    @Test
    @DisplayName("a rejected batch is retried one by one; only the bad reading is set aside")
    fun rejectedBatchIsolatesBadReading() = runTest {
        coEvery { repository.getPending() } returnsMany listOf(listOf(r1, r2), emptyList())
        coEvery { foodScanner.pushWaistReadings(listOf(r1, r2)) } returns rejected()
        coEvery { foodScanner.pushWaistReadings(listOf(r1)) } returns rejected()
        coEvery { foodScanner.pushWaistReadings(listOf(r2)) } returns Result.success(1)
        coJustRun { repository.markSynced(any()) }
        coJustRun { repository.markRejected(any()) }

        assertEquals(0, useCase())
        coVerify { repository.markRejected(r1.timestamp.toEpochMilli()) }
        coVerify { repository.markSynced(setOf(r2.timestamp.toEpochMilli())) }
    }

    @Test
    @DisplayName("going offline while isolating stops without setting anything aside")
    fun transientDuringIsolationStops() = runTest {
        coEvery { repository.getPending() } returns listOf(r1, r2)
        coEvery { foodScanner.pushWaistReadings(listOf(r1, r2)) } returns rejected()
        coEvery { foodScanner.pushWaistReadings(listOf(r1)) } returns Result.failure(ServerException(502))

        assertEquals(2, useCase())
        coVerify(exactly = 0) { repository.markRejected(any()) }
        coVerify(exactly = 0) { foodScanner.pushWaistReadings(listOf(r2)) }
    }

    @Test
    @DisplayName("large queues are sent in batches of 100")
    fun sendsInBatches() = runTest {
        val many = (0 until 150).map { WaistReading(valueCm = 100.0, timestamp = Instant.ofEpochMilli(1_700_000_000_000L + it)) }
        coEvery { repository.getPending() } returnsMany listOf(many, emptyList())
        coEvery { foodScanner.pushWaistReadings(any()) } answers { Result.success(firstArg<List<WaistReading>>().size) }
        coJustRun { repository.markSynced(any()) }

        assertEquals(0, useCase())
        coVerify(exactly = 1) { foodScanner.pushWaistReadings(match { it.size == 100 }) }
        coVerify(exactly = 1) { foodScanner.pushWaistReadings(match { it.size == 50 }) }
    }

    @Test
    @DisplayName("a thrown error keeps everything pending")
    fun exceptionKeepsPending() = runTest {
        coEvery { repository.getPending() } returns listOf(r1)
        coEvery { foodScanner.pushWaistReadings(any()) } throws IllegalStateException("boom")

        assertEquals(1, useCase())
        coVerify(exactly = 0) { repository.markSynced(any()) }
    }

    @Test
    @DisplayName("cancellation propagates")
    fun cancellationPropagates() = runTest {
        coEvery { repository.getPending() } returns listOf(r1)
        coEvery { foodScanner.pushWaistReadings(any()) } throws CancellationException("cancelled")

        assertThrows<CancellationException> { useCase() }
    }
}
