package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import com.healthhelper.app.domain.model.GlucoseReading
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class HealthConnectBloodGlucoseRepositoryTest {

    private val testReading = GlucoseReading(
        valueMgDl = 101,
    )

    // --- writeBloodGlucoseRecord tests ---

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false when HealthConnectClient is null")
    fun writeReturnsFalseWhenClientNull() = runTest {
        val repository = HealthConnectBloodGlucoseRepository(healthConnectClient = null)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns true on successful insert")
    fun writeReturnsTrueOnSuccess() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false on SecurityException")
    fun writeReturnsFalseOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false on general Exception")
    fun writeReturnsFalseOnGeneralException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false when insertRecords exceeds 10s timeout")
    fun writeReturnsFalseOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } coAnswers {
            delay(15_000L)
            mockk<InsertRecordsResponse>()
        }

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeBloodGlucoseRecord")
    fun writePropagateCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.writeBloodGlucoseRecord(testReading)
        }
    }

    // --- getLastReading tests ---

    @Test
    @DisplayName("getLastReading returns null when HealthConnectClient is null")
    fun getLastReadingReturnsNullWhenClientNull() = runTest {
        val repository = HealthConnectBloodGlucoseRepository(healthConnectClient = null)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null when no records exist")
    fun getLastReadingReturnsNullWhenNoRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { mockResponse.records } returns emptyList()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns most recent reading")
    fun getLastReadingReturnsMostRecent() = runTest {
        val mockClient = mockk<HealthConnectClient>()

        val olderRecord = BloodGlucoseRecord(
            time = Instant.parse("2026-01-14T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(4.5),
            metadata = Metadata.manualEntry(),
        )
        val newerRecord = BloodGlucoseRecord(
            time = Instant.parse("2026-01-15T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.6),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { mockResponse.records } returns listOf(olderRecord, newerRecord)
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.getLastReading()

        assertNotNull(result)
        assertEquals(101, result.valueMgDl)
    }

    @Test
    @DisplayName("getLastReading returns null on exception")
    fun getLastReadingReturnsNullOnException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws RuntimeException("HC error")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null when readRecords exceeds 10s timeout")
    fun getLastReadingReturnsNullOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } coAnswers {
            delay(15_000L)
            mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        }

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null on SecurityException")
    fun getLastReadingReturnsNullOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("CancellationException propagates through getLastReading")
    fun getLastReadingPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodGlucoseRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.getLastReading()
        }
    }
}
