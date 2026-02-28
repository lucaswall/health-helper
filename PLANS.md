# Implementation Plan

**Created:** 2026-02-28
**Source:** Inline request: Add ETag support to food sync — the Food Scanner API already returns ETag headers and handles If-None-Match / 304 Not Modified. Update the client to cache ETags per date, send If-None-Match on requests, and skip Health Connect writes when data hasn't changed. This eliminates redundant HC upserts every 15 minutes for today's food.
**Linear Issues:** [HEA-129](https://linear.app/lw-claude/issue/HEA-129), [HEA-130](https://linear.app/lw-claude/issue/HEA-130), [HEA-131](https://linear.app/lw-claude/issue/HEA-131), [HEA-132](https://linear.app/lw-claude/issue/HEA-132), [HEA-133](https://linear.app/lw-claude/issue/HEA-133)

## Context Gathered

### Codebase Analysis
- **Food Scanner server**: ETag fully deployed (v1.16.0). `GET /api/v1/food-log` returns `ETag` header (SHA-256 of response data, first 16 hex chars). Handles `If-None-Match` → responds 304 with empty body when data unchanged.
- **Health Helper client**: Zero ETag support. `FoodScannerApiClient` sends plain GET with Bearer auth. No `If-None-Match`, no 304 handling, no ETag storage.
- **API client**: Ktor 3.1.1 + OkHttp engine, `ContentNegotiation` + `HttpTimeout` (30s). Returns `Result<List<FoodLogEntry>>`.
- **Repository**: `FoodScannerFoodLogRepository` is a pass-through wrapper over the API client.
- **Use case**: `SyncNutritionUseCase` always re-syncs today, writes to Health Connect via `insertRecords()` (upserts via `clientRecordId`). No harm from duplicate writes, but wasteful.
- **Settings storage**: `DataStoreSettingsRepository` uses DataStore<Preferences> for non-sensitive data, JSON serialization for complex types (meals list pattern).
- **DI**: `AppModule` provides `FoodLogRepository` as `FoodScannerFoodLogRepository(apiClient)` — will need `SettingsRepository` added.
- **Test patterns**: Ktor `MockEngine` for API client tests, MockK for repository/use-case mocks. JUnit 5 + `@DisplayName` + `runTest`.
- **Callers of `FoodLogRepository.getFoodLog()`**: Only `SyncNutritionUseCase`.

### MCP Context
- **Linear team**: Health Helper (ID: `7b911426-efe2-48cb-93a4-4d69cd4592a6`)

## Original Plan

### Task 1: Add FoodLogResult domain model
**Linear Issue:** [HEA-129](https://linear.app/lw-claude/issue/HEA-129)

A sealed class representing the outcome of a food log fetch — either new data or "not modified". This is the domain-layer concept that abstracts away the HTTP ETag mechanism.

**File:** `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogResult.kt`

**Specification:**
- `FoodLogResult.Data(entries: List<FoodLogEntry>)` — fresh data from API
- `FoodLogResult.NotModified` — data unchanged since last fetch (object, no fields)
- Follow existing sealed class pattern from `SyncResult.kt`

**TDD Steps:**
1. Write test in `app/src/test/kotlin/com/healthhelper/app/domain/model/FoodLogResultTest.kt`
   - Test `FoodLogResult.Data` holds entries correctly
   - Test `FoodLogResult.NotModified` is a singleton (`assertSame`)
   - Test pattern matching (when expression) covers both branches
2. Run verifier (expect fail — class doesn't exist)
3. Create `FoodLogResult.kt` with the sealed class
4. Run verifier (expect pass)

---

### Task 2: Add ETag storage to SettingsRepository
**Linear Issue:** [HEA-130](https://linear.app/lw-claude/issue/HEA-130)

Store per-date ETags so the client can send `If-None-Match` on subsequent requests. ETags are stored as a JSON-serialized `Map<String, String>` (date → ETag) in DataStore. Old entries are pruned to prevent unbounded growth.

**Files modified:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — add interface methods
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — implement

**Specification:**
- `suspend fun getETag(date: String): String?` — returns stored ETag for given date, or null
- `suspend fun setETag(date: String, etag: String)` — stores ETag for date; prunes entries older than 7 days from today to prevent unbounded map growth
- DataStore key: `stringPreferencesKey("food_log_etags")`, stored as JSON object `{"2026-02-28":"\"abc123\"","2026-02-27":"\"def456\""}`
- Serialization: `kotlinx.serialization.json.Json.encodeToString()` / `decodeFromString()` using `Map<String, String>`
- Pruning: on each `setETag` call, parse the existing map, remove entries with date keys older than `LocalDate.now().minusDays(7)`, then write back
- Malformed JSON in DataStore → log warning, return null / start fresh (don't throw)

**TDD Steps:**
1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/repository/ETagStorageTest.kt`
   - Test `getETag` returns null when no ETag stored
   - Test `setETag` then `getETag` returns stored value
   - Test `setETag` overwrites previous ETag for same date
   - Test `setETag` prunes entries older than 7 days
   - Test `getETag` with malformed JSON in DataStore returns null (does not throw)
   - Test ETags for different dates are independent
2. Run verifier (expect fail)
3. Add `getETag`/`setETag` to `SettingsRepository` interface
4. Add `FOOD_LOG_ETAGS` key and implement methods in `DataStoreSettingsRepository`
5. Run verifier (expect pass)

**Note:** Existing tests that mock `SettingsRepository` (SyncNutritionUseCaseTest, SettingsViewModelTest) will need stub responses for the new methods. Add `coEvery { settingsRepository.getETag(any()) } returns null` and `coEvery { settingsRepository.setETag(any(), any()) } returns Unit` to the `configureSettings()` helper in `SyncNutritionUseCaseTest.kt`.

---

### Task 3: Add ETag support to FoodScannerApiClient
**Linear Issue:** [HEA-131](https://linear.app/lw-claude/issue/HEA-131)

Update the HTTP client to accept an optional ETag, send `If-None-Match` header, handle 304 responses, and extract the `ETag` response header from 200 responses.

**File modified:** `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt`
**New file:** `app/src/main/kotlin/com/healthhelper/app/data/api/FoodLogApiResponse.kt`

**Specification:**

New data class `FoodLogApiResponse`:
- `entries: List<FoodLogEntry>` — parsed food entries (empty for 304)
- `etag: String?` — ETag from response header (null if not present)
- `notModified: Boolean` — true when server returned 304

Method signature change: `getFoodLog(baseUrl, apiKey, date, etag: String? = null): Result<FoodLogApiResponse>`

Behavior changes:
- When `etag` parameter is non-null: add `If-None-Match` request header with the ETag value (via `header(HttpHeaders.IfNoneMatch, etag)`)
- When response status is 304: return `Result.success(FoodLogApiResponse(emptyList(), etag = null, notModified = true))`. Do NOT attempt to parse the body (it's empty).
- When response status is 2xx: extract `ETag` header from response (`response.headers[HttpHeaders.ETag]`), parse body as before, return `FoodLogApiResponse(entries, etag = extractedETag, notModified = false)`
- 304 check must happen BEFORE the existing `!response.status.isSuccess()` check (since 304 is not a success status)
- All other behavior unchanged (401, 429, error handling, HTTPS validation, CancellationException propagation)

**TDD Steps:**
1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (add to existing file)
   - Test: `If-None-Match` header is sent when `etag` parameter is provided — capture request headers, assert `If-None-Match` equals the provided ETag
   - Test: `If-None-Match` header is NOT sent when `etag` is null — capture request headers, assert header absent
   - Test: 304 response returns `FoodLogApiResponse` with `notModified = true` and empty entries — mock engine returns `respond("", status = HttpStatusCode(304, "Not Modified"))`, assert result is success, `notModified == true`, `entries.isEmpty()`
   - Test: 200 response with `ETag` header returns it in `FoodLogApiResponse.etag` — mock engine returns success with `ETag` response header, assert `etag` field matches
   - Test: 200 response without `ETag` header returns `etag = null`
   - Update all existing tests to unwrap `FoodLogApiResponse` (change `result.getOrThrow()` to `result.getOrThrow().entries` and add `assertFalse(result.getOrThrow().notModified)` where applicable)
2. Run verifier (expect fail — new tests fail, existing tests fail due to return type change)
3. Create `FoodLogApiResponse.kt` data class
4. Update `getFoodLog()` in `FoodScannerApiClient`: add `etag` parameter, handle 304, extract ETag header, return `FoodLogApiResponse`
5. Run verifier (expect pass)

---

### Task 4: Wire ETag through FoodScannerFoodLogRepository
**Linear Issue:** [HEA-132](https://linear.app/lw-claude/issue/HEA-132)

Update the repository to: (1) change its return type to `FoodLogResult`, (2) inject `SettingsRepository` for ETag storage, (3) read the stored ETag before calling the API, (4) store the new ETag on 200, and (5) map the API response to the domain `FoodLogResult`.

**Files modified:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/FoodLogRepository.kt` — change return type
- `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerFoodLogRepository.kt` — add SettingsRepository, wire ETag
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` — add SettingsRepository to FoodLogRepository provider

**Specification:**

Interface change:
```
getFoodLog(baseUrl, apiKey, date): Result<FoodLogResult>
```

Repository logic:
1. Call `settingsRepository.getETag(date)` to get cached ETag
2. Call `apiClient.getFoodLog(baseUrl, apiKey, date, etag)` with the cached ETag
3. On success with `notModified = true` → return `Result.success(FoodLogResult.NotModified)`
4. On success with `notModified = false`:
   - If `apiResponse.etag` is non-null → call `settingsRepository.setETag(date, apiResponse.etag)`
   - Return `Result.success(FoodLogResult.Data(apiResponse.entries))`
5. On failure → propagate `Result.failure()` unchanged

DI change in `AppModule.provideFoodLogRepository()`: add `settingsRepository: SettingsRepository` parameter, pass to `FoodScannerFoodLogRepository(apiClient, settingsRepository)`.

**TDD Steps:**
1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/repository/FoodScannerFoodLogRepositoryTest.kt`
   - Test: successful API response maps to `FoodLogResult.Data` with correct entries
   - Test: 304 API response maps to `FoodLogResult.NotModified`
   - Test: ETag from settings is passed to API client
   - Test: new ETag from API response is stored via `settingsRepository.setETag()`
   - Test: null ETag from API response does NOT call `setETag()`
   - Test: API failure propagates as `Result.failure()`
   - Test: `getETag` failure (exception) does not prevent API call (graceful degradation — call API without ETag)
2. Run verifier (expect fail)
3. Update `FoodLogRepository` interface return type
4. Add `SettingsRepository` to `FoodScannerFoodLogRepository` constructor, implement ETag wiring
5. Update `AppModule.provideFoodLogRepository()` to inject `SettingsRepository`
6. Run verifier (expect pass)

---

### Task 5: Handle FoodLogResult.NotModified in SyncNutritionUseCase
**Linear Issue:** [HEA-133](https://linear.app/lw-claude/issue/HEA-133)

Update the sync algorithm to skip Health Connect writes when the API returns NotModified. Also fix a subtle bug: when all dates return NotModified (no new entries collected), don't overwrite `lastSyncedMeals` with an empty list.

**File modified:** `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt`

**Specification:**

When `FoodLogResult.NotModified`:
- Count as a successful day (`successfulDays++`)
- Mark in `pastDateResults` as `true` (for contiguous watermark)
- Do NOT call `nutritionRepository.writeNutritionRecords()`
- Do NOT add to `syncedEntries`
- Do NOT increment `totalEntriesFetched` or `totalRecordsSynced`
- Log at debug level: `"getFoodLog(%s) not modified, skipping HC write"`

When `FoodLogResult.Data`:
- Existing behavior: check entries, write to HC, track counts

Meal persistence fix:
- Only call `settingsRepository.setLastSyncedMeals(summaries)` if `syncedEntries.isNotEmpty()`
- If `syncedEntries` is empty (all dates were NotModified or had no food), skip meal persistence to preserve the previously stored meals

**Defensive Requirements:**
- `CancellationException`: must still re-throw (verify in tests)
- `FoodLogResult` exhaustive `when`: compiler-enforced by sealed class
- If new `FoodLogResult` variants are added in the future, the `when` block should fail at compile time (no `else` branch)

**TDD Steps:**
1. Update existing tests in `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt`
   - Update `configureSettings()` helper: add `coEvery { settingsRepository.getETag(any()) } returns null` and `coEvery { settingsRepository.setETag(any(), any()) } returns Unit`
   - Update all existing `coEvery { foodLogRepository.getFoodLog(...) } returns Result.success(listOf(...))` to `returns Result.success(FoodLogResult.Data(listOf(...)))`
   - Update all `Result.success(emptyList())` to `Result.success(FoodLogResult.Data(emptyList()))`
   - Run verifier to ensure existing tests still pass after migration
2. Write new tests:
   - Test: `NotModified` for today skips HC write, returns Success with 0 records synced and 1 day processed
   - Test: `NotModified` for a past date counts as successful in contiguous watermark — setup 3 past dates where middle date returns NotModified, verify `lastSyncedDate` advances through it (unlike a failure which breaks the chain)
   - Test: mix of `Data` and `NotModified` — today returns NotModified, yesterday returns Data with entries → HC write called only for yesterday, records count = yesterday's entries
   - Test: all dates return `NotModified` → `setLastSyncedMeals` is NOT called (preserves existing meals)
   - Test: some dates return `Data` with entries, others `NotModified` → `setLastSyncedMeals` IS called with entries from `Data` dates only
3. Run verifier (expect fail for new tests)
4. Update `SyncNutritionUseCase.invoke()`:
   - Change `foodLogRepository.getFoodLog()` result handling to `when` on `FoodLogResult`
   - Add `FoodLogResult.NotModified` branch
   - Guard `setLastSyncedMeals` with `syncedEntries.isNotEmpty()`
5. Run verifier (expect pass)

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Add ETag support to food sync to eliminate redundant Health Connect writes every 15 minutes

**Request:** The Food Scanner API already supports ETags. Adapt the Health Helper client to send If-None-Match headers, handle 304 Not Modified responses, and skip Health Connect writes when food data hasn't changed.

**Linear Issues:** HEA-129, HEA-130, HEA-131, HEA-132, HEA-133

**Approach:** Add a `FoodLogResult` sealed class to the domain layer to represent "data" vs "not modified" outcomes. Store per-date ETags in DataStore (pruned after 7 days). Update the API client to send `If-None-Match` and handle 304 responses. Wire ETag storage through the repository layer. Update the use case to skip HC writes on NotModified and fix a subtle bug where empty sync cycles would overwrite persisted meal summaries.

**Scope:**
- Tasks: 5
- Files affected: 8 modified, 3 new (model, DTO, tests)
- New tests: yes (ETag storage tests, API client ETag tests, repository ETag wiring tests, use case NotModified tests)

**Key Decisions:**
- ETag is an HTTP detail handled in data layer; domain layer only sees `FoodLogResult.NotModified`
- ETags stored per-date in DataStore as JSON map, pruned after 7 days
- 304 NotModified counts as "successful day" for the contiguous watermark — data was already synced, no gap
- Meal summary persistence guarded by `syncedEntries.isNotEmpty()` to avoid overwriting with empty list

**Risks/Considerations:**
- Existing tests (14 API client tests, 18 use case tests) need mechanical updates for new return types — high line count but low risk
- If Food Scanner server removes ETag support, behavior gracefully degrades: no `If-None-Match` sent (etag is null), server returns 200 with full data, everything works as before

---

## Iteration 1

**Implemented:** 2026-02-28
**Method:** Single-agent (2 independent units, 9 effort points — below worker threshold)

### Tasks Completed This Iteration
- Task 1: Add FoodLogResult domain model — sealed class with Data/NotModified variants
- Task 2: Add ETag storage to SettingsRepository — per-date JSON map in DataStore with 7-day pruning
- Task 3: Add ETag support to FoodScannerApiClient — If-None-Match header, 304 handling, ETag extraction
- Task 4: Wire ETag through FoodScannerFoodLogRepository — inject SettingsRepository, map to FoodLogResult, store ETags
- Task 5: Handle FoodLogResult.NotModified in SyncNutritionUseCase — skip HC writes, fix meal persistence guard

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogResult.kt` — New sealed class (Data/NotModified)
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodLogApiResponse.kt` — New API response DTO with entries, etag, notModified
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — Added etag parameter, 304 handling, ETag header extraction
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — Added getETag/setETag interface methods
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — Implemented ETag storage with JSON map and 7-day pruning
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/FoodLogRepository.kt` — Changed return type to Result<FoodLogResult>
- `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerFoodLogRepository.kt` — Added SettingsRepository injection, ETag wiring, FoodLogResult mapping
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` — Added SettingsRepository to FoodLogRepository provider
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` — Handle NotModified branch, guard meal persistence with syncedEntries.isNotEmpty()
- `app/src/test/kotlin/com/healthhelper/app/domain/model/FoodLogResultTest.kt` — New: 3 tests for sealed class
- `app/src/test/kotlin/com/healthhelper/app/data/repository/ETagStorageTest.kt` — New: 6 tests for ETag storage
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` — Updated existing tests + 5 new ETag tests
- `app/src/test/kotlin/com/healthhelper/app/data/repository/FoodScannerFoodLogRepositoryTest.kt` — New: 8 tests for repository ETag wiring
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` — Migrated 22 existing tests + 5 new NotModified tests

### Linear Updates
- HEA-129: Todo → In Progress → Review
- HEA-130: Todo → In Progress → Review
- HEA-131: Todo → In Progress → Review
- HEA-132: Todo → In Progress → Review
- HEA-133: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 2 bugs (1 HIGH: setETag exception propagation, 1 MEDIUM: hardcoded test dates), both fixed
- verifier: All tests pass, zero warnings

### Review Findings

Summary: 4 issue(s) found (Team: security, reliability, quality reviewers)
- FIX: 4 issue(s) — Linear issues created
- DISCARDED: 7 finding(s) — false positives / not applicable

**Issues requiring fix:**
- [MEDIUM] COROUTINE: CancellationException swallowed in apiKeyFlow callbackFlow (`app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt:93`) — `catch (e: Exception)` catches CancellationException without rethrowing, breaking cooperative cancellation
- [MEDIUM] BUG: setLastSyncTimestamp called before HC write result checked (`app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt:117-123`) — timestamp updated when `successfulDays > 0` but before checking if HC writes succeeded; misleads UI on failed syncs
- [MEDIUM] LOGGING: Server error logged at DEBUG level instead of WARN (`app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt:65`) — `Timber.d` makes server-reported errors invisible in production
- [LOW] LOGGING: HTTP 429 rate-limited logged at ERROR level instead of WARN (`app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt:55`) — rate limiting is a transient condition, not an app error

**Discarded findings (not bugs):**
- [DISCARDED] SECURITY: ETag CRLF injection risk (`FoodScannerApiClient.kt:89`) — OkHttp validates header values and throws on CR/LF characters; server is already trusted (auth token sent). Defense exists at HTTP client layer.
- [DISCARDED] SECURITY: No certificate pinning (`AppModule.kt:86-93`) — User-configured server makes certificate pinning impractical. HTTPS enforcement already present. Known trade-off.
- [DISCARDED] SECURITY: No date parameter validation (`FoodLogRepository.kt:9`) — All callers generate dates from `LocalDate.format()`. No user input path. Defensive coding suggestion, not a bug.
- [DISCARDED] CONVENTION: Missing `operator` keyword on invoke (`SyncNutritionUseCase.kt:25`) — Style-only preference. Both `useCase.invoke()` and `useCase()` work identically. Zero correctness impact.
- [DISCARDED] TEST: Trivial singleton test (`FoodLogResultTest.kt:40-43`) — `data object` singleton is a Kotlin language guarantee. Test is trivially true but harmless.
- [DISCARDED] TYPE: `assertIs<>` pattern not used in tests (`FoodScannerFoodLogRepositoryTest.kt`, `SyncNutritionUseCaseTest.kt`) — Style preference. `assertTrue + as` is correct and provides adequate coverage.
- [DISCARDED] SECURITY: Server error message logged verbatim (`FoodScannerApiClient.kt:65`) — Merged into logging finding above. Timber.d suppressed in production.

### Linear Updates
- HEA-129: Review → Merge
- HEA-130: Review → Merge
- HEA-131: Review → Merge
- HEA-132: Review → Merge
- HEA-133: Review → Merge
- HEA-134: Created in Todo (Fix: CancellationException swallowed in apiKeyFlow)
- HEA-135: Created in Todo (Fix: setLastSyncTimestamp called prematurely)
- HEA-136: Created in Todo (Fix: Server error logged at DEBUG)
- HEA-137: Created in Todo (Fix: HTTP 429 logged at ERROR)

<!-- REVIEW COMPLETE -->

---

## Fix Plan

**Source:** Review findings from Iteration 1
**Linear Issues:** [HEA-134](https://linear.app/lw-claude/issue/HEA-134), [HEA-135](https://linear.app/lw-claude/issue/HEA-135), [HEA-136](https://linear.app/lw-claude/issue/HEA-136), [HEA-137](https://linear.app/lw-claude/issue/HEA-137)

### Fix 1: CancellationException swallowed in apiKeyFlow callbackFlow
**Linear Issue:** [HEA-134](https://linear.app/lw-claude/issue/HEA-134)

1. Write test in `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` that collects `apiKeyFlow`, cancels the collecting coroutine during `migrateIfNeeded()`, and verifies `CancellationException` propagates (is not swallowed)
2. Add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` in `DataStoreSettingsRepository.kt:93`
3. Run verifier (expect pass)

### Fix 2: setLastSyncTimestamp called before HC write result checked
**Linear Issue:** [HEA-135](https://linear.app/lw-claude/issue/HEA-135)

1. Write test in `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt`: all API calls succeed with entries but all `writeNutritionRecords()` return false → verify `setLastSyncTimestamp` is NOT called
2. Write test: some HC writes succeed (totalRecordsSynced > 0) → verify `setLastSyncTimestamp` IS called
3. Change condition in `SyncNutritionUseCase.kt:117` from `successfulDays > 0` to `totalRecordsSynced > 0`
4. Run verifier (expect pass)

### Fix 3: Server error logged at DEBUG level instead of WARN
**Linear Issue:** [HEA-136](https://linear.app/lw-claude/issue/HEA-136)

1. Change `Timber.d` to `Timber.w` on line 65 of `FoodScannerApiClient.kt`
2. Run verifier (expect pass — no test changes needed, log level is not asserted in tests)

### Fix 4: HTTP 429 rate-limited logged at ERROR level instead of WARN
**Linear Issue:** [HEA-137](https://linear.app/lw-claude/issue/HEA-137)

1. In `FoodScannerApiClient.kt:52-56`, change the else branch to distinguish 429 from other errors:
   - 401 → `Timber.w` (already correct)
   - 429 → `Timber.w` (change from `Timber.e`)
   - other → `Timber.e` (keep as-is)
2. Run verifier (expect pass)
