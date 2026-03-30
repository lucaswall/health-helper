# Implementation Plan

**Created:** 2026-03-30
**Source:** Inline request: Robust health readings backfill with capped incremental sync, dedup ledger, retry, and home screen status
**Linear Issues:** [HEA-185](https://linear.app/lw-claude/issue/HEA-185), [HEA-186](https://linear.app/lw-claude/issue/HEA-186), [HEA-187](https://linear.app/lw-claude/issue/HEA-187), [HEA-188](https://linear.app/lw-claude/issue/HEA-188), [HEA-189](https://linear.app/lw-claude/issue/HEA-189), [HEA-190](https://linear.app/lw-claude/issue/HEA-190)
**Branch:** feat/robust-health-backfill

## Context Gathered

### Codebase Analysis

- **Current backfill flow:** `SyncWorker` → `SyncHealthReadingsUseCase` reads glucose+BP from Health Connect in a single bulk read (paginated, 120s cumulative timeout), pushes to Food Scanner API in chunks of 1000, uses a single shared `lastHealthReadingsSyncTimestamp` watermark. Non-fatal — failures don't affect the nutrition sync worker result.
- **Write flow:** `WriteGlucoseReadingUseCase` / `WriteBloodPressureReadingUseCase` push to both Health Connect and Food Scanner simultaneously. HC stores glucose in mmol/L internally; domain model uses mg/dL. Round-trip conversion (`mg/dL → mmol/L → mg/dL`) via `GlucoseReading.toMmolL()` / `GlucoseReading.fromMmolL()` can produce ±1 mg/dL drift due to `roundToInt()` on `value * 18.018`.
- **HC metadata:** Records written by this app carry `clientRecordId` prefixes: `"bloodglucose-{epochMillis}"` for glucose, `"bloodpressure-{epochMillis}"` for BP (set in `BloodGlucoseRecordMapper.kt:50` and `BloodPressureRecordMapper.kt:38`). Third-party records have different or null client record IDs.
- **Dedup challenge:** Pre-PR#14 records exist only in HC (Food Scanner push didn't exist). Post-PR#14 records exist in both HC and Food Scanner. The backfill must include pre-PR#14 self-written records but skip post-PR#14 ones to avoid overwriting correct values with mmol/L-roundtrip-corrupted values.
- **API error handling:** `FoodScannerApiClient` detects 401/429/5xx but wraps all as generic `Exception(message)`. No typed exceptions — callers cannot distinguish retryable from permanent failures.
- **Settings storage:** `DataStoreSettingsRepository` uses DataStore Preferences + EncryptedSharedPreferences. JSON serialization via kotlinx.serialization for complex types (meals, ETags).
- **Test conventions:** JUnit 5 + MockK + `runTest`. `@DisplayName` annotations. `coEvery`/`coVerify` for suspend mocks. Turbine for Flow testing in ViewModel tests. `StandardTestDispatcher` for ViewModel tests.
- **UI conventions:** Compose Material3 Cards with `titleMedium` headers. Status text uses `bodySmall` + `onSurfaceVariant` color. `collectAsStateWithLifecycle` for state observation.

### MCP Context

- **MCPs used:** Linear (issue creation)
- **Findings:** Team "Health Helper" (ID: 7b911426), prefix HEA-xxx. Issues HEA-185 through HEA-190 created in Todo state.

## Tasks

### Task 1: Typed API exceptions for Food Scanner health endpoints
**Linear Issue:** [HEA-185](https://linear.app/lw-claude/issue/HEA-185)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiException.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)

**Steps:**
1. Write tests in `FoodScannerApiClientTest.kt`:
   - Test 429 response throws `RateLimitException` (subclass of `FoodScannerApiException`)
   - Test 401 response throws `AuthenticationException`
   - Test 500/502/503 response throws `ServerException`
   - Test network error (IOException) still throws IOException (not wrapped)
   - Test 400/other 4xx throws generic `FoodScannerApiException`
   - Apply to both `postGlucoseReadings` and `postBloodPressureReadings`
2. Run verifier (expect fail)
3. Create `FoodScannerApiException.kt` with sealed class hierarchy:
   - `FoodScannerApiException(message, httpStatus)` — open base
   - `RateLimitException` — 429
   - `AuthenticationException` — 401
   - `ServerException` — 5xx
4. Update `postGlucoseReadings` and `postBloodPressureReadings` in `FoodScannerApiClient.kt` to throw typed exceptions instead of `Result.failure(Exception(message))` for HTTP errors. Keep the `Result` return type — the typed exception goes inside `Result.failure()`.
5. Run verifier (expect pass)

**Notes:**
- The `getFoodLog` method also uses generic exceptions but is out of scope — only health push endpoints need typed errors for the retry logic.
- Existing tests for success/parse-error paths should remain unchanged.

### Task 2: Already-pushed ledger in SettingsRepository
**Linear Issue:** [HEA-186](https://linear.app/lw-claude/issue/HEA-186)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify)

**Steps:**
1. Write tests in `DataStoreSettingsRepositoryTest.kt`:
   - Test `getDirectPushedGlucoseTimestamps()` returns empty set initially
   - Test `addDirectPushedGlucoseTimestamp(ts)` then `get` returns set containing `ts`
   - Test multiple adds accumulate in the set
   - Test `pruneDirectPushedTimestamps(beforeMs)` removes entries older than threshold, keeps newer ones
   - Test prune on empty set is a no-op
   - Same five tests for BP variant
   - Test corrupted JSON in DataStore returns empty set (graceful degradation)
2. Run verifier (expect fail)
3. Add to `SettingsRepository` interface:
   - `suspend fun getDirectPushedGlucoseTimestamps(): Set<Long>`
   - `suspend fun addDirectPushedGlucoseTimestamp(timestampMs: Long)`
   - `suspend fun getDirectPushedBpTimestamps(): Set<Long>`
   - `suspend fun addDirectPushedBpTimestamp(timestampMs: Long)`
   - `suspend fun pruneDirectPushedTimestamps(glucoseBeforeMs: Long, bpBeforeMs: Long)`
4. Implement in `DataStoreSettingsRepository`:
   - Two new `stringPreferencesKey` entries: `DIRECT_PUSHED_GLUCOSE_TIMESTAMPS`, `DIRECT_PUSHED_BP_TIMESTAMPS`
   - Stored as JSON-encoded `Set<Long>` via kotlinx.serialization
   - `add` reads existing set, adds new entry, writes back
   - `prune` filters out entries < threshold, writes back
   - Corrupted JSON → log warning, return empty set
5. Run verifier (expect pass)

**Notes:**
- Follow existing DataStore JSON patterns (see `setETag`/`getETag` and `setLastSyncedMeals`/`lastSyncedMealsFlow` for serialization approach).
- The set is bounded: entries are pruned as the backfill watermark advances. Between prunes, growth is limited to ~1-2 entries per day (user logging frequency).

### Task 3: Record direct pushes in Write use cases
**Linear Issue:** [HEA-187](https://linear.app/lw-claude/issue/HEA-187)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCase.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCaseTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCaseTest.kt` (modify)

**Steps:**
1. Write tests in `WriteGlucoseReadingUseCaseTest.kt`:
   - Test: when FS push succeeds, `addDirectPushedGlucoseTimestamp` is called with `reading.timestamp.toEpochMilli()`
   - Test: when FS push fails (Result.failure), `addDirectPushedGlucoseTimestamp` is NOT called
   - Test: when FS push throws exception, `addDirectPushedGlucoseTimestamp` is NOT called
   - Test: ledger write failure (exception from `addDirectPushedGlucoseTimestamp`) does not affect the returned `HealthDataWriteResult` — it's fire-and-forget with a log warning
2. Write same four tests in `WriteBloodPressureReadingUseCaseTest.kt` for BP variant
3. Run verifier (expect fail)
4. Inject `SettingsRepository` into both use cases. After successful FS push (`result.isSuccess`), call `settingsRepository.addDirectPushedGlucoseTimestamp(reading.timestamp.toEpochMilli())` (or BP variant). Wrap in try-catch to make it non-fatal.
5. Run verifier (expect pass)

**Notes:**
- The `SettingsRepository` is already available via Hilt in these use cases' scope — just add it to the constructor.
- Ledger write is best-effort: if it fails, the worst case is that a future backfill re-pushes a reading with ±1 mg/dL drift. This is acceptable vs. failing the whole write operation.

### Task 4: Per-type sync timestamps and status metadata
**Linear Issue:** [HEA-188](https://linear.app/lw-claude/issue/HEA-188)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify)

**Steps:**
1. Write tests in `DataStoreSettingsRepositoryTest.kt`:
   - Test `lastGlucoseSyncTimestampFlow` emits 0L initially
   - Test `setLastGlucoseSyncTimestamp(ts)` then flow emits `ts`
   - Test `lastBpSyncTimestampFlow` emits 0L initially
   - Test `setLastBpSyncTimestamp(ts)` then flow emits `ts`
   - Test `glucoseSyncCountFlow` emits 0 initially
   - Test `setGlucoseSyncCount(n)` then flow emits `n`
   - Test `bpSyncCountFlow` emits 0 initially
   - Test `setBpSyncCount(n)` then flow emits `n`
   - Test `glucoseSyncCaughtUpFlow` emits false initially
   - Test `setGlucoseSyncCaughtUp(true)` then flow emits true
   - Same for BP caught-up
2. Run verifier (expect fail)
3. Add to `SettingsRepository` interface:
   - `val lastGlucoseSyncTimestampFlow: Flow<Long>` + setter
   - `val lastBpSyncTimestampFlow: Flow<Long>` + setter
   - `val glucoseSyncCountFlow: Flow<Int>` + setter
   - `val bpSyncCountFlow: Flow<Int>` + setter
   - `val glucoseSyncCaughtUpFlow: Flow<Boolean>` + setter
   - `val bpSyncCaughtUpFlow: Flow<Boolean>` + setter
4. Implement in `DataStoreSettingsRepository` with new preferences keys. Follow existing `longPreferencesKey`/`intPreferencesKey` patterns. For boolean, use `booleanPreferencesKey`.
5. Remove `lastHealthReadingsSyncTimestampFlow` and `setLastHealthReadingsSyncTimestamp` from the interface and implementation.
6. Run verifier (expect fail — `SyncHealthReadingsUseCase` and its tests still reference removed field)

**Notes:**
- **Migration note:** The old `LAST_HEALTH_READINGS_SYNC_TIMESTAMP` DataStore key is being removed. Users upgrading will restart backfill from EPOCH, which is the desired behavior since the old backfill was not robust. No data migration needed.
- The removal of the old field will cause compile errors in `SyncHealthReadingsUseCase`, `SyncHealthReadingsUseCaseTest`, and `SyncViewModelTest`. These are resolved in Task 5.
- Step 6 intentionally expects failure to confirm the old field is fully removed and dependent code needs updating.

### Task 5: Rewrite SyncHealthReadingsUseCase with capped incremental backfill
**Linear Issue:** [HEA-189](https://linear.app/lw-claude/issue/HEA-189)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` (modify — major rewrite)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` (modify — major rewrite)
- `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncWorker.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncWorkerTest.kt` (modify)

**Steps:**
1. Rewrite tests in `SyncHealthReadingsUseCaseTest.kt` (replace all existing tests):

   **Watermark behavior:**
   - Test: first run (timestamp=0) reads glucose from `Instant.EPOCH` to `~now`
   - Test: subsequent run reads from exact saved timestamp to `~now` (no 1-day overlap)
   - Test: glucose and BP use independent watermarks — glucose at epoch, BP at a past timestamp

   **100-record cap:**
   - Test: when HC returns 150 glucose readings (sorted by timestamp), only the first 100 are pushed
   - Test: when HC returns 50 readings (< 100), all 50 are pushed and `caughtUp` is set to true
   - Test: when HC returns exactly 100, `caughtUp` is NOT set to true (might be more)
   - Test: when HC returns 0, no push is made, `caughtUp` is set to true, watermark is NOT advanced

   **Ledger filtering:**
   - Test: readings whose `timestamp.toEpochMilli()` is in the already-pushed ledger are excluded before pushing
   - Test: if all 100 readings are in the ledger, no push is made but watermark advances to last record's timestamp
   - Test: ledger is pruned after watermark advances (call `pruneDirectPushedTimestamps` with new watermark values)

   **Watermark advancement:**
   - Test: on successful push, watermark is set to `lastPushedRecord.timestamp.toEpochMilli()` (not `Instant.now()`)
   - Test: glucose watermark advances independently of BP success/failure
   - Test: BP watermark advances independently of glucose success/failure

   **Sync count tracking:**
   - Test: on successful push of N records, sync count is incremented by N (read existing count, add N, write)
   - Test: glucose and BP counts are tracked independently

   **Retry with backoff (retryable errors):**
   - Test: when push fails with `RateLimitException`, retry up to 3 times
   - Test: when push fails with `ServerException`, retry up to 3 times
   - Test: when push fails with `IOException` (network), retry up to 3 times
   - Test: after 3 retries exhausted, watermark is NOT advanced for that type
   - Test: on retry, delay increases (500ms, 1s, 2s) — verify with `advanceTimeBy` in `runTest`

   **Permanent errors (no retry):**
   - Test: when push fails with `AuthenticationException`, no retry, watermark NOT advanced
   - Test: when push fails with generic non-retryable exception, no retry, watermark NOT advanced

   **Inter-chunk delay:**
   - Test: 200ms delay between successful chunks (if > 100 records after filtering somehow — edge case, but verify the delay mechanism exists)

   **Error isolation:**
   - Test: glucose read throws SecurityException → caught, BP still processed normally
   - Test: glucose read throws generic Exception → caught, BP still processed normally
   - Test: CancellationException propagates from any operation

2. Run verifier (expect fail)

3. Rewrite `SyncHealthReadingsUseCase`:
   - Constructor: inject `BloodGlucoseRepository`, `BloodPressureRepository`, `FoodScannerHealthRepository`, `SettingsRepository` (same as current)
   - `invoke()` method:
     a. Check `isConfigured()` — return early if not
     b. Process glucose: `syncType(glucoseRepo::getReadings, fsRepo::pushGlucoseReadings, glucoseTimestampFlow, setGlucoseTimestamp, getGlucoseLedger, setGlucoseCount, setGlucoseCaughtUp)`
     c. Process BP: same pattern with BP variants
     d. Prune ledger entries older than the new watermarks
   - Extract private `syncType()` function that encapsulates: read from watermark, take 100, filter ledger, push with retry, advance watermark, update count/caughtUp
   - Private `pushWithRetry()` function: attempt push, on `RateLimitException`/`ServerException`/`IOException` retry up to 3 times with 500ms/1s/2s delays. On `AuthenticationException` or other exceptions, fail immediately.
   - `MAX_READINGS_PER_RUN = 100`, `MAX_RETRIES = 3`, `INITIAL_RETRY_DELAY_MS = 500L`
   - `caughtUp` = true when HC returns fewer than `MAX_READINGS_PER_RUN` records

4. Update `SyncWorker.kt`: no functional change needed — it already calls `syncHealthReadingsUseCase.invoke()` in a try-catch. But update `SyncWorkerTest.kt` to use the new per-type timestamp mocks instead of the removed `lastHealthReadingsSyncTimestampFlow`.

5. Run verifier (expect pass)

**Notes:**
- The current `pushInChunks` with `CHUNK_SIZE = 1000` is removed. With the 100-record cap, a single push per type per run is sufficient. If after ledger filtering the list is ≤100, it's one API call.
- The `getReadings(start, end)` methods in the HC repos already return results sorted by timestamp ascending — the `.take(100)` naturally gives the oldest unsynced records.
- Both types are processed sequentially within `invoke()`. If glucose fails, BP still runs (existing pattern, preserved).

**Defensive Requirements:**
- Retry delays must use `kotlinx.coroutines.delay()` (not `Thread.sleep`) for coroutine-friendly waiting
- CancellationException must propagate from all operations including retry delays
- `syncType` must catch all non-cancellation exceptions per type so one type's failure doesn't block the other

### Task 6: Home screen health sync status display
**Linear Issue:** [HEA-190](https://linear.app/lw-claude/issue/HEA-190)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**Steps:**
1. Write tests in `SyncViewModelTest.kt`:
   - Test: when `glucoseSyncCaughtUp=true` and `glucoseSyncCount=342`, status string is `"342 synced to food-scanner"`
   - Test: when `glucoseSyncCaughtUp=false` and `glucoseSyncCount=100` and `lastGlucoseSyncTimestamp` maps to "Mar 15", status is `"Syncing to food-scanner: up to Mar 15 (100 pushed)"`
   - Test: when `glucoseSyncCount=0` and `glucoseSyncCaughtUp=false`, status is `"Not synced to food-scanner"`
   - Test: when `glucoseSyncCaughtUp=true` and `glucoseSyncCount=0`, status is `"No readings to sync"`
   - Same four tests for BP variant
   - Test: `lastGlucoseSyncTimestamp` of 0 produces no "last sync" text
   - Test: `lastGlucoseSyncTimestamp` > 0 produces a relative time string (reuses `formatRelativeTime`)
2. Run verifier (expect fail)
3. Add to `SyncUiState`:
   - `glucoseSyncStatus: String = ""`
   - `bpSyncStatus: String = ""`
4. In `SyncViewModel.init`, add a `combine` on the three glucose sync flows (`glucoseSyncCountFlow`, `glucoseSyncCaughtUpFlow`, `lastGlucoseSyncTimestampFlow`) → format status string → update `_uiState`. Same for BP.
5. Format logic:
   - `count=0, caughtUp=false` → `"Not synced to food-scanner"`
   - `count=0, caughtUp=true` → `"No readings to sync"`
   - `count>0, caughtUp=true` → `"$count synced to food-scanner"`
   - `count>0, caughtUp=false` → `"Syncing to food-scanner: up to $date ($count pushed)"` where `$date` is `formatRelativeTime(lastTimestamp)` or the formatted date from the timestamp
6. In `SyncScreen.kt`, add the status text inside the Blood Pressure card (below the last reading display, above the button) and the Blood Glucose card (same position). Use `MaterialTheme.typography.bodySmall` + `MaterialTheme.colorScheme.onSurfaceVariant`, matching the existing `nextSyncTime` style.
7. Run verifier (expect pass)

**Notes:**
- The periodic refresh loop (every 30s) in the ViewModel should also refresh relative time in these status strings if they contain a timestamp. Follow the existing `lastSyncTime` refresh pattern.
- Status strings should be concise — they sit inside already-dense cards.

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Make the health readings backfill to Food Scanner robust with incremental capped syncing, dedup to prevent glucose value corruption from mmol/L roundtrip, retry with backoff, and visible sync status on the home screen.
**Linear Issues:** HEA-185, HEA-186, HEA-187, HEA-188, HEA-189, HEA-190
**Approach:** Introduce a local "already-pushed" ledger to prevent re-pushing readings that were directly sent to Food Scanner (avoiding ±1 mg/dL glucose drift from the mmol/L round-trip), while still including all historical self-written records from before the Food Scanner push feature existed. Split the single shared watermark into per-type independent timestamps, cap each run at 100 records for predictable incremental progress, add typed API exceptions to enable smart retry (retryable 429/5xx vs permanent 401), and surface sync progress on the home screen.
**Scope:** 6 tasks, 14 files, ~40 tests
**Key Decisions:**
- Ledger-based dedup instead of clientRecordId filtering — includes pre-PR#14 historical self-written records while skipping post-PR#14 duplicates
- 100-record cap per type per run — predictable, bounded work per sync cycle
- Watermark saves last-pushed-record timestamp (not `now`) — no gaps, no overlap
- Ledger is best-effort — failure to record a direct push is non-fatal (worst case: ±1 mg/dL on one reading)
**Risks:**
- Two readings at the exact same millisecond could cause a false ledger match (extremely unlikely given manual logging)
- Pre-PR#14 glucose readings pushed via backfill will have ±1 mg/dL drift from the mmol/L round-trip — this is the best available data and acceptable

---

## Iteration 1

**Implemented:** 2026-03-30
**Method:** Agent team (4 workers, worktree-isolated)

### Tasks Completed This Iteration
- Task 1: Typed API exceptions for Food Scanner health endpoints — Created FoodScannerApiException sealed hierarchy, updated postGlucoseReadings/postBloodPressureReadings, 14 new tests (worker-1)
- Task 2: Already-pushed ledger in SettingsRepository — Added per-type timestamp sets with JSON serialization, graceful degradation, 12 new tests (worker-2)
- Task 3: Record direct pushes in Write use cases — Injected SettingsRepository, fire-and-forget ledger recording, 8 new tests (worker-2)
- Task 4: Per-type sync timestamps and status metadata — Replaced shared watermark with independent per-type flows/setters, removed old field, 12 new tests (worker-2)
- Task 5: Rewrite SyncHealthReadingsUseCase with capped incremental backfill — Full rewrite with 100-record cap, ledger filtering, retry with exponential backoff, error isolation, 27 new tests (worker-3)
- Task 6: Home screen health sync status display — Added glucoseSyncStatus/bpSyncStatus to SyncUiState, combine blocks in ViewModel, status text in SyncScreen cards, 10 new tests (worker-4)

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiException.kt` — Created sealed exception hierarchy
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — Typed exceptions in Result.failure()
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — Per-type sync flows, ledger methods, removed old shared watermark
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — Implemented all new settings with DataStore preferences
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCase.kt` — Ledger recording after FS push
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCase.kt` — Ledger recording after FS push
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` — Full rewrite with capped sync, retry, ledger filtering
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` — Sync status combine blocks, formatSyncStatus helper
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` — Status text in glucose and BP cards
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` — 14 typed exception tests
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` — 24 new tests (ledger + per-type sync)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCaseTest.kt` — 4 ledger recording tests
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCaseTest.kt` — 4 ledger recording tests
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` — 27 tests (full rewrite)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` — 10 sync status tests

### Linear Updates
- HEA-185: Todo → In Progress → Review
- HEA-186: Todo → In Progress → Review
- HEA-187: Todo → In Progress → Review
- HEA-188: Todo → In Progress → Review
- HEA-189: Todo → In Progress → Review
- HEA-190: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 1 HIGH (missing logging in syncType), 2 MEDIUM (no-op DataStore transaction, ViewModel var fields). Fixed HIGH and first MEDIUM. Second MEDIUM is a false positive (viewModelScope guarantees Main dispatcher).
- verifier: All tests pass, zero warnings

### Work Partition
- Worker 1: Task 1 (data/API — typed exceptions)
- Worker 2: Tasks 2, 3, 4 (data+domain — settings ledger, write use cases, per-type timestamps)
- Worker 3: Task 5 (domain — sync use case rewrite)
- Worker 4: Task 6 (presentation — sync status UI)

### Merge Summary
- Worker 1: fast-forward (no conflicts)
- Worker 2: merged cleanly (no conflicts)
- Worker 3: merged, 3 conflicts in FoodScannerApiException.kt, SettingsRepository.kt, DataStoreSettingsRepository.kt (worker-3 stubs vs worker-1/2 real implementations — kept real implementations)
- Worker 4: merged, 5 conflicts in SettingsRepository.kt, DataStoreSettingsRepository.kt, SyncHealthReadingsUseCase.kt, DataStoreSettingsRepositoryTest.kt, SyncHealthReadingsUseCaseTest.kt (worker-4 stubs vs worker-2/3 real implementations — kept real implementations via --ours)
- Post-merge fix: exception constructor signature mismatch (worker-3 tests used String params, worker-1 used Int params)

### Review Findings

Summary: 14 findings raised by team (security, reliability, quality reviewers), 4 classified as FIX, 10 discarded
- FIX: 4 issue(s) — Linear issues created in Todo
- DISCARDED: 10 finding(s) — false positives / not applicable

**Issues requiring fix:**
- [HIGH] CONVENTION: java.util.logging.Logger instead of Timber (`app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt:14-15,131`) — inconsistent logging framework, logs routed differently than rest of app
- [MEDIUM] CONVENTION: Missing `operator` keyword on invoke() (`app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt:28`) — breaks callable-as-function convention
- [MEDIUM] BUG: Kotlin assert() instead of JUnit assertTrue (`app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt:367`) — test is no-op without -ea flag
- [MEDIUM] BUG: Sync count uses toPublish.size instead of server-reported count (`app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt:91`) — inflated count if server deduplicates

**Discarded findings (not bugs):**
- [DISCARDED] SECURITY: LAST_SYNCED_MEALS in plaintext DataStore (`DataStoreSettingsRepository.kt:60`) — Consistent design: all non-credential data uses DataStore, only secrets use EncryptedSharedPreferences
- [DISCARDED] SECURITY: READ_HEALTH_DATA_HISTORY raw string permission (`SyncViewModel.kt:78`) — Intentional: this is a real Android permission required for historical data access; the backfill feature needs it
- [DISCARDED] SECURITY: Server error messages logged via Timber (`FoodScannerApiClient.kt:71,148,205`) — Standard debug practice; Timber production tree can be configured to suppress
- [DISCARDED] SECURITY: Camera permission requested without rationale (`SyncScreen.kt:83-85`) — UX preference, not a correctness bug
- [DISCARDED] BUG: getFoodLog uses generic Exception (`FoodScannerApiClient.kt:52-64`) — Intentionally out of scope per plan: "The getFoodLog method also uses generic exceptions but is out of scope"
- [DISCARDED] RESOURCE: Ledger sets unbounded when watermark stays at 0 (`DataStoreSettingsRepository.kt:336,361`) — Growth bounded by user logging frequency (~1-2/day, ~7KB/year). Plan documents this bound explicitly.
- [DISCARDED] TIMEOUT: No per-request timeout on HTTP operations (`FoodScannerApiClient.kt:40,124,180`) — False positive: HttpClient has 30s `HttpTimeout` configured in `AppModule.kt:104-105`
- [DISCARDED] TIMEOUT: No timeout on Health Connect getReadings() (`SyncHealthReadingsUseCase.kt:74`) — WorkManager provides outer timeout; HC reads are sub-second in practice
- [DISCARDED] RACE: ViewModel var fields written from multiple coroutines (`SyncViewModel.kt:86-92`) — Safe: all coroutines use viewModelScope which runs on Dispatchers.Main (single-threaded)
- [DISCARDED] BUG: LocalDate.now() in DataStore edit lambda (`DataStoreSettingsRepository.kt:301`) — Negligible: days-level granularity makes retry variance irrelevant for 7-day ETag cutoff

### Linear Updates
- HEA-185: Review → Merge
- HEA-186: Review → Merge
- HEA-187: Review → Merge
- HEA-188: Review → Merge
- HEA-189: Review → Merge
- HEA-190: Review → Merge
- HEA-191: Created in Todo (Fix: java.util.logging → Timber)
- HEA-192: Created in Todo (Fix: missing operator keyword)
- HEA-193: Created in Todo (Fix: Kotlin assert → JUnit assertTrue)
- HEA-194: Created in Todo (Fix: sync count server-reported)

<!-- REVIEW COMPLETE -->

---

## Fix Plan

**Source:** Review findings from Iteration 1
**Linear Issues:** [HEA-191](https://linear.app/lw-claude/issue/HEA-191), [HEA-192](https://linear.app/lw-claude/issue/HEA-192), [HEA-193](https://linear.app/lw-claude/issue/HEA-193), [HEA-194](https://linear.app/lw-claude/issue/HEA-194)

### Fix 1: Replace java.util.logging with Timber in SyncHealthReadingsUseCase
**Linear Issue:** [HEA-191](https://linear.app/lw-claude/issue/HEA-191)

1. Replace `java.util.logging.Logger` and `java.util.logging.Level` imports with `timber.log.Timber`
2. Replace `logger.log(Level.WARNING, ...)` at line 100 with `Timber.w(e, "syncType failed, keeping watermark at %d", watermark)`
3. Remove `logger` companion object field at line 131
4. Run verifier (expect pass — no test changes needed)

### Fix 2: Add operator keyword to SyncHealthReadingsUseCase.invoke()
**Linear Issue:** [HEA-192](https://linear.app/lw-claude/issue/HEA-192)

1. Change `suspend fun invoke()` to `suspend operator fun invoke()` at line 28
2. Run verifier (expect pass)

### Fix 3: Replace Kotlin assert() with JUnit assertTrue in retry delay test
**Linear Issue:** [HEA-193](https://linear.app/lw-claude/issue/HEA-193)

1. Replace `assert(testScheduler.currentTime >= 3500L)` with `assertTrue(testScheduler.currentTime >= 3500L, "Expected at least 3500ms of virtual time, got ${testScheduler.currentTime}ms")` at line 367
2. Add `import org.junit.jupiter.api.Assertions.assertTrue` if not present
3. Run verifier (expect pass — assertion should still hold)

### Fix 4: Use server-reported count for sync count increment
**Linear Issue:** [HEA-194](https://linear.app/lw-claude/issue/HEA-194)

1. Write test in `SyncHealthReadingsUseCaseTest.kt`: when push returns Result.success(95) for 100 records sent, sync count increments by 95 (not 100)
2. Run verifier (expect fail)
3. Change line 91 from `setCount(currentCount + toPublish.size)` to `setCount(currentCount + (result.getOrNull() ?: toPublish.size))`
4. Run verifier (expect pass)

---

## Iteration 2

**Implemented:** 2026-03-30
**Method:** Single-agent (effort score 4, 1 work unit)

### Tasks Completed This Iteration
- Fix 1: Replace java.util.logging with Timber in SyncHealthReadingsUseCase (HEA-191)
- Fix 2: Add operator keyword to SyncHealthReadingsUseCase.invoke() (HEA-192)
- Fix 3: Replace Kotlin assert() with JUnit assertTrue in retry delay test (HEA-193)
- Fix 4: Use server-reported count for sync count increment (HEA-194)

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` — Replaced java.util.logging with Timber, added operator keyword, use server-reported count
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` — Replaced assert() with assertTrue, added server-reported count test

### Linear Updates
- HEA-191: Todo → In Progress → Review
- HEA-192: Todo → In Progress → Review
- HEA-193: Todo → In Progress → Review
- HEA-194: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: 0 functional bugs, 1 LOW convention finding (import ordering — fixed)
- verifier: All 28 tests pass, zero warnings

### Continuation Status
All fix plan tasks completed.
