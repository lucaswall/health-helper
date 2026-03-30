# Implementation Plan

**Created:** 2026-03-29
**Source:** Inline request: Improve food log time accuracy (use zoneOffset from API) and backfill Health Connect glucose/BP readings to Food Scanner
**Linear Issues:** [HEA-176](https://linear.app/lw-claude/issue/HEA-176/add-zoneoffset-to-food-log-dtos-and-domain-model), [HEA-177](https://linear.app/lw-claude/issue/HEA-177/use-zoneoffset-in-nutritionrecordmapper), [HEA-178](https://linear.app/lw-claude/issue/HEA-178/add-range-read-methods-to-health-connect-glucose-and-bp-repositories), [HEA-179](https://linear.app/lw-claude/issue/HEA-179/add-batch-push-methods-to-foodscannerhealthrepository), [HEA-180](https://linear.app/lw-claude/issue/HEA-180/add-health-readings-sync-timestamp-to-settingsrepository), [HEA-181](https://linear.app/lw-claude/issue/HEA-181/create-synchealthreadingsusecase), [HEA-182](https://linear.app/lw-claude/issue/HEA-182/integrate-health-readings-sync-into-syncworker)
**Branch:** feat/food-log-timezone-and-health-backfill

## Context Gathered

### Codebase Analysis
- **Related files:**
  - `app/src/main/kotlin/com/healthhelper/app/data/api/dto/FoodLogResponse.kt` — `MealEntryDto` has `time: String?` but NO `zoneOffset` field. Food Scanner API sends `zoneOffset` (±HH:MM) but HealthHelper ignores it.
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogEntry.kt` — Domain model has `time: String?` but NO `zoneOffset` field
  - `app/src/main/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapper.kt` — `mapToNutritionRecord()` hardcodes `ZoneId.systemDefault()` (line 29). Falls back to `LocalTime.NOON` when time is null. Parses time via `LocalTime.parse()` which handles both HH:mm and HH:mm:ss
  - `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — Maps `MealEntryDto` to `FoodLogEntry` (lines 76-94), does not extract `zoneOffset`
  - `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncWorker.kt` — Only calls `syncNutritionUseCase.invoke()`. No health readings sync.
  - `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` — Full nutrition sync with date tracking, rate limiting, backoff. Pattern to follow for health readings sync.
  - `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — Has `lastSyncedDateFlow` for nutrition sync. No health readings sync date.
  - `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodGlucoseRepository.kt` — Has `getLastReading()` that reads last 30 days, returns most recent. Need range-read method for backfill.
  - `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepository.kt` — Same pattern as glucose, `getLastReading()` returns most recent from 30 days.
  - `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodGlucoseRepository.kt` — Interface: `writeBloodGlucoseRecord()`, `getLastReading()`. Needs `getReadings(start, end)`.
  - `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodPressureRepository.kt` — Same pattern. Needs `getReadings(start, end)`.
  - `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerHealthRepositoryImpl.kt` — Pushes single glucose/BP readings to Food Scanner. Currently wraps single reading in a list. Needs batch support for backfill.
  - `app/src/main/kotlin/com/healthhelper/app/domain/repository/FoodScannerHealthRepository.kt` — Interface: `pushGlucoseReading()`, `pushBloodPressureReading()`. Needs batch push methods.
  - `app/src/main/kotlin/com/healthhelper/app/data/api/dto/HealthReadingsDtos.kt` — DTOs already support batch (`GlucoseReadingRequest(readings: List<GlucoseReadingDto>)`). API client already supports batch POST.
- **Existing patterns:**
  - `SyncNutritionUseCase`: date-range sync with `lastSyncedDate` tracking, contiguous date advancement, consecutive failure abort, rate limiting. Pattern to follow for health readings sync.
  - Health Connect repos: `withTimeout(10_000L)`, SecurityException/TimeoutCancellationException/CancellationException handling
  - `FoodScannerHealthRepositoryImpl`: reads settings from flows, validates configured, maps domain → DTO with zone offset
  - `NutritionRecordMapper`: top-level function, tested in dedicated test file
- **Test conventions:**
  - Mapper tests: direct function calls, assertEquals on mapped fields
  - Repository tests: MockK `HealthConnectClient`, test null client, success, timeout, security, cancellation
  - Use case tests: MockK repositories, `runTest`, verify orchestration and return values
  - API client tests: `MockEngine` with JSON responses

### MCP Context
- **MCPs used:** Linear
- **Findings:** No open issues in Health Helper team. Clean slate for new work.

## Tasks

### Task 1: Add zoneOffset to food log DTOs and domain model
**Linear Issue:** [HEA-176](https://linear.app/lw-claude/issue/HEA-176/add-zoneoffset-to-food-log-dtos-and-domain-model)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/dto/FoodLogResponse.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogEntry.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/FoodLogEntryTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)

**Steps:**
1. Write tests:
   - `FoodLogEntryTest`: FoodLogEntry accepts `zoneOffset = "+05:30"` and `zoneOffset = null`
   - `FoodScannerApiClientTest`: when API response contains `zoneOffset` in a meal entry, the mapped `FoodLogEntry` has the value. When `zoneOffset` is absent/null in JSON, the mapped `FoodLogEntry` has `zoneOffset = null`
2. Run verifier (expect fail)
3. Add `val zoneOffset: String? = null` to `MealEntryDto` (after `time` field)
4. Add `val zoneOffset: String? = null` to `FoodLogEntry` (after `time` field)
5. Update `FoodScannerApiClient.getFoodLog()` mapping (line 82 area): pass `zoneOffset = entry.zoneOffset` when constructing `FoodLogEntry`
6. Run verifier (expect pass)

**Notes:**
- `zoneOffset` format from Food Scanner API: `±HH:MM` (e.g., `"+03:00"`, `"-05:00"`) or null
- Default `null` preserves backward compatibility — no breaking changes to existing code

### Task 2: Use zoneOffset in NutritionRecordMapper
**Linear Issue:** [HEA-177](https://linear.app/lw-claude/issue/HEA-177/use-zoneoffset-in-nutritionrecordmapper)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapper.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapperTest.kt` (modify)

**Steps:**
1. Write tests in `NutritionRecordMapperTest`:
   - Entry with `zoneOffset = "+05:30"` and `time = "12:30:00"`: startTime corresponds to 12:30 at +05:30 (i.e., 07:00 UTC), and `startZoneOffset` is `ZoneOffset.of("+05:30")`
   - Entry with `zoneOffset = "-03:00"` and `time = "08:00:00"`: startTime is 11:00 UTC, zone offset is -03:00
   - Entry with `zoneOffset = null`: uses system default timezone (current behavior preserved)
   - Entry with malformed `zoneOffset` (e.g., `"invalid"`): falls back to system default, logs warning
   - Existing test `nullTimeFallsBackToNoon` still passes with zoneOffset null
2. Run verifier (expect fail)
3. Update `mapToNutritionRecord()` signature: add `entry` already has `zoneOffset` via domain model (no signature change needed — `FoodLogEntry` already passed)
4. Update mapper logic:
   - If `entry.zoneOffset` is non-null, try `ZoneOffset.parse(entry.zoneOffset)`. On success, use it to construct `OffsetDateTime` (or `ZonedDateTime` with fixed offset). On `DateTimeParseException`, log warning via Timber, fall back to `ZoneId.systemDefault()`
   - If `entry.zoneOffset` is null, use `ZoneId.systemDefault()` (current behavior)
   - The `startZoneOffset` and `endZoneOffset` on the NutritionRecord should use the parsed offset
5. Run verifier (expect pass)

**Notes:**
- `ZoneOffset.parse()` handles `±HH:MM` format natively
- The `createEntry()` helper in test file needs a new `zoneOffset` parameter with default `null` to avoid breaking existing tests
- `SyncNutritionUseCase` passes `FoodLogEntry` objects to `NutritionRepository.writeNutritionRecords()` — the zoneOffset flows through automatically since it's on the domain model

### Task 3: Add range-read methods to Health Connect glucose and BP repositories
**Linear Issue:** [HEA-178](https://linear.app/lw-claude/issue/HEA-178/add-range-read-methods-to-health-connect-glucose-and-bp-repositories)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodGlucoseRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodPressureRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodGlucoseRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodGlucoseRepositoryTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepositoryTest.kt` (modify)

**Steps:**
1. Write tests for glucose repository `getReadings(start: Instant, end: Instant)`:
   - Returns empty list when Health Connect client is null
   - Returns all readings in time range, mapped to domain models, sorted by timestamp ascending
   - Returns empty list when no records in range
   - Handles timeout (10s) — returns empty list, logs warning
   - Handles SecurityException — returns empty list, logs error
   - CancellationException propagates
2. Write same test suite for BP repository `getReadings(start: Instant, end: Instant)`
3. Run verifier (expect fail)
4. Add `suspend fun getReadings(start: Instant, end: Instant): List<GlucoseReading>` to `BloodGlucoseRepository` interface
5. Add `suspend fun getReadings(start: Instant, end: Instant): List<BloodPressureReading>` to `BloodPressureRepository` interface
6. Implement in `HealthConnectBloodGlucoseRepository`:
   - Follow existing `getLastReading()` pattern (null client check, withTimeout, SecurityException, CancellationException)
   - Use `TimeRangeFilter.between(start, end)` instead of `TimeRangeFilter.after()`
   - Map all records via `mapToGlucoseReading()`, wrapping each in `runCatching` (same as `getLastReading`)
   - Sort by timestamp ascending
   - No pagination needed — 30 days of manual readings is well under the page size limit
7. Implement same pattern in `HealthConnectBloodPressureRepository`
8. Run verifier (expect pass)

**Notes:**
- `getLastReading()` already uses `runCatching` per-record to skip records that fail mapping (e.g., out-of-range values). Follow same pattern.
- Ascending sort is natural for push-to-server (oldest first)
- CGM data (e.g., Dexcom every 5 min) could produce ~8640 records in 30 days. Health Connect pagination may be needed if reading large windows. For now, single read is acceptable — the sync window is typically 1-2 days after initial backfill.

### Task 4: Add batch push methods to FoodScannerHealthRepository
**Linear Issue:** [HEA-179](https://linear.app/lw-claude/issue/HEA-179/add-batch-push-methods-to-foodscannerhealthrepository)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/FoodScannerHealthRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerHealthRepositoryImpl.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/FoodScannerHealthRepositoryImplTest.kt` (modify)

**Steps:**
1. Write tests for `pushGlucoseReadings(readings: List<GlucoseReading>)`:
   - Empty list: returns `Result.success(0)` without calling API
   - Single reading: maps correctly to DTO and calls API, returns upserted count
   - Multiple readings: all mapped correctly, single API call, returns upserted count
   - Settings not configured: returns `Result.failure`
   - API failure: returns `Result.failure` with exception
   - Verifies DTO field mapping: valueMgDl, measuredAt (ISO-8601), zoneOffset (±HH:MM), relationToMeal/mealType/specimenSource as lowercase strings
2. Write same test suite for `pushBloodPressureReadings(readings: List<BloodPressureReading>)`
3. Run verifier (expect fail)
4. Add to `FoodScannerHealthRepository` interface:
   - `suspend fun pushGlucoseReadings(readings: List<GlucoseReading>): Result<Int>`
   - `suspend fun pushBloodPressureReadings(readings: List<BloodPressureReading>): Result<Int>`
5. Implement in `FoodScannerHealthRepositoryImpl`:
   - Early return `Result.success(0)` for empty lists
   - Same settings validation as existing single-push methods
   - Map each reading to DTO (reuse existing mapping logic from `pushGlucoseReading`)
   - Extract DTO mapping into private helper functions to avoid duplication with existing single-push methods
   - Call `apiClient.postGlucoseReadings()` / `apiClient.postBloodPressureReadings()` with full batch
   - Return `Result<Int>` (upserted count from API)
6. Run verifier (expect pass)

**Notes:**
- Food Scanner API supports up to 1000 readings per batch. Chunking (if needed for CGM data) belongs in the use case, not the repository.
- Existing single-push methods (`pushGlucoseReading`, `pushBloodPressureReading`) remain unchanged — they're used by the manual entry flow.

### Task 5: Add health readings sync date to SettingsRepository
**Linear Issue:** [HEA-180](https://linear.app/lw-claude/issue/HEA-180/add-health-readings-sync-timestamp-to-settingsrepository)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify — or wherever the implementation lives)
- Test file for DataStoreSettingsRepository (modify if exists)

**Steps:**
1. Write test: `lastHealthReadingsSyncTimestampFlow` defaults to `0L`, can be set and read back
2. Run verifier (expect fail)
3. Add to `SettingsRepository` interface:
   - `val lastHealthReadingsSyncTimestampFlow: Flow<Long>`
   - `suspend fun setLastHealthReadingsSyncTimestamp(value: Long)`
4. Implement in the DataStore-backed implementation:
   - New DataStore key `last_health_readings_sync_timestamp` (Long, default 0L)
   - Follow existing `lastSyncTimestampFlow` / `setLastSyncTimestamp` pattern exactly
5. Run verifier (expect pass)

**Notes:**
- Using a timestamp (epoch millis) instead of a date string because health readings are instant-based, not date-based. The sync reads records since this timestamp.
- Default `0L` means first sync will read last 30 days (capped by Health Connect's default read limit).

**Defensive Requirements:**
- DataStore write uses `.edit{}` (not `.updateData{}`) following existing pattern

### Task 6: Create SyncHealthReadingsUseCase
**Linear Issue:** [HEA-181](https://linear.app/lw-claude/issue/HEA-181/create-synchealthreadingsusecase)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` (create)

**Steps:**
1. Write tests:
   - Settings not configured: returns early without error (health readings sync is best-effort)
   - First sync (timestamp = 0): reads from 30 days ago to now, pushes all to Food Scanner
   - Subsequent sync: reads from last sync timestamp minus 1 day (overlap buffer) to now
   - Glucose readings found and pushed successfully: updates sync timestamp
   - BP readings found and pushed successfully: updates sync timestamp
   - Mixed: some glucose, some BP, both pushed
   - No readings found in range: updates sync timestamp (no-op is still progress)
   - Glucose push fails but BP push succeeds: logs warning, does NOT update sync timestamp
   - BP push fails but glucose push succeeds: logs warning, does NOT update sync timestamp
   - Health Connect read throws SecurityException: caught, logs warning, skips that type
   - Health Connect read returns empty list: no API call made for that type
   - CancellationException propagates from any operation
   - Large batch (>1000 readings): chunked into batches of 1000 for API calls
2. Run verifier (expect fail)
3. Create `SyncHealthReadingsUseCase`:
   - `@Inject constructor(bloodGlucoseRepository: BloodGlucoseRepository, bloodPressureRepository: BloodPressureRepository, foodScannerHealthRepository: FoodScannerHealthRepository, settingsRepository: SettingsRepository)`
   - `suspend fun invoke()`:
     1. Check `settingsRepository.isConfigured()` — return early if not
     2. Read `lastHealthReadingsSyncTimestamp` from settings
     3. Calculate `start`: if timestamp is 0, use `Instant.now().minus(30, ChronoUnit.DAYS)`; otherwise `Instant.ofEpochMilli(timestamp).minus(1, ChronoUnit.DAYS)` (1-day overlap to catch late-arriving records)
     4. Calculate `end`: `Instant.now()`
     5. Read glucose readings from Health Connect via `bloodGlucoseRepository.getReadings(start, end)` — wrap in try-catch for SecurityException
     6. Read BP readings from Health Connect via `bloodPressureRepository.getReadings(start, end)` — wrap in try-catch for SecurityException
     7. If glucose readings non-empty: push via `foodScannerHealthRepository.pushGlucoseReadings()` in chunks of 1000. If any chunk fails, log warning and set `glucoseFailed = true`
     8. If BP readings non-empty: push via `foodScannerHealthRepository.pushBloodPressureReadings()` in chunks of 1000. Same failure tracking.
     9. If neither failed: update `lastHealthReadingsSyncTimestamp` to `end.toEpochMilli()`
     10. Log summary: counts of glucose/BP read and pushed
4. Run verifier (expect pass)

**Notes:**
- This is best-effort: failures log warnings but don't fail the overall SyncWorker
- The 1-day overlap on subsequent syncs handles late-arriving records (e.g., CGM data syncing from phone). Food Scanner's upsert on `measuredAt` deduplicates.
- CancellationException must propagate — check before catching generic Exception
- Chunking at 1000 matches Food Scanner API batch limit

**Defensive Requirements:**
- CancellationException rethrown before any generic catch
- SecurityException from Health Connect reads caught independently per type (glucose failure doesn't skip BP)
- 10s timeout inherited from repository layer — no additional timeout needed here

### Task 7: Integrate health readings sync into SyncWorker
**Linear Issue:** [HEA-182](https://linear.app/lw-claude/issue/HEA-182/integrate-health-readings-sync-into-syncworker)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncWorker.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncWorkerTest.kt` (create or modify)

**Steps:**
1. Write tests:
   - SyncWorker calls `syncHealthReadingsUseCase.invoke()` after nutrition sync completes
   - Health readings sync failure does not affect SyncWorker result (nutrition sync result determines worker outcome)
   - Health readings sync is skipped if nutrition sync returns `NeedsConfiguration`
   - CancellationException from health readings sync propagates
2. Run verifier (expect fail)
3. Modify `SyncWorker`:
   - Add `SyncHealthReadingsUseCase` to constructor injection
   - After nutrition sync completes (regardless of Success/Error), call `syncHealthReadingsUseCase.invoke()` wrapped in try-catch
   - If health readings sync throws (non-cancellation), log warning and continue — the nutrition sync result determines the worker's return value
   - If nutrition sync returns `NeedsConfiguration`, skip health readings sync entirely
4. Run verifier (expect pass)

**Notes:**
- Health readings sync is fire-and-forget from the worker's perspective. It runs on the same cadence as nutrition sync but its failure is non-fatal.
- The worker's Result (success/retry/failure) is determined solely by nutrition sync. Health readings sync piggybacks on the same schedule.

**Defensive Requirements:**
- CancellationException from `syncHealthReadingsUseCase` must propagate (worker is being cancelled)
- Generic exceptions caught and logged — never crash the worker

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Improve food log time accuracy by using the Food Scanner API's zoneOffset field, and backfill Health Connect glucose/blood pressure readings to Food Scanner

**Approach:** Feature 1 threads `zoneOffset` from the API response through DTO → domain model → NutritionRecordMapper, replacing the hardcoded system timezone with the actual offset from when the meal was logged. Feature 2 adds range-read methods to the Health Connect repositories, batch-push methods to the Food Scanner repository, a new `SyncHealthReadingsUseCase` that reads all glucose/BP from Health Connect and pushes to Food Scanner, and integrates it into the existing `SyncWorker` on the same cadence as nutrition sync.

**Scope:**
- Tasks: 7
- Files affected: ~18 (12 modified, ~2 created, plus tests)
- New tests: ~40+ test cases across 7 new/modified test files

**Key Decisions:**
- `zoneOffset` null → system default (backward compatible, handles API responses without the field)
- Malformed `zoneOffset` → log warning, fall back to system default
- Health readings sync uses epoch timestamp (not date string) for tracking — instant-based records
- 1-day overlap on subsequent syncs to catch late-arriving records (CGM backfill)
- Batch chunking at 1000 (Food Scanner API limit)
- Health readings sync is best-effort — failure doesn't affect SyncWorker result
- Push ALL Health Connect readings regardless of source app — Food Scanner deduplicates on `measuredAt`

**Risks/Considerations:**
- CGM devices (Dexcom) can produce ~288 readings/day (every 5 min). First 30-day backfill could be ~8640 records — well within batch limits but worth monitoring API response time
- Health Connect's 30-day default read limit caps initial backfill. `READ_HEALTH_DATA_HISTORY` permission could extend this later if needed
- `zoneOffset` may be null for older Food Scanner entries logged before the field was added — fallback to system default is correct for these
