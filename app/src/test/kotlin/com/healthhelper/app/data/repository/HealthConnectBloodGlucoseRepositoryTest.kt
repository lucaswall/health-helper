package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import com.healthhelper.app.domain.model.GlucoseReading
import android.content.Context
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

    private val mockContext: Context = mockk {
        every { packageName } returns "com.healthhelper.app"
    }

    private val testReading = GlucoseReading(
        valueMgDl = 101,
    )

    // --- writeBloodGlucoseRecord tests ---

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false when HealthConnectClient is null")
    fun writeReturnsFalseWhenClientNull() = runTest {
        val repository = HealthConnectBloodGlucoseRepository(healthConnectClient = null, context = mockContext)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns true on successful insert")
    fun writeReturnsTrueOnSuccess() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false on SecurityException")
    fun writeReturnsFalseOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodGlucoseRecord returns false on general Exception")
    fun writeReturnsFalseOnGeneralException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.writeBloodGlucoseRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeBloodGlucoseRecord")
    fun writePropagateCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.writeBloodGlucoseRecord(testReading)
        }
    }

    // --- getLastReading tests ---

    @Test
    @DisplayName("getLastReading returns null when HealthConnectClient is null")
    fun getLastReadingReturnsNullWhenClientNull() = runTest {
        val repository = HealthConnectBloodGlucoseRepository(healthConnectClient = null, context = mockContext)
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

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getLastReading()

        assertNotNull(result)
        assertEquals(101, result.valueMgDl)
    }

    @Test
    @DisplayName("getLastReading returns null on exception")
    fun getLastReadingReturnsNullOnException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws RuntimeException("HC error")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null on SecurityException")
    fun getLastReadingReturnsNullOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("CancellationException propagates through getLastReading")
    fun getLastReadingPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.getLastReading()
        }
    }

    // --- getReadings tests ---

    @Test
    @DisplayName("getReadings returns empty list when HealthConnectClient is null")
    fun getReadingsReturnsEmptyListWhenClientNull() = runTest {
        val repository = HealthConnectBloodGlucoseRepository(healthConnectClient = null, context = mockContext)
        val result = repository.getReadings(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getReadings returns all readings in time range, mapped and sorted by timestamp ascending")
    fun getReadingsReturnsMappedAndSortedReadings() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val record1 = BloodGlucoseRecord(
            time = Instant.parse("2026-01-01T12:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.0),
            metadata = Metadata.manualEntry(),
        )
        val record2 = BloodGlucoseRecord(
            time = Instant.parse("2026-01-01T08:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(4.5),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { mockResponse.records } returns listOf(record1, record2)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getReadings(start, end)

        assertEquals(2, result.size)
        assertTrue(result[0].timestamp <= result[1].timestamp)
    }

    @Test
    @DisplayName("getReadings returns empty list when no records in range")
    fun getReadingsReturnsEmptyListWhenNoRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { mockResponse.records } returns emptyList()
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getReadings(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getReadings returns empty list when readRecords exceeds 10s timeout")
    fun getReadingsReturnsEmptyListOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } coAnswers {
            delay(15_000L)
            mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        }

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getReadings(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getReadings rethrows SecurityException so use case can report missing permission")
    fun getReadingsRethrowsSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        assertFailsWith<SecurityException> {
            repository.getReadings(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }

    @Test
    @DisplayName("getReadings excludes records that fail mapping and returns valid ones")
    fun getReadingsExcludesFailedMappingsAndReturnsValid() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val validRecord = BloodGlucoseRecord(
            time = Instant.parse("2026-01-01T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.0),
            metadata = Metadata.manualEntry(),
        )
        val badRecord = mockk<BloodGlucoseRecord>()
        every { badRecord.relationToMeal } throws RuntimeException("corrupt data")

        val mockResponse = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { mockResponse.records } returns listOf(badRecord, validRecord)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
    }

    @Test
    @DisplayName("getReadings returns partial results when per-page timeout occurs after accumulating records")
    fun getReadingsReturnsPartialResultsOnTimeoutAfterAccumulation() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val record = BloodGlucoseRecord(
            time = Instant.parse("2026-01-01T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            level = BloodGlucose.millimolesPerLiter(5.0),
            metadata = Metadata.manualEntry(),
        )

        val page1Response = mockk<ReadRecordsResponse<BloodGlucoseRecord>>()
        every { page1Response.records } returns listOf(record)
        every { page1Response.pageToken } returns "page2"

        var callCount = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } coAnswers {
            callCount++
            if (callCount == 1) {
                page1Response
            } else {
                delay(15_000L)
                mockk()
            }
        }

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(2, callCount)
    }

    @Test
    @DisplayName("CancellationException propagates through getReadings")
    fun getReadingsPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodGlucoseRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodGlucoseRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.getReadings(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }
}
