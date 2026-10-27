package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Energy
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.model.MealType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthConnectNutritionRepositoryTest {

    private val testEntry = FoodLogEntry(
        id = 1,
        foodName = "Test Food",
        mealType = MealType.BREAKFAST,
        time = "08:00:00",
        calories = 300.0,
        proteinG = 10.0,
        carbsG = 40.0,
        fatG = 8.0,
        fiberG = 3.0,
        sodiumMg = 150.0,
        saturatedFatG = null,
        transFatG = null,
        sugarsG = null,
        caloriesFromFat = null,
    )

    private fun nutritionRecord(
        clientRecordId: String?,
        localDateTime: LocalDateTime,
        offset: ZoneOffset = ZoneOffset.UTC,
    ): NutritionRecord {
        val start = localDateTime.toInstant(offset)
        return NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = start.plusSeconds(60),
            endZoneOffset = offset,
            energy = Energy.kilocalories(100.0),
            metadata = if (clientRecordId != null) {
                Metadata.manualEntry(clientRecordId = clientRecordId)
            } else {
                Metadata.manualEntry()
            },
        )
    }

    private fun readResponse(
        records: List<NutritionRecord>,
        pageToken: String? = null,
    ): ReadRecordsResponse<NutritionRecord> = mockk {
        every { this@mockk.records } returns records
        every { this@mockk.pageToken } returns pageToken
    }

    // --- writeNutritionRecords ---

    @Test
    @DisplayName("writeNutritionRecords returns false when HealthConnectClient is null")
    fun nullClientReturnsFalse() = runTest {
        val repository = HealthConnectNutritionRepository(healthConnectClient = null)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("CancellationException propagates through writeNutritionRecords")
    fun cancellationExceptionPropagates() = runTest {
        val mockClient = mockk<HealthConnectClient>()
        coEvery { mockClient.insertRecords(any()) } throws CancellationException("Cancelled")
        val repository = HealthConnectNutritionRepository(mockClient)

        assertFailsWith<CancellationException> {
            repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        }
    }

    @Test
    @DisplayName("writeNutritionRecords returns false when insertRecords throws SecurityException")
    fun securityExceptionReturnsFalse() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } throws SecurityException("Permission denied")

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("writeNutritionRecords returns false when insertRecords throws general Exception")
    fun generalExceptionReturnsFalse() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } throws RuntimeException("Unexpected error")

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("writeNutritionRecords returns true on successful write")
    fun successfulWriteReturnsTrue() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } returns mockk<InsertRecordsResponse>()

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertTrue(result)
    }

    @Test
    @DisplayName("writeNutritionRecords handles empty entries list without calling insertRecords")
    fun emptyEntriesListReturnsTrue() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", emptyList())
        assertTrue(result)
        coVerify(exactly = 0) { healthConnectClient.insertRecords(any()) }
    }

    @Test
    @DisplayName("writeNutritionRecords returns false when insertRecords exceeds 10s timeout")
    fun insertTimeoutReturnsFalse() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        coEvery { healthConnectClient.insertRecords(any()) } coAnswers {
            delay(15_000L)
            mockk<InsertRecordsResponse>()
        }

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry))
        assertFalse(result)
    }

    @Test
    @DisplayName("one invalid entry is skipped; the day's valid entries are still written")
    fun invalidEntrySkipped() = runTest {
        val healthConnectClient = mockk<HealthConnectClient>()
        val inserted = slot<List<androidx.health.connect.client.records.Record>>()
        coEvery { healthConnectClient.insertRecords(capture(inserted)) } returns mockk<InsertRecordsResponse>()
        // Energy above HC's maximum (100,000 kcal) is rejected by NutritionRecord's validation
        val invalid = testEntry.copy(id = 2, calories = 1_000_000.0)

        val repository = HealthConnectNutritionRepository(healthConnectClient = healthConnectClient)
        val result = repository.writeNutritionRecords("2026-01-15", listOf(testEntry, invalid))

        assertTrue(result)
        assertEquals(1, inserted.captured.size)
        assertEquals("foodscanner-1", inserted.captured[0].metadata.clientRecordId)
    }

    // --- deleteStaleNutritionRecords ---

    @Test
    @DisplayName("deleteStale returns false when HealthConnectClient is null")
    fun deleteStaleNullClientReturnsFalse() = runTest {
        val repository = HealthConnectNutritionRepository(healthConnectClient = null)
        assertFalse(repository.deleteStaleNutritionRecords("2026-01-15", listOf(testEntry)))
    }

    @Test
    @DisplayName("deleteStale deletes only foodscanner records for the day that are missing from the entries")
    fun deleteStaleDeletesOnlyMissingRecords() = runTest {
        val client = mockk<HealthConnectClient>()
        val day = LocalDateTime.of(2026, 1, 15, 12, 0)
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } returns readResponse(
            listOf(
                nutritionRecord("foodscanner-1", day), // still present → keep
                nutritionRecord("foodscanner-2", day), // deleted on server → delete
                nutritionRecord("other-app-id", day), // not ours → keep
                nutritionRecord(null, day), // no client id → keep
                nutritionRecord("foodscanner-3", day.minusDays(1)), // previous day → keep
                // 23:30 at -05:00 is still Jan 15 locally, though Jan 16 in UTC → delete
                nutritionRecord("foodscanner-4", day.withHour(23).withMinute(30), ZoneOffset.ofHours(-5)),
            ),
        )
        coEvery { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) } returns Unit

        val repository = HealthConnectNutritionRepository(client)
        val result = repository.deleteStaleNutritionRecords("2026-01-15", listOf(testEntry))

        assertTrue(result)
        coVerify(exactly = 1) {
            client.deleteRecords(NutritionRecord::class, emptyList(), listOf("foodscanner-2", "foodscanner-4"))
        }
    }

    @Test
    @DisplayName("deleteStale with empty entries deletes all of the day's foodscanner records")
    fun deleteStaleEmptyEntriesDeletesAll() = runTest {
        val client = mockk<HealthConnectClient>()
        val day = LocalDateTime.of(2026, 1, 15, 8, 0)
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } returns readResponse(
            listOf(nutritionRecord("foodscanner-1", day), nutritionRecord("foodscanner-7", day)),
        )
        coEvery { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) } returns Unit

        val result = HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", emptyList())

        assertTrue(result)
        coVerify { client.deleteRecords(NutritionRecord::class, emptyList(), listOf("foodscanner-1", "foodscanner-7")) }
    }

    @Test
    @DisplayName("deleteStale follows pagination")
    fun deleteStaleFollowsPages() = runTest {
        val client = mockk<HealthConnectClient>()
        val day = LocalDateTime.of(2026, 1, 15, 8, 0)
        coEvery { client.readRecords(match<ReadRecordsRequest<NutritionRecord>> { it.pageToken == null }) } returns
            readResponse(listOf(nutritionRecord("foodscanner-5", day)), pageToken = "p2")
        coEvery { client.readRecords(match<ReadRecordsRequest<NutritionRecord>> { it.pageToken == "p2" }) } returns
            readResponse(listOf(nutritionRecord("foodscanner-6", day)))
        coEvery { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) } returns Unit

        val result = HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", listOf(testEntry))

        assertTrue(result)
        coVerify { client.deleteRecords(NutritionRecord::class, emptyList(), listOf("foodscanner-5", "foodscanner-6")) }
    }

    @Test
    @DisplayName("deleteStale does not call deleteRecords when nothing is stale")
    fun deleteStaleNothingStale() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } returns readResponse(
            listOf(nutritionRecord("foodscanner-1", LocalDateTime.of(2026, 1, 15, 8, 0))),
        )

        val result = HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", listOf(testEntry))

        assertTrue(result)
        coVerify(exactly = 0) { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) }
    }

    @Test
    @DisplayName("deleteStale read failure returns false and deletes nothing")
    fun deleteStaleReadFailureDeletesNothing() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } throws SecurityException("no read")

        val result = HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", emptyList())

        assertFalse(result)
        coVerify(exactly = 0) { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) }
    }

    @Test
    @DisplayName("deleteStale read timeout returns false and deletes nothing")
    fun deleteStaleReadTimeoutReturnsFalse() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } coAnswers {
            delay(15_000L)
            readResponse(emptyList())
        }

        val result = HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", emptyList())

        assertFalse(result)
        coVerify(exactly = 0) { client.deleteRecords(any<kotlin.reflect.KClass<NutritionRecord>>(), any(), any()) }
    }

    @Test
    @DisplayName("deleteStale propagates CancellationException")
    fun deleteStaleCancellationPropagates() = runTest {
        val client = mockk<HealthConnectClient>()
        coEvery { client.readRecords(any<ReadRecordsRequest<NutritionRecord>>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", emptyList())
        }
    }

    @Test
    @DisplayName("deleteStale queries a window around the day wide enough for any zone offset")
    fun deleteStaleQueriesPaddedWindow() = runTest {
        val client = mockk<HealthConnectClient>()
        val request = slot<ReadRecordsRequest<NutritionRecord>>()
        coEvery { client.readRecords(capture(request)) } returns readResponse(emptyList())

        HealthConnectNutritionRepository(client).deleteStaleNutritionRecords("2026-01-15", emptyList())

        val filter = request.captured.timeRangeFilter
        val start = filter.startTime!!
        val end = filter.endTime!!
        assertTrue(!start.isAfter(Instant.parse("2026-01-14T10:00:00Z")))
        assertTrue(!end.isBefore(Instant.parse("2026-01-16T12:00:00Z")))
    }
}
