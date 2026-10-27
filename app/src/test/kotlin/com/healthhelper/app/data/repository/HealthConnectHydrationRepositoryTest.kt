package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Volume
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import androidx.health.connect.client.aggregate.AggregationResult
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import io.mockk.slot
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class HealthConnectHydrationRepositoryTest {

    // --- getReadings tests ---

    @Test
    @DisplayName("getReadingsResult throws when HealthConnectClient is null (unavailable is not 'no data')")
    fun getReadingsThrowsWhenClientNull() = runTest {
        val repository = HealthConnectHydrationRepository(healthConnectClient = null)
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

        val record1 = HydrationRecord(
            startTime = Instant.parse("2026-01-01T12:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T12:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(500.0),
            metadata = Metadata.manualEntry(),
        )
        val record2 = HydrationRecord(
            startTime = Instant.parse("2026-01-01T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T08:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.0),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { mockResponse.records } returns listOf(record1, record2)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } returns mockResponse

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(2, result.size)
        assertTrue(result[0].timestamp <= result[1].timestamp)
        assertEquals(250, result[0].volumeMl)
        assertEquals(500, result[1].volumeMl)
    }

    @Test
    @DisplayName("getReadings returns empty list when no records in range")
    fun getReadingsReturnsEmptyListWhenNoRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val mockResponse = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { mockResponse.records } returns emptyList()
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } returns mockResponse

        val repository = HealthConnectHydrationRepository(mockClient)
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
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } coAnswers {
            delay(15_000L)
            mockk<ReadRecordsResponse<HydrationRecord>>()
        }

        val repository = HealthConnectHydrationRepository(mockClient)
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
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } throws SecurityException("Permission denied")

        val repository = HealthConnectHydrationRepository(mockClient)
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

        val validRecord = HydrationRecord(
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T10:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.0),
            metadata = Metadata.manualEntry(),
        )
        val badRecord = mockk<HydrationRecord>()
        every { badRecord.volume } throws RuntimeException("corrupt data")

        val mockResponse = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { mockResponse.records } returns listOf(badRecord, validRecord)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } returns mockResponse

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(250, result[0].volumeMl)
    }

    @Test
    @DisplayName("getReadings returns partial results when per-page timeout occurs after accumulating records")
    fun getReadingsReturnsPartialResultsOnTimeoutAfterAccumulation() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val record = HydrationRecord(
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T10:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.0),
            metadata = Metadata.manualEntry(),
        )

        val page1Response = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page1Response.records } returns listOf(record)
        every { page1Response.pageToken } returns "page2"

        var callCount = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } coAnswers {
            callCount++
            if (callCount == 1) {
                page1Response
            } else {
                delay(15_000L)
                mockk()
            }
        }

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(2, callCount)
    }

    @Test
    @DisplayName("CancellationException propagates through getReadings")
    fun getReadingsPropagatesCancellationException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } throws CancellationException("Cancelled")

        val repository = HealthConnectHydrationRepository(mockClient)
        assertFailsWith<CancellationException> {
            repository.getReadings(
                start = Instant.parse("2026-01-01T00:00:00Z"),
                end = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
    }

    @Test
    @DisplayName("getReadings rounds fractional mL values instead of truncating")
    fun getReadingsRoundsFractionalVolumes() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val record = HydrationRecord(
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T10:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.9),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { mockResponse.records } returns listOf(record)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } returns mockResponse

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(251, result[0].volumeMl)
    }

    @Test
    @DisplayName("getReadings drops sub-0.5mL records that round to zero")
    fun getReadingsDropsSubHalfMlRecords() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-02T00:00:00Z")

        val validRecord = HydrationRecord(
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T10:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.0),
            metadata = Metadata.manualEntry(),
        )
        val tinyRecord = HydrationRecord(
            startTime = Instant.parse("2026-01-01T11:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T11:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(0.3),
            metadata = Metadata.manualEntry(),
        )

        val mockResponse = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { mockResponse.records } returns listOf(validRecord, tinyRecord)
        every { mockResponse.pageToken } returns null
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } returns mockResponse

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadings(start, end)

        assertEquals(1, result.size)
        assertEquals(250, result[0].volumeMl)
    }

    // --- getReadingsResult failure / paging tests ---

    @Test
    @DisplayName("getReadingsResult rethrows non-permission read failures instead of returning an empty result")
    fun getReadingsResultRethrowsGenericFailure() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } throws IllegalStateException("HC quota")

        val repository = HealthConnectHydrationRepository(mockClient)
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
        val page1 = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page1.records } returns listOf(
            makeRecord("2026-01-01T01:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T03:00:00Z"),
        )
        every { page1.pageToken } returns "page2"
        val requests = mutableListOf<ReadRecordsRequest<HydrationRecord>>()
        coEvery { mockClient.readRecords(capture(requests)) } returns page1

        val repository = HealthConnectHydrationRepository(mockClient)
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
        val page1 = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page1.records } returns listOf(
            makeRecord("2026-01-01T01:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T02:00:00Z"),
        )
        every { page1.pageToken } returns "page2"
        val page2 = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page2.records } returns listOf(
            makeRecord("2026-01-01T02:00:00Z"),
            makeRecord("2026-01-01T03:00:00Z"),
        )
        every { page2.pageToken } returns "page3"
        var calls = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } answers {
            calls++
            if (calls == 1) page1 else page2
        }

        val repository = HealthConnectHydrationRepository(mockClient)
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
        val page1 = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page1.records } returns listOf(makeRecord("2026-01-01T01:00:00Z"))
        every { page1.pageToken } returns "page2"
        val page2 = mockk<ReadRecordsResponse<HydrationRecord>>()
        every { page2.records } returns listOf(makeRecord("2026-01-01T02:00:00Z"))
        every { page2.pageToken } returns null
        var calls = 0
        coEvery { mockClient.readRecords(any<ReadRecordsRequest<HydrationRecord>>()) } answers {
            calls++
            if (calls == 1) page1 else page2
        }

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getReadingsResult(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-02T00:00:00Z"),
        )

        assertEquals(2, calls)
        assertFalse(result.truncated)
        assertEquals(2, result.readings.size)
    }

    @Test
    @DisplayName("getReadings maps the record's lastModifiedTime so late arrivals can be detected")
    fun getReadingsMapsLastModified() = runTest {
        val lastModified = Instant.parse("2026-01-01T15:00:00Z")
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.lastModifiedTime } returns lastModified
        val record = HydrationRecord(
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-01-01T10:00:05Z"),
            endZoneOffset = ZoneOffset.UTC,
            volume = Volume.milliliters(250.0),
            metadata = metadata,
        )
        assertEquals(lastModified, mapToHydrationReading(record).lastModified)
    }

    // --- getTotalVolumeMl tests ---

    @Test
    @DisplayName("getTotalVolumeMl returns the aggregated VOLUME_TOTAL rounded to mL")
    fun getTotalVolumeReturnsAggregate() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val aggregation = mockk<AggregationResult>()
        every { aggregation[HydrationRecord.VOLUME_TOTAL] } returns Volume.milliliters(750.6)
        coEvery { mockClient.aggregate(any()) } returns aggregation

        val repository = HealthConnectHydrationRepository(mockClient)
        val result = repository.getTotalVolumeMl(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T12:00:00Z"),
        )

        assertEquals(751, result)
    }

    @Test
    @DisplayName("getTotalVolumeMl returns 0 when there is no hydration data")
    fun getTotalVolumeReturnsZeroWhenNoData() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        val aggregation = mockk<AggregationResult>()
        every { aggregation[HydrationRecord.VOLUME_TOTAL] } returns null
        coEvery { mockClient.aggregate(any()) } returns aggregation

        val repository = HealthConnectHydrationRepository(mockClient)
        assertEquals(0, repository.getTotalVolumeMl(Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    @DisplayName("getTotalVolumeMl returns null (unavailable) when HealthConnectClient is null")
    fun getTotalVolumeNullWhenClientNull() = runTest {
        val repository = HealthConnectHydrationRepository(healthConnectClient = null)
        assertNull(repository.getTotalVolumeMl(Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    @DisplayName("getTotalVolumeMl returns null (unavailable) on a generic read failure")
    fun getTotalVolumeNullOnFailure() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.aggregate(any()) } throws IllegalStateException("HC down")

        val repository = HealthConnectHydrationRepository(mockClient)
        assertNull(repository.getTotalVolumeMl(Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    @DisplayName("getTotalVolumeMl returns null (unavailable) when aggregate exceeds the timeout")
    fun getTotalVolumeNullOnTimeout() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.aggregate(any()) } coAnswers {
            delay(15_000L)
            mockk()
        }

        val repository = HealthConnectHydrationRepository(mockClient)
        assertNull(repository.getTotalVolumeMl(Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    @DisplayName("getTotalVolumeMl rethrows SecurityException so the use case reports PermissionDenied")
    fun getTotalVolumeRethrowsSecurityException() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.aggregate(any()) } throws SecurityException("denied")

        val repository = HealthConnectHydrationRepository(mockClient)
        assertFailsWith<SecurityException> {
            repository.getTotalVolumeMl(Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z"))
        }
    }

    private fun makeRecord(time: String) = HydrationRecord(
        startTime = Instant.parse(time),
        startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.parse(time).plusSeconds(5),
        endZoneOffset = ZoneOffset.UTC,
        volume = Volume.milliliters(250.0),
        metadata = Metadata.manualEntry(),
    )
}
