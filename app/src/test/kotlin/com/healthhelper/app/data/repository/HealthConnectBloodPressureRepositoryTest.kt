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
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

    private val mockContext: Context = mockk {
        every { packageName } returns "com.healthhelper.app"
    }

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
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null, context = mockContext)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns true on successful insert")
    fun writeReturnsTrueOnSuccess() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.writeBloodPressureRecord(testReading)
        assertTrue(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns false on SecurityException")
    fun writeReturnsFalseOnSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("writeBloodPressureRecord returns false on general Exception")
    fun writeReturnsFalseOnGeneralException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.writeBloodPressureRecord(testReading)
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeBloodPressureRecord")
    fun writePropagateCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.writeBloodPressureRecord(testReading)
        }
    }

    // --- getLastReading tests ---

    @Test
    @DisplayName("getLastReading returns null when HealthConnectClient is null")
    fun getLastReadingReturnsNullWhenClientNull() = runTest {
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null, context = mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getLastReading()
        assertNull(result)
    }

    @Test
    @DisplayName("CancellationException propagates through getLastReading")
    fun getLastReadingPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.getLastReading()
        }
    }

    // --- getReadings tests ---

    @Test
    @DisplayName("getReadingsResult throws when HealthConnectClient is null (unavailable is not 'no data')")
    fun getReadingsThrowsWhenClientNull() = runTest {
        val repository = HealthConnectBloodPressureRepository(healthConnectClient = null, context = mockContext)
        assertFailsWith<IllegalStateException> {
            repository.getReadingsResult(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
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

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(2, callCount)
    }

    @Test
    @DisplayName("CancellationException propagates through getReadings")
    fun getReadingsPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        assertFailsWith<CancellationException> {
            repository.getReadings(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }

    // --- getReadingsResult failure / paging tests ---

    @Test
    @DisplayName("getReadingsResult rethrows non-permission read failures instead of returning an empty result")
    fun getReadingsResultRethrowsGenericFailure() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } throws IllegalStateException("HC quota")

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        assertFailsWith<IllegalStateException> {
            repository.getReadingsResult(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }

    @Test
    @DisplayName("getReadingsResult stops paging once the limit is exceeded and marks the result truncated")
    fun getReadingsResultStopsPagingAtLimit() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val page1 = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page1.records } returns listOf(
            makeRecord("2026-01-01T01:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T03:00:00Z"),
        )
        every { page1.pageToken } returns "page2"
        val requests = mutableListOf<ReadRecordsRequest<BloodPressureRecord>>()
        coEvery { mockClient.readRecords(capture(requests)) } returns page1

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getReadingsResult(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
            limit = 2,
        )

        assertEquals(1, requests.size)
        assertEquals(3, requests[0].pageSize)
        assertTrue(result.truncated)
        assertEquals(3, result.readings.size)
    }

    @Test
    @DisplayName("getReadingsResult keeps paging while readings tie with the limit-th timestamp")
    fun getReadingsResultIncludesTiesAtLimit() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val page1 = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page1.records } returns listOf(
            makeRecord("2026-01-01T01:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
        )
        every { page1.pageToken } returns "page2"
        val page2 = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page2.records } returns listOf(
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T03:00:00Z"),
        )
        every { page2.pageToken } returns "page3"
        var calls = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } answers {
            calls++
            if (calls == 1) page1 else page2
        }

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getReadingsResult(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
            limit = 2,
        )

        assertEquals(2, calls)
        assertTrue(result.truncated)
        assertEquals(5, result.readings.size)
    }

    @Test
    @DisplayName("getReadingsResult reads every page and is not truncated when no limit is reached")
    fun getReadingsResultReadsAllPagesWithoutLimit() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val page1 = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page1.records } returns listOf(makeRecord("2026-01-01T01:00:00Z"))
        every { page1.pageToken } returns "page2"
        val page2 = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { page2.records } returns listOf(makeRecord("2026-01-01T02:00:00Z"))
        every { page2.pageToken } returns null
        var calls = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } answers {
            calls++
            if (calls == 1) page1 else page2
        }

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getReadingsResult(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )

        assertEquals(2, calls)
        assertFalse(result.truncated)
        assertEquals(2, result.readings.size)
    }

    @Test
    @DisplayName("getLastReading requests the newest records first with a small page")
    fun getLastReadingRequestsNewestFirst() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns emptyList()
        val request = slot<ReadRecordsRequest<BloodPressureRecord>>()
        coEvery { mockClient.readRecords(capture(request)) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        repository.getLastReading()

        assertFalse(request.captured.ascendingOrder)
        assertEquals(20, request.captured.pageSize)
    }

    @Test
    @DisplayName("getLastReading skips a newest record that fails to map and returns the next valid one")
    fun getLastReadingSkipsUnmappableNewest() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val badNewest = mockk<BloodPressureRecord>()
        every { badNewest.time } returns Instant.parse("2026-01-20T10:00:00Z")
        val valid = makeRecord("2026-01-19T10:00:00Z")
        val mockResponse = mockk<ReadRecordsResponse<BloodPressureRecord>>()
        every { mockResponse.records } returns listOf(badNewest, valid)
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<BloodPressureRecord>>()) } returns mockResponse

        val repository = HealthConnectBloodPressureRepository(mockClient, mockContext)
        val result = repository.getLastReading()

        assertNotNull(result)
        assertEquals(Instant.parse("2026-01-19T10:00:00Z"), result.timestamp)
    }

    private fun makeRecord(time: String) = BloodPressureRecord(
        time = Instant.parse(time),
        zoneOffset = ZoneOffset.UTC,
        systolic = Pressure.millimetersOfMercury(120.0),
        diastolic = Pressure.millimetersOfMercury(80.0),
        metadata = Metadata.manualEntry(),
    )
}
