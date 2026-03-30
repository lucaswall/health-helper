package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.healthhelper.app.domain.model.BloodPressureReading
import com.healthhelper.app.domain.model.BodyPosition
import com.healthhelper.app.domain.model.MeasurementLocation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
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

class HealthConnectBloodPressureRepositoryTest {

    private val testReading = BloodPressureReading(
        systolic = 120,
        diastolic = 80,
        bodyPosition = BodyPosition.SITTING_DOWN,
        measurementLocation = MeasurementLocation.LEFT_UPPER_ARM,
    )

    // --- writeBloodPressureRecord tests ---

    @Test
    @DisplayName("writeBloodPressureRecord returns false when HealthConnectClient is null")
    fun writeReturnsFalseWhenClientNull() = runTest {
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns true on successful insert")
    fun writeReturnsTrueOnSuccess() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.writeBloodPressureRecord(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns false on SecurityException")
    fun writeReturnsFalseOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns false on general Exception")
    fun writeReturnsFalseOnGeneralException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns false when insertRecords exceeds 10s timeout")
    fun writeReturnsFalseOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } coAnswers {
            delay(15_000L)
            mockk<InsertRecordsResponse>()
        }

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeBloodPressureRecord")
    fun writePropagateCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.writeBloodPressureRecord(testReading)
        }
    }

    // --- getLastReading tests ---

    @Test
    @DisplayName("getLastReading returns null when HealthConnectClient is null")
    fun getLastReadingReturnsNullWhenClientNull() = runTest {
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null when no records exist")
    fun getLastReadingReturnsNullWhenNoRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns emptyList()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns most recent reading")
    fun getLastReadingReturnsMostRecent() = runTest {
        val mockClient = mockk<HealthConnectClient>()

        val olderRecord = BloodPressureRecord(
            time = Instant.parse("2026-01-14T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(110.0),
            diastolic = Pressure.millimetersOfMercury(70.0),
            metadata = Metadata.manualEntry(),
        )
        val newerRecord = BloodPressureRecord(
            time = Instant.parse("2026-01-15T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(125.0),
            diastolic = Pressure.millimetersOfMercury(85.0),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns listOf(olderRecord, newerRecord)
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getLastReading()

        assertNotNull(result)
        // Most recent: systolic 125, diastolic 85
        assertTrue(result.systolic == 125)
        assertTrue(result.diastolic == 85)
    }

    @Test
    @DisplayName("getLastReading returns null on exception (does not throw)")
    fun getLastReadingReturnsNullOnException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws RuntimeException("HC error")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("getLastReading returns null when readRecords exceeds 10s timeout")
    fun getLastReadingReturnsNullOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } coAnswers {
            delay(15_000L)
            mockk<ReadRecordsResponse<BloodPressureRecord>>()
        }

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("CancellationException propagates through getLastReading")
    fun getLastReadingPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.getLastReading()
        }
    }

    // --- getReadings tests ---

    @Test
    @DisplayName("getReadings returns empty list when HealthConnectClient is null")
    fun getReadingsReturnsEmptyListWhenClientNull() = runTest {
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null)
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

        val record1 = BloodPressureRecord(
            time = Instant.parse("2026-01-01T12:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(130.0),
            diastolic = Pressure.millimetersOfMercury(85.0),
            metadata = Metadata.manualEntry(),
        )
        val record2 = BloodPressureRecord(
            time = Instant.parse("2026-01-01T08:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(120.0),
            diastolic = Pressure.millimetersOfMercury(80.0),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns listOf(record1, record2)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(2, result.size)
        assertTrue(result[0].timestamp <= result[1].timestamp)
    }

    @Test
    @DisplayName("getReadings returns empty list when no records in range")
    fun getReadingsReturnsEmptyListWhenNoRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns emptyList()
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient)
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
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } coAnswers {
            delay(15_000L)
            mockk<ReadRecordsResponse<BloodPressureRecord>>()
        }

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getReadings(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getReadings returns empty list on SecurityException")
    fun getReadingsReturnsEmptyListOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getReadings(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getReadings excludes records that fail mapping and returns valid ones")
    fun getReadingsExcludesFailedMappingsAndReturnsValid() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val validRecord = BloodPressureRecord(
            time = Instant.parse("2026-01-01T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(120.0),
            diastolic = Pressure.millimetersOfMercury(80.0),
            metadata = Metadata.manualEntry(),
        )
        val badRecord = mockk<BloodPressureRecord>()
        every { badRecord.systolic } throws RuntimeException("corrupt data")

        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns listOf(badRecord, validRecord)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
    }

    @Test
    @DisplayName("getReadings returns partial results when per-page timeout occurs after accumulating records")
    fun getReadingsReturnsPartialResultsOnTimeoutAfterAccumulation() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val record = BloodPressureRecord(
            time = Instant.parse("2026-01-01T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            systolic = Pressure.millimetersOfMercury(120.0),
            diastolic = Pressure.millimetersOfMercury(80.0),
            metadata = Metadata.manualEntry(),
        )

        val page1Response = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page1Response.records } returns listOf(record)
        every { page1Response.pageToken } returns "page2"

        var callCount = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } coAnswers {
            callCount++
            if (callCount == 1) {
                page1Response
            } else {
                delay(15_000L)
                mockk()
            }
        }

        val repository = HealthConnectBloodPressureRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(2, callCount)
    }

    @Test
    @DisplayName("CancellationException propagates through getReadings")
    fun getReadingsPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.getReadings(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }
}
