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
    @DisplayName("getReadings returns empty list when HealthConnectClient is null")
    fun getReadingsReturnsEmptyListWhenClientNull() = runTest {
        val repository = HealthConnectHydrationRepository(healthConnectClient = null)
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
}
