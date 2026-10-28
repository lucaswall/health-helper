package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.response.InsertRecordsResponse
import com.healthhelper.app.domain.model.HeartRateReading
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class HealthConnectHeartRateRepositoryTest {

    private val reading = HeartRateReading(beatsPerMinute = 68, timestamp = Instant.parse("2026-10-28T09:15:00Z"))

    @Test
    @DisplayName("returns false when Health Connect is unavailable")
    fun falseWhenClientNull() = runTest {
        assertFalse(HealthConnectHeartRateRepository(null).writeHeartRateRecord(reading))
    }

    @Test
    @DisplayName("inserts a HeartRateRecord and returns true")
    fun insertsHeartRateRecord() = runTest {
        val client = mockk<HealthConnectClient>()
        val inserted = slot<List<Record>>()
        coEvery { client.insertRecords(capture(inserted)) } returns mockk<InsertRecordsResponse>()

        assertTrue(HealthConnectHeartRateRepository(client).writeHeartRateRecord(reading))
        val record = inserted.captured.single() as HeartRateRecord
        assertEquals(68L, record.samples.single().beatsPerMinute)
    }

    @Test
    @DisplayName("returns false on SecurityException (heart rate permission not granted)")
    fun falseOnSecurityException() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.insertRecords(any()) } throws SecurityException("denied")

        assertFalse(HealthConnectHeartRateRepository(client).writeHeartRateRecord(reading))
    }

    @Test
    @DisplayName("returns false on unexpected exception")
    fun falseOnException() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.insertRecords(any()) } throws RuntimeException("boom")

        assertFalse(HealthConnectHeartRateRepository(client).writeHeartRateRecord(reading))
    }

    @Test
    @DisplayName("returns false when the insert exceeds the 10s timeout")
    fun falseOnTimeout() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.insertRecords(any()) } coAnswers {
            delay(15_000L)
            mockk<InsertRecordsResponse>()
        }

        assertFalse(HealthConnectHeartRateRepository(client).writeHeartRateRecord(reading))
    }

    @Test
    @DisplayName("CancellationException propagates")
    fun propagatesCancellation() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.insertRecords(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            HealthConnectHeartRateRepository(client).writeHeartRateRecord(reading)
        }
    }
}
