# Fix Plan: Reduce Sentry noise from transient server errors and add sync abort on consecutive failures

**Issue:** HEA-157
**Date:** 2026-03-01
**Status:** Planning
**Branch:** fix/HEA-157-sync-backoff-and-log-levels

## Investigation

### Bug Report
Sentry issue [HEALTH-HELPER-5](https://lucas-wall.sentry.io/issues/HEALTH-HELPER-5): 8 error events from `getFoodLog(2026-03-01) HTTP error: 503` in ~4 minutes during a single sync cycle. The Food Scanner API returned 503 (Service Unavailable) and the app logged each failure as `Timber.e` (error level), creating one Sentry event per date synced. The app continued hammering the down server for every remaining date.

### Classification
- **Type:** Integration
- **Severity:** Medium
- **Affected Area:** `FoodScannerApiClient`, `SyncNutritionUseCase`

### Root Cause Analysis
Two issues compound to create Sentry noise when the server is temporarily unavailable:

1. **5xx errors logged at error level** — `FoodScannerApiClient.kt:52-54` logs 401/429 at `Timber.w` (warning, no Sentry event) but all other HTTP errors including 5xx at `Timber.e` (error, creates Sentry event). Transient server outages are expected in mobile apps and should not generate error-level Sentry events.

2. **No circuit breaker for consecutive API failures** — `SyncNutritionUseCase.kt:73-124` iterates through all dates with only a fixed 500ms delay, even when the server is clearly down. With 8 events in 4 minutes on a single sync, the app was repeatedly calling a server that was returning 503 for every request.

#### Evidence
- **File:** `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt:52-54` — 5xx falls into the `else` branch that logs at `Timber.e` level
- **File:** `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt:73-124` — sync loop with fixed 500ms delay, no abort on consecutive failures
- **Sentry:** 8 events from release `v1.1.0+4`, single user, 4-minute window

#### Related Code
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt:45-56` — HTTP error handler with log-level branching
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt:104-108` — failure path in sync loop (logs warning, continues)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt:120-124` — fixed 500ms delay between API calls

### Impact
- Sentry flooded with transient server errors (8 events from one sync cycle)
- Unnecessary API calls to a server that is clearly unavailable
- Battery and network waste on mobile device during server outages

## Fix Plan (TDD Approach)

### Step 1: Log 5xx errors at warning level in FoodScannerApiClient
**File:** `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
**Test:** `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)

**Behavior:**
- The `when` block at lines 52-54 should treat 5xx status codes (500..599) the same as 401/429 for logging: use `Timber.w` instead of `Timber.e`
- The error message for 5xx should be "Server unavailable" to distinguish from client-side errors
- Add `in 500..599` case to the `when` block at line 47 to set the message, and at line 52 to set the log level
- The function still returns `Result.failure` — only the log level and message change

**Tests:**
1. 503 response returns failure with message "Server unavailable"
2. 500 response returns failure with message "Server unavailable"

### Step 2: Add exponential backoff and abort after 5 consecutive failures in SyncNutritionUseCase
**File:** `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
**Test:** `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)

**Behavior:**
- Track consecutive API failures with an `Int` counter, reset to 0 on any successful API call (when `result.isSuccess`)
- When consecutive failures reach 5, abort the sync loop early (break out of the `for (date in dates)` loop)
- Apply exponential backoff to the inter-request delay: base delay 500ms, doubled on each consecutive failure (500ms, 1000ms, 2000ms, 4000ms, 8000ms), capped at 8000ms
- On success, reset delay back to 500ms base
- When aborting early, log a warning: `"SyncNutrition: aborting after %d consecutive API failures"`
- The abort counts as completing the loop — existing result logic (successfulDays, lastSyncedDate watermark, etc.) still applies based on whatever was processed before aborting

**Tests:**
1. 5 consecutive API failures aborts the sync loop — `getFoodLog` should be called exactly 5 times even when more dates remain
2. Success after 4 consecutive failures resets the counter — sync continues through all remaining dates
3. Intermittent failures (fail, succeed, fail, succeed, ...) never trigger abort — all dates are processed
4. After abort with no prior successes, sync returns `SyncResult.Error`
5. After abort with some prior successes, sync returns `SyncResult.Success` with partial count
6. Existing test `apiErrorContinues` still passes — a single failure does not abort

### Step 3: Verify
- [ ] All new tests pass
- [ ] All existing tests pass
- [ ] Kotlin compiles without errors (`./gradlew assembleDebug`)
- [ ] Lint passes (`./gradlew lint`)
- [ ] Build succeeds

## Notes
- The Sentry issue is on release `v1.1.0+4` but current is `v1.2.0`. The code path is unchanged — the fix applies to the current codebase.
- The exponential backoff only affects the inter-request delay within a single sync invocation. WorkManager's own retry policy handles retrying the entire sync worker.
- Seer rated this issue as `super_low` actionability because the 503 is a server-side problem — but the app-side improvements (log levels + backoff) are still valuable.
