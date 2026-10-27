package com.healthhelper.app.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhelper.app.domain.model.FoodLogEntry
import com.healthhelper.app.domain.repository.NutritionRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

class HealthConnectNutritionRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
) : NutritionRepository {

    override suspend fun writeNutritionRecords(
        date: String,
        entries: List<FoodLogEntry>,
    ): Boolean {
        if (healthConnectClient == null) {
            Timber.w("writeNutritionRecords(%s): Health Connect not available", date)
            return false
        }
        // Map one entry at a time so a single invalid entry (unparseable date, value outside
        // HC's allowed range) is skipped instead of dropping the whole day.
        val records = entries.mapNotNull { entry ->
            try {
                mapToNutritionRecord(entry, date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "writeNutritionRecords(%s): skipping invalid entry %d", date, entry.id)
                null
            }
        }
        if (records.isEmpty()) {
            Timber.d("writeNutritionRecords(%s): no valid records to write", date)
            return true
        }
        return try {
            withTimeout(HC_TIMEOUT_MS) {
                healthConnectClient.insertRecords(records)
            }
            Timber.d("writeNutritionRecords(%s): wrote %d records", date, records.size)
            true
        } catch (e: TimeoutCancellationException) {
            Timber.e("writeNutritionRecords(%s): timed out after %dms", date, HC_TIMEOUT_MS)
            false
        } catch (e: SecurityException) {
            Timber.w(e, "writeNutritionRecords(%s): permission denied", date)
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "writeNutritionRecords(%s): failed", date)
            false
        }
    }

    override suspend fun deleteStaleNutritionRecords(
        date: String,
        currentEntries: List<FoodLogEntry>,
    ): Boolean {
        if (healthConnectClient == null) {
            Timber.w("deleteStaleNutritionRecords(%s): Health Connect not available", date)
            return false
        }
        return try {
            val localDate = LocalDate.parse(date)
            val keepIds = currentEntries.map { foodScannerClientRecordId(it.id) }.toSet()
            // Query a window wide enough for any zone offset (-12h..+14h), then keep only
            // records whose local date (at their own offset) is this day — mirrors the mapper,
            // which places each entry on `date` in the entry's own zone.
            val rangeStart = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(DAY_PADDING_SECONDS)
            val rangeEnd = localDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                .plusSeconds(DAY_PADDING_SECONDS)

            val stale = mutableListOf<String>()
            var pageToken: String? = null
            do {
                val response = withTimeout(HC_TIMEOUT_MS) {
                    healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            recordType = NutritionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(rangeStart, rangeEnd),
                            pageToken = pageToken,
                        ),
                    )
                }
                response.records.forEach { record ->
                    val clientId = record.metadata.clientRecordId ?: return@forEach
                    if (!clientId.startsWith(FOOD_SCANNER_CLIENT_ID_PREFIX)) return@forEach
                    if (clientId in keepIds) return@forEach
                    val offset = record.startZoneOffset
                        ?: ZoneId.systemDefault().rules.getOffset(record.startTime)
                    if (record.startTime.atOffset(offset).toLocalDate() == localDate) {
                        stale.add(clientId)
                    }
                }
                pageToken = response.pageToken
            } while (pageToken != null)

            if (stale.isNotEmpty()) {
                // clientRecordIds are scoped to this app, so this can only delete our own records.
                withTimeout(HC_TIMEOUT_MS) {
                    healthConnectClient.deleteRecords(
                        recordType = NutritionRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = stale,
                    )
                }
                Timber.d("deleteStaleNutritionRecords(%s): deleted %d stale records", date, stale.size)
            }
            true
        } catch (e: TimeoutCancellationException) {
            Timber.e("deleteStaleNutritionRecords(%s): timed out after %dms", date, HC_TIMEOUT_MS)
            false
        } catch (e: SecurityException) {
            Timber.w(e, "deleteStaleNutritionRecords(%s): permission denied", date)
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "deleteStaleNutritionRecords(%s): failed", date)
            false
        }
    }

    private companion object {
        const val HC_TIMEOUT_MS = 10_000L
        const val DAY_PADDING_SECONDS = 14L * 60 * 60
    }
}
