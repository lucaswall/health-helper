# Implementation Plan

**Created:** 2026-04-10
**Source:** Inline request: Hydration sync home screen card with daily total, sync status, and history progress
**Linear Issues:** [HEA-200](https://linear.app/lw-claude/issue/HEA-200), [HEA-201](https://linear.app/lw-claude/issue/HEA-201), [HEA-202](https://linear.app/lw-claude/issue/HEA-202)
**Branch:** feat/hydration-sync-card

## Context Gathered

### Codebase Analysis

- **Hydration backend is complete:** Domain model (`HydrationReading`), repository (`HydrationRepository` / `HealthConnectHydrationRepository`), API push (`FoodScannerApiClient.postHydrationReadings`), sync pipeline (`SyncHealthReadingsUseCase` hydration syncType), settings flows (watermark, count, caughtUp, runTimestamp) — all shipped in v1.7.0.
- **Gap is purely presentation:** `SyncUiState` has no hydration fields, `SyncViewModel` doesn't observe hydration flows, `SyncScreen` has no hydration card.
- **Missing domain component:** `HydrationRepository` only has `getReadings(start, end)` — no `getLastReading()`. But for hydration we need a daily total (sum of today's readings), not a single last reading. A new `GetTodayHydrationTotalUseCase` replaces the last-reading pattern.
- **Card pattern:** BP and glucose cards follow identical structure — Card > Column(padding=16, spacing=12) > titleMedium title > bodyMedium reading display > bodySmall sync status > Button. Hydration card follows same layout minus the button.
- **Sync status pattern:** ViewModel uses `combine(countFlow, caughtUpFlow, runTimestampFlow)` → `formatSyncStatus()` → `_uiState.update`. Same pattern applies for hydration.
- **History progress (new):** Hydration card adds a history backfill indicator not present on glucose/BP cards. Uses watermark timestamp + caughtUp flag: "History: up to date" when caught up, "History: synced to 2 months ago" when backfilling. Requires a new `formatHistoryAge()` function that extends relative time formatting to weeks/months/years (existing `formatRelativeTime` caps at "X days ago" then switches to "MMM d" dates).
- **Permission gap:** `REQUIRED_HC_PERMISSIONS` in `SyncViewModel` does not include `HydrationRecord` read permission. Must add `HealthPermission.getReadPermission(HydrationRecord::class)`.
- **Periodic refresh:** ViewModel refreshes relative time strings every 30s (lines 160-192). Must add hydration daily total refresh and history status refresh to this loop.
- **Test conventions:** JUnit 5 + MockK + `runTest`. `viewModelTest {}` helper cancels ViewModel scope. Turbine `uiState.test { awaitItem() }` for state assertions. Mock all settings flows in `@BeforeEach`.

### MCP Context

- **Linear:** All 5 hydration issues (HEA-195 through HEA-199) are Released. No open hydration work. No Todo issues exist.

## Tasks

### Task 1: GetTodayHydrationTotalUseCase
**Linear Issue:** [HEA-200](https://linear.app/lw-claude/issue/HEA-200)

**Files:**
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/GetTodayHydrationTotalUseCaseTest.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/GetTodayHydrationTotalUseCase.kt` (create)

**Steps:**
1. Write tests in `GetTodayHydrationTotalUseCaseTest.kt`:
   - Multiple readings returns sum of all volumeMl values
   - Empty list returns 0
   - Repository throws exception → returns 0 (logged via Timber)
   - CancellationException is rethrown, not swallowed
   - Verify repository called with start-of-today (midnight in system timezone) to now
2. Run verifier (expect fail)
3. Implement `GetTodayHydrationTotalUseCase` in the use case file:
   - `@Inject constructor(private val hydrationRepository: HydrationRepository)`
   - `suspend operator fun invoke(): Int` — calculates start of today via `LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()`, calls `hydrationRepository.getReadings(startOfToday, Instant.now())`, returns `readings.sumOf { it.volumeMl }`
   - try-catch with CancellationException rethrow, other exceptions return 0
   - Follow `GetLastBloodPressureReadingUseCase` error handling pattern
4. Run verifier (expect pass)

**Notes:**
- Pure domain layer — no Android imports. `java.time` classes are fine.
- Unlike glucose/BP which use `getLastReading()`, hydration uses `getReadings()` + sum because hydration is cumulative throughout the day.

### Task 2: SyncViewModel hydration integration
**Linear Issue:** [HEA-201](https://linear.app/lw-claude/issue/HEA-201)

**Files:**
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)

**Steps:**
1. Write tests in `SyncViewModelTest.kt`:
   - `hydrationTodayDisplay shows total when use case returns positive value` — mock `GetTodayHydrationTotalUseCase` to return 1250, assert `hydrationTodayDisplay == "1,250 mL"`
   - `hydrationTodayDisplay is empty when use case returns 0` — assert `hydrationTodayDisplay == ""`
   - `hydrationSyncStatus shows not synced when run timestamp is 0` — follow glucose sync status test pattern (lines 1014-1028)
   - `hydrationSyncStatus shows pushed count with relative time` — follow glucose pattern (lines 1030-1046)
   - `hydrationSyncStatus shows up to date when caught up` — assert format matches "Up to date · X"
   - `hydrationHistoryStatus shows up to date when caught up` — watermark > 0, caughtUp true → "History: up to date"
   - `hydrationHistoryStatus shows relative age when backfilling` — watermark at some past date, caughtUp false → "History: synced to X ago"
   - `hydrationHistoryStatus is empty when never synced` — watermark 0, runTimestamp 0 → ""
   - Mock setup: add `hydrationSyncCountFlow`, `hydrationSyncCaughtUpFlow`, `hydrationSyncRunTimestampFlow`, `lastHydrationSyncTimestampFlow` to `@BeforeEach` (all default to `flowOf(0)` / `flowOf(false)` / `flowOf(0L)`)
   - Mock `GetTodayHydrationTotalUseCase` default to return 0
2. Run verifier (expect fail)
3. Implement in `SyncViewModel.kt`:
   - Add `SyncUiState` fields: `hydrationTodayDisplay: String = ""`, `hydrationSyncStatus: String = ""`, `hydrationHistoryStatus: String = ""`
   - Add `HydrationRecord` read permission to `REQUIRED_HC_PERMISSIONS`
   - Add constructor parameter: `private val getTodayHydrationTotalUseCase: GetTodayHydrationTotalUseCase`
   - Add private state vars: `lastHydrationSyncTs`, `hydrationSyncCount`, `hydrationSyncCaughtUp`
   - In `init`: launch `loadTodayHydration()`, launch hydration sync status combine (follow glucose pattern at lines 201-215), launch hydration history status combine using `lastHydrationSyncTimestampFlow` + `hydrationSyncCaughtUpFlow` + `hydrationSyncRunTimestampFlow`
   - Add `formatHistoryAge(timestampMillis: Long): String` — extends relative time to weeks/months/years: `< 7 days` → "X days ago", `< 30 days` → "X weeks ago", `< 365 days` → "X months ago", `else` → "X years ago". Mark `internal` for testability.
   - Add `formatHydrationHistoryStatus(watermark: Long, caughtUp: Boolean, runTimestamp: Long): String` — if runTimestamp == 0 return "", if caughtUp return "History: up to date", if watermark == 0 return "History: starting...", else "History: synced to ${formatHistoryAge(watermark)}"
   - Add `loadTodayHydration()` private suspend method — calls use case, formats with `NumberFormat.getIntegerInstance()` for thousand separators, updates `hydrationTodayDisplay`
   - Add `refreshTodayHydration()` public method — launches `loadTodayHydration()` in viewModelScope
   - In periodic refresh loop (lines 160-192): add hydration sync status refresh and call `loadTodayHydration()` to pick up new readings
   - In `onSyncHealthReadingsComplete()` or wherever readings are refreshed post-sync: call `loadTodayHydration()`
4. Run verifier (expect pass)

**Notes:**
- Follow exact patterns from glucose/BP sync status observation (lines 201-231).
- `formatHistoryAge` is distinct from `formatRelativeTime` because user wants "2 months ago" / "5 years ago" style for all ranges, whereas `formatRelativeTime` switches to "MMM d" dates after 7 days.
- `NumberFormat.getIntegerInstance()` is `java.text` — available in Android, used in ViewModel (presentation layer).

**Defensive Requirements:**
- `loadTodayHydration()` must handle exceptions with try-catch + CancellationException rethrow (follow `loadLastBpReading` pattern at lines 271-287)

### Task 3: SyncScreen hydration card
**Linear Issue:** [HEA-202](https://linear.app/lw-claude/issue/HEA-202)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)

**Steps:**
1. No isolated unit test (Compose UI — verified visually + by verifier build check)
2. Add hydration card section after the Blood Glucose card (after line ~354):
   - Card > Column(fillMaxWidth, padding 16.dp, spacedBy 12.dp)
   - Title: "Hydration" in `titleMedium`
   - Daily total: if `uiState.hydrationTodayDisplay.isNotEmpty()` show `"${uiState.hydrationTodayDisplay} today"` in `bodyMedium`, else show "No readings today" in `bodyMedium` + `onSurfaceVariant`
   - Sync status: if `uiState.hydrationSyncStatus.isNotEmpty()` show in `bodySmall` + `onSurfaceVariant`
   - History status: if `uiState.hydrationHistoryStatus.isNotEmpty()` show in `bodySmall` + `onSurfaceVariant`
   - No action button (hydration is read-only from Health Connect)
   - No error state (no scanner)
3. Run verifier (expect pass)

**Notes:**
- Follow BP card structure (lines 226-289) minus the button, error text, and camera permission text.
- Card order: Nutrition → Blood Pressure → Blood Glucose → Hydration.

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Add a hydration sync card to the home screen showing daily total from Health Connect, sync push status, and history backfill progress.
**Linear Issues:** HEA-200, HEA-201, HEA-202
**Approach:** Create a GetTodayHydrationTotalUseCase that sums today's hydration readings, wire hydration sync status flows and history progress into SyncViewModel/SyncUiState, and add a hydration card to SyncScreen following the existing BP/glucose card pattern. The card is read-only (no action button) and adds a unique history progress indicator showing how far back the backfill has synced.
**Scope:** 3 tasks, 4 files (2 create, 2 modify), ~8 tests
**Key Decisions:** Daily total (not last reading) for the primary display; relative time format ("2 months ago") for history progress across all ranges; no action button since hydration is read-only from Health Connect.
**Risks:** HydrationRecord read permission not currently in REQUIRED_HC_PERMISSIONS — existing installs will need to re-grant Health Connect permissions after this update.
