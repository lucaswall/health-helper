# Implementation Plan

**Created:** 2026-04-17
**Source:** Backlog: HEA-205, HEA-206, HEA-207, HEA-208, HEA-209, HEA-210, HEA-211
**Linear Issues:** [HEA-205](https://linear.app/lw-claude/issue/HEA-205), [HEA-206](https://linear.app/lw-claude/issue/HEA-206), [HEA-207](https://linear.app/lw-claude/issue/HEA-207), [HEA-208](https://linear.app/lw-claude/issue/HEA-208), [HEA-209](https://linear.app/lw-claude/issue/HEA-209), [HEA-210](https://linear.app/lw-claude/issue/HEA-210), [HEA-211](https://linear.app/lw-claude/issue/HEA-211)
**Branch:** fix/hydration-sync-hardening

## Context Gathered

### Codebase Analysis

- **Hydration sync pipeline:** Push is in `FoodScannerHealthRepositoryImpl.toHydrationReadingDto` (line 112). Read is in `HealthConnectHydrationRepository.getReadingsResult` (per-page 10s + cumulative 120s timeouts, returns `ReadingsResult(readings, truncated)`). Orchestration is `SyncHealthReadingsUseCase.syncType` (generic over all three health types).
- **Hydration card UI:** `SyncScreen.kt:458-500` — renders title, today-total or "No readings today", `hydrationSyncStatus`, `hydrationHistoryStatus`. No hydration-specific permission branch (only the top-level Nutrition card banner covers missing perms).
- **ViewModel state:** `SyncUiState` already exposes `hydrationTodayDisplay`, `hydrationSyncStatus`, `hydrationHistoryStatus`, and a cross-cutting `missingHealthPermissions` set (which already contains `READ_HYDRATION` when denied, because it's in `HealthPermissions.REQUIRED`). No hydration-scoped permission flag yet.
- **30-second refresh loop:** `SyncViewModel.kt:152-190` ticks every 30s. Calls `loadTodayHydration()` without cancelling in-flight work (line 172) and re-derives sync-status strings for glucose/BP/hydration only when `count > 0 && !caughtUp` (lines 174-188).
- **Sync-status derivation:** `formatSyncStatus(count, caughtUp, runTs)` returns `"Up to date · X min ago"` when caught up — relative time freezes because the guard skips the reformat.
- **Sealed-result sync path already rethrows SecurityException:** `HealthConnectHydrationRepository.getReadingsResult:82-84` rethrows; `SyncHealthReadingsUseCase.syncType:142-146` catches it and populates `missingSink`. The "today total" path (`GetTodayHydrationTotalUseCase`) catches generic `Exception` and returns 0 — the only place SecurityException is swallowed.
- **Manifest & permissions:** `READ_HEALTH_DATA_HISTORY` is declared in `AndroidManifest.xml:26` and in `HealthPermissions.REQUIRED`, so the top-level missing-permissions banner already surfaces it. The outstanding risk is the unbounded read window size, not the permission itself.
- **DTO nullability:** `HydrationReadingDto.zoneOffset: String? = null` already supports sending null (line 39 of `HealthReadingsDtos.kt`).
- **HydrationReading model:** `zoneOffset: ZoneOffset? = null` already propagates nullability from `HydrationRecordMapper` (which forwards `record.startZoneOffset` verbatim).
- **Test conventions:** JUnit 5 + MockK. Existing tests: `SyncHealthReadingsUseCaseTest`, `FoodScannerHealthRepositoryImplTest`, `GetTodayHydrationTotalUseCaseTest`, `SyncViewModelTest`. ViewModel tests use Turbine.

### MCP Context

- **Linear MCP:** 7 backlog issues under the Health Helper team, all related to the v1.7.x hydration rollout. No Sentry references in the descriptions.

### Triage Results

**Planned:** HEA-205, HEA-206, HEA-207, HEA-208, HEA-209, HEA-210, HEA-211

**Canceled:** (none — all 7 issues were verified against the current codebase and are real)

## Tasks

### Task 1: Stop faking zoneOffset in hydration push DTO

**Linear Issue:** [HEA-205](https://linear.app/lw-claude/issue/HEA-205)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerHealthRepositoryImpl.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/FoodScannerHealthRepositoryImplTest.kt` (modify)

**Behavioral spec:**
- `toHydrationReadingDto` must pass `reading.zoneOffset` through **as-is** — if null, DTO field is null; if non-null, call `.toString()` and normalize `"Z"` → `"+00:00"`.
- Delete the `?: zoneOffsetString(reading.timestamp)` fallback for hydration.
- Do NOT change glucose/BP DTO mapping. Acceptance criterion explicitly asks to decide on scope; these readings originate from in-app camera capture where the system zone offset is truthful at capture time, unlike hydration records which can be imported from third-party apps across DST or timezone changes. Document this choice in the task notes; keep the scope tight.

**Steps:**
1. RED — Add three tests to `FoodScannerHealthRepositoryImplTest`:
   - `pushHydrationReadings serializes non-null zoneOffset normalized` — input `HydrationReading(volumeMl=250, timestamp=fixedTs, zoneOffset=ZoneOffset.of("+02:00"))`, assert DTO request captured via `slot<HydrationReadingRequest>()` has `readings[0].zoneOffset == "+02:00"`.
   - `pushHydrationReadings serializes UTC zoneOffset as +00:00` — `ZoneOffset.UTC` input, assert DTO `zoneOffset == "+00:00"`.
   - `pushHydrationReadings serializes null zoneOffset as null` — input with `zoneOffset = null`, assert DTO `zoneOffset` is null (NOT the current system default).
2. Run verifier (`./gradlew test`) — expect the null-offset test to fail (current code substitutes system offset).
3. GREEN — In `toHydrationReadingDto`, change `zoneOffset` expression to:

   `reading.zoneOffset?.toString()?.let { if (it == "Z") "+00:00" else it }`

   (drop the `?: zoneOffsetString(reading.timestamp)` tail).
4. Run verifier — expect pass.
5. REFACTOR — If `zoneOffsetString` helper is now only used by glucose/BP, leave it; no churn.

**Defensive requirements:**
- No new network/IO paths introduced; no timeout changes.
- No changes to DTO schema (wire field is already nullable).

**Notes:**
- **Migration note:** Server stores a `zoneOffset` column for hydration readings; rows previously synced with a falsified offset will remain until they are re-pushed. Acceptable — the value was wrong data anyway, and the change makes future pushes truthful. No DB migration needed on the Android side.
- Decision: glucose/BP intentionally left using `zoneOffsetString(timestamp)` because those readings are captured live in-app (system offset is accurate at capture). Hydration is special because it's imported from other apps. Record this in the commit body.

---

### Task 2: Don't mark caughtUp when read was truncated

**Linear Issue:** [HEA-206](https://linear.app/lw-claude/issue/HEA-206)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` (modify)

**Behavioral spec:**
- In `syncType`, the `batch.isEmpty()` branch (lines 116-121) must gate `setCaughtUp(true)` on `!result.truncated`.
- When `batch.isEmpty() && result.truncated`: still call `setCount(0)` and `setRunTimestamp(...)` so retry cadence shows recent activity, but do NOT call `setCaughtUp(...)` at all (leave the prior value untouched).
- Return value stays `watermark` in both sub-cases.

**Steps:**
1. RED — Add unit tests to `SyncHealthReadingsUseCaseTest`:
   - `syncType does not setCaughtUp when hydration read returns empty + truncated` — stub `hydrationRepository.getReadingsResult` to return `ReadingsResult(emptyList(), truncated = true)`; assert via `coVerify(exactly = 0) { settingsRepository.setHydrationSyncCaughtUp(any()) }` and `coVerify { settingsRepository.setHydrationSyncRunTimestamp(any()) }`.
   - Equivalent tests for glucose (`setGlucoseSyncCaughtUp`) and BP (`setBpSyncCaughtUp`) with their repositories returning truncated+empty.
   - Regression test: `syncType sets caughtUp=true when batch is empty and not truncated` — one per type or a single parametrized hydration variant — assert `setHydrationSyncCaughtUp(true)` is called.
2. Run verifier — expect new tests to fail.
3. GREEN — Modify the `batch.isEmpty()` branch of `syncType`:
   ```
   if (batch.isEmpty()) {
       setCount(0)
       if (!result.truncated) setCaughtUp(true)
       setRunTimestamp(System.currentTimeMillis())
       return watermark
   }
   ```
4. Run verifier — expect pass.

**Defensive requirements:**
- Do NOT touch the non-empty branch; `caughtUp = readings.size < MAX_READINGS_PER_RUN && !result.truncated` (line 126) already handles the truncated case correctly for non-empty batches.
- CancellationException handling at line 140 is unchanged.

---

### Task 3: Cap hydration first-run window

**Linear Issue:** [HEA-208](https://linear.app/lw-claude/issue/HEA-208)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncHealthReadingsUseCaseTest.kt` (modify)

**Behavioral spec:**
- Policy: on first-run for **hydration only**, start at `Instant.now().minus(Duration.ofDays(FIRST_RUN_LOOKBACK_DAYS))` instead of `Instant.EPOCH`. `FIRST_RUN_LOOKBACK_DAYS = 90` (aligns with typical HC retention windows and avoids spinning through years of data when `READ_HEALTH_DATA_HISTORY` is present). Subsequent runs continue to use `Instant.ofEpochMilli(watermark + 1)` as today.
- Glucose and BP are out of scope for this task — the rationale is hydration's importer-heavy data source (users can accumulate hundreds of records/day from tracking apps); glucose/BP are camera-logged in-app and will not have large histories. Document this explicitly.
- The policy is orthogonal to `READ_HEALTH_DATA_HISTORY` permission: the top-level missing-permissions banner already prompts if that grant is missing. This task does not add a new prompt.

**Implementation approach:**
- Add a `firstRunStart: Instant` parameter to the private `syncType` signature (defaults to `Instant.EPOCH` so glucose/BP callers don't change behavior).
- Inside `syncType`: `val start = if (watermark == 0L) firstRunStart else Instant.ofEpochMilli(watermark + 1)`.
- Hydration call site passes `firstRunStart = Instant.now().minus(Duration.ofDays(FIRST_RUN_LOOKBACK_DAYS))`.

**Steps:**
1. RED — Add test to `SyncHealthReadingsUseCaseTest`:
   - `syncType hydration first run reads from N days ago, not EPOCH` — stub `hydrationRepository.lastHydrationSyncTimestampFlow` → `flowOf(0L)`; capture the `start` arg via `coEvery { hydrationRepository.getReadingsResult(capture(startSlot), any()) }`; assert captured start is after `Instant.now().minus(Duration.ofDays(91))` and before `Instant.now().minus(Duration.ofDays(89))` (clock tolerance of 1 day).
   - `syncType hydration subsequent run reads from watermark+1ms` — stub watermark flow → `flowOf(1700000000000L)`; assert captured start equals `Instant.ofEpochMilli(1700000000001L)`.
   - `syncType glucose first run still reads from EPOCH (unchanged)` — regression.
2. Run verifier — expect first hydration test to fail.
3. GREEN — Refactor `syncType` to accept `firstRunStart: Instant = Instant.EPOCH`; update hydration call site to pass `Instant.now().minus(Duration.ofDays(FIRST_RUN_LOOKBACK_DAYS))`; add `const val FIRST_RUN_LOOKBACK_DAYS = 90L` to the companion object.
4. Run verifier — expect pass.

**Defensive requirements:**
- Per-page (10s) and cumulative (120s) timeouts in `HealthConnectHydrationRepository` are unchanged — the narrower window just reduces the likelihood of hitting them.
- No new permission prompts (HEALTH_DATA_HISTORY is already in REQUIRED set).
- Clock source is `Instant.now()` — tests tolerate ±1 day.

**Notes:**
- **Migration note:** This only affects users whose first hydration sync hasn't happened yet (watermark == 0). Users already past first sync are unaffected. Historical hydration data older than 90 days will not be imported on fresh installs going forward — acceptable since we have no product requirement to backfill beyond that.

---

### Task 4: Surface hydration permission denial in today-total path

**Linear Issue:** [HEA-207](https://linear.app/lw-claude/issue/HEA-207)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/GetTodayHydrationTotalUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/GetTodayHydrationTotalUseCaseTest.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)

**Behavioral spec:**
- `GetTodayHydrationTotalUseCase.invoke()` returns a sealed result:
  - Introduce `sealed interface TodayHydrationResult` in the same file with variants `Total(val volumeMl: Int)`, `PermissionDenied`, `Unavailable` (Health Connect client null or generic failure).
  - Rethrow `CancellationException`; catch `SecurityException` → `PermissionDenied`; catch other `Exception` → `Unavailable` (log via Timber as today).
- `SyncViewModel`:
  - Add `hydrationReadPermissionMissing: Boolean = false` to `SyncUiState`.
  - `loadTodayHydration()` branches on the sealed result:
    - `Total(v)` → set `hydrationTodayDisplay` as today (formatted mL if `v > 0` else empty), `hydrationReadPermissionMissing = false`.
    - `PermissionDenied` → set `hydrationTodayDisplay = ""`, `hydrationReadPermissionMissing = true`.
    - `Unavailable` → leave both fields untouched (preserves last-known good).
- `SyncScreen` hydration card (lines 458-500): below the title, add a hydration-scoped permission prompt row when `uiState.hydrationReadPermissionMissing == true`:
  - Error-colored text: `"Health Connect read permission denied for hydration."`
  - Button: `"Grant hydration access"` that calls `permissionLauncher.launch(setOf(HealthPermissions.READ_HYDRATION))`.
  - When the prompt is shown, suppress the "No readings today" / today-total row to avoid duplication.
- The cross-cutting top-level banner (at the Nutrition Sync card, lines 215-252) continues to show all missing perms; this new row is a hydration-specific hint that matches the card's context.

**Steps:**
1. RED — Add tests to `GetTodayHydrationTotalUseCaseTest`:
   - `invoke returns PermissionDenied when HydrationRepository throws SecurityException`.
   - `invoke returns Unavailable when HydrationRepository throws IOException`.
   - `invoke returns Total(sum) on success` — regression test; sum across multiple readings.
   - `invoke rethrows CancellationException`.
2. Run verifier — tests fail (new API missing).
3. GREEN — Modify `GetTodayHydrationTotalUseCase`: introduce sealed interface, change return type, keep `try { ... Total(sum) } catch { ... }` structure. Preserve `CancellationException` rethrow.
4. RED — Add ViewModel tests to `SyncViewModelTest`:
   - `loadTodayHydration PermissionDenied sets hydrationReadPermissionMissing=true and clears display`.
   - `loadTodayHydration Total(0) sets hydrationReadPermissionMissing=false and empty display`.
   - `loadTodayHydration Total(500) sets display to "500 mL" and hydrationReadPermissionMissing=false`.
5. Run verifier — fails (state field missing).
6. GREEN — Add `hydrationReadPermissionMissing: Boolean = false` to `SyncUiState`. Update `loadTodayHydration()` to `when` on the sealed result.
7. Run verifier — expect pass.
8. UI — Modify `SyncScreen.kt` hydration card: before the today-display block, add an `if (uiState.hydrationReadPermissionMissing) { ... } else { /* existing display */ }` branch as specified above. Follow the "Grant in Health Connect" button pattern from lines 231-238. Manually launch `./gradlew installDebug`, revoke hydration permission in HC settings, verify the hydration card shows the new prompt and the button launches the HC sheet for READ_HYDRATION only.

**Defensive requirements:**
- Permission launcher for the hydration-only button uses the existing `permissionLauncher` (already defined via `PermissionController.createRequestPermissionResultContract()`); do not add a second launcher.
- `SyncViewModel.loadTodayHydration` must still rethrow `CancellationException` (existing behavior) since the viewModelScope collector depends on cooperative cancellation.
- UI button must be resilient to the launcher throwing `ActivityNotFoundException` — reuse the existing `openHealthConnectSettings(context)` fallback pattern from `SyncScreen.kt:67-86` (wrap `permissionLauncher.launch(...)` in `try { launch } catch (e: Exception) { openHealthConnectSettings(context) }`).
- No changes to the top-level missing-permissions banner.

**Notes:**
- Acceptance-criterion satisfaction: the sealed result, ViewModel state flag, card prompt, and SecurityException unit test are all covered above.
- Do NOT merge this permission flag with `missingHealthPermissions`. The top-level set is a cross-cutting grant status; this flag reflects an observed empirical failure from the today-total read path and is allowed to flicker independently (e.g. HC is unavailable mid-read but permission set looks fine). They are orthogonal signals.

---

### Task 5: Serialize loadTodayHydration to prevent stale-value races

**Linear Issue:** [HEA-209](https://linear.app/lw-claude/issue/HEA-209)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**Behavioral spec:**
- Maintain a single `hydrationLoadJob: Job?` field on `SyncViewModel`.
- `loadTodayHydration()` (both the init-time call and the 30s loop call and `refreshTodayHydration`): cancel `hydrationLoadJob` (if non-null and active) before launching a new one. The job must be launched on `viewModelScope`.
- On cancellation, the in-flight coroutine must not overwrite state — `CancellationException` already rethrows from `loadTodayHydration()`'s internal catch (line 362) so the launched lambda will propagate and skip the `_uiState.update` call.

**Steps:**
1. RED — Add test to `SyncViewModelTest`:
   - `rapid loadTodayHydration calls do not emit stale value` — stub `getTodayHydrationTotalUseCase.invoke()` to suspend on first call (using a `CompletableDeferred`) and return a fresh `Total(500)` on second call. Invoke `refreshTodayHydration()` twice back-to-back. Using Turbine on `uiState`, complete the second invocation, then complete the first with `Total(100)`. Assert the UI never emits `"100 mL"` — final state is `"500 mL"`.
2. Run verifier — fails (race exists).
3. GREEN — Refactor:
   - Make `loadTodayHydration()` private and no longer suspend (it already only launches/updates state).
   - Change the signature to launch internally: `private fun loadTodayHydration() { hydrationLoadJob?.cancel(); hydrationLoadJob = viewModelScope.launch { try { ... when(result) { ... } } catch (_: CancellationException) { throw it } ... } }`.
   - Update the 3 call sites: init block (line 120-122) calls the new launcher directly (no outer `viewModelScope.launch`); the 30s loop (line 172) calls it directly; `refreshTodayHydration` (line 368-372) calls it directly.
4. Run verifier — expect pass.

**Defensive requirements:**
- Cancellation of `hydrationLoadJob` must happen BEFORE launching the new one (otherwise the new job could be the one cancelled).
- `CancellationException` path must rethrow — don't log-and-swallow, it breaks structured concurrency.
- Do NOT use `collectLatest`/`flatMapLatest` (overkill for a single-shot suspend call; job cancellation is simpler and does not require introducing a new flow).
- The 30s loop itself (`while (isActive) { delay(30_000); ... }`) must not be affected — only the inner loader is re-entrant.

**Notes:**
- This task depends on Task 4 (the sealed result is what `loadTodayHydration` now branches on — the new job pattern must carry that through).

---

### Task 6: Refresh sync status strings every 30s even when caught up

**Linear Issue:** [HEA-210](https://linear.app/lw-claude/issue/HEA-210)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**Behavioral spec:**
- In the 30s loop (`SyncViewModel.kt:174-188`), drop the `count > 0 && !caughtUp` guards. Keep the `runTimestamp > 0` guard (so we don't reformat when there's no sync history yet).
- Apply the same fix symmetrically to glucose, BP, and hydration — the issue calls this out explicitly.
- Rewritten guards (per type):
  - `if (lastHydrationSyncTs > 0) _uiState.update { it.copy(hydrationSyncStatus = formatSyncStatus(hydrationSyncCount, hydrationSyncCaughtUp, lastHydrationSyncTs)) }`
  - Same shape for glucose and BP.

**Steps:**
1. RED — Add test to `SyncViewModelTest`:
   - `sync status string is re-emitted every 30s when hydration is caught up` — use `TestScope`/`advanceTimeBy`. Seed `hydrationSyncCount=0`, `hydrationSyncCaughtUp=true`, `lastHydrationSyncTs = Instant.now().minus(59s).toEpochMilli()`. Advance by 30s past the threshold where `formatRelativeTime` rolls from "Just now" / "X min ago" to the next bucket. Assert `uiState.hydrationSyncStatus` changes across the tick.
   - Equivalent test for `glucoseSyncStatus` and `bpSyncStatus`.
2. Run verifier — fails (guard skips reformat).
3. GREEN — Replace the three guarded `_uiState.update` blocks in the 30s loop. Keep the `> 0` runTimestamp check.
4. Run verifier — expect pass.

**Defensive requirements:**
- Keep the existing `if (lastSyncTimestamp > 0)` guard for the nutrition sync status (line 155-157) — that one doesn't have the stale-caughtUp issue because `formatRelativeTime` is always called on a valid timestamp.
- Don't introduce a new flow — reuse the existing 30s `while (isActive) { delay(30_000); ... }` loop.
- No cancellation concerns — this is pure state formatting with no IO.

**Notes:**
- This is a cross-type fix, matching the issue's explicit ask.

---

### Task 7: Differentiate "no readings today" from "pre-first-sync" wording

**Linear Issue:** [HEA-211](https://linear.app/lw-claude/issue/HEA-211)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)

**Behavioral spec:**
- In the hydration card (line 471-482), when `uiState.hydrationTodayDisplay.isEmpty()` AND `uiState.hydrationReadPermissionMissing == false` (Task 4 handles the perm case):
  - If `uiState.hydrationSyncStatus.isEmpty()` (no sync has run yet — `formatSyncStatus` returns `"Not synced to food-scanner"` only when `runTimestampMs == 0L`, and returns empty for no data only transiently; practical check is `hydrationSyncStatus` empty or equal to `"Not synced to food-scanner"`): show `"Waiting for first sync…"`.
  - Otherwise: show `"No water logged yet today"` (friendlier replacement for `"No readings today"`).
- The "Waiting for first sync…" text uses the same `bodyMedium` style / `onSurfaceVariant` color as the existing empty row.

**Steps:**
1. Implement directly in `SyncScreen.kt`. This is a string + simple branch change — no new state or logic. The ViewModel already exposes `hydrationSyncStatus` so no new field is needed.

   Approximate structure inside the existing `else` branch at line 476:
   ```
   val noSyncYet = uiState.hydrationSyncStatus.isEmpty() ||
       uiState.hydrationSyncStatus == "Not synced to food-scanner"
   Text(
       text = if (noSyncYet) "Waiting for first sync…" else "No water logged yet today",
       ...
   )
   ```
2. Manual verification (no unit test — this is pure UI string selection): `./gradlew installDebug`, install on a fresh app state (clear data) — card should show `"Waiting for first sync…"`. After first sync with zero readings, it should show `"No water logged yet today"`.
3. No automated test for this task — UI string selection without ViewModel logic doesn't warrant a Compose test harness run for a single branch. Document this in the plan review.

**Defensive requirements:**
- String selection runs inside a Composable — no IO, no coroutines, no launchers.
- Must not regress Task 4: when `hydrationReadPermissionMissing == true`, the Task 4 permission block takes precedence and this `else` branch is not entered.

**Notes:**
- This task depends on Task 4 (the conditional must live inside the not-permission-denied branch).

---

## Post-Implementation Checklist

1. Run `bug-hunter` agent — review all changes for coroutine leaks, missing cancellation paths, and DTO serialization edge cases.
2. Run `verifier` agent — `./gradlew test` must pass with zero warnings; `./gradlew assembleDebug` must succeed.
3. Manual smoke test on device:
   - Fresh install → hydration card shows `"Waiting for first sync…"`.
   - Revoke hydration permission in HC settings → hydration card shows the new permission prompt + grant button.
   - Grant permission back → today total loads.
   - Leave the app open for 60+ seconds on a caught-up sync state → sync-status relative time updates (no longer frozen at "Just now").

## Plan Summary

**Objective:** Harden hydration sync correctness (zoneOffset, truncated reads, first-run window) and fix the hydration card UX (permission surfacing, stale status, wording) before the next release.

**Linear Issues:** HEA-205, HEA-206, HEA-207, HEA-208, HEA-209, HEA-210, HEA-211

**Approach:** Land the three data-integrity bug fixes first (DTO zoneOffset null passthrough, caughtUp-gated-on-truncated, first-run lookback cap), then the ViewModel/UI improvements (sealed-result permission surface, job-serialized hydration reload, unconditional 30s status reformat, friendlier empty-state wording). Every task is TDD with tests written first; UI-only string changes verified manually.

**Scope:** 7 tasks, 7 source files modified (no new files), 4 test files modified, ~14 new unit tests total.

**Key Decisions:**
- HEA-205 fix applies to hydration only — glucose/BP retain the `zoneOffsetString(timestamp)` fallback because those readings are always captured live in-app where the system offset is truthful.
- HEA-208 uses a 90-day lookback constant; no new permission prompt is added because `READ_HEALTH_DATA_HISTORY` is already in `HealthPermissions.REQUIRED`.
- HEA-207 adds a hydration-scoped `hydrationReadPermissionMissing` UI flag separate from the cross-cutting `missingHealthPermissions` set — they're orthogonal signals (grant state vs. observed read failure).
- HEA-209 uses explicit `Job` cancellation rather than `collectLatest`/`flatMapLatest` — simpler and matches the surrounding code style.

**Risks:**
- Task 4 changes the `GetTodayHydrationTotalUseCase` public signature (return type). Only one caller (`SyncViewModel`) exists today; confirmed via grep.
- Task 6 changes visible UI strings that may appear in Sentry breadcrumbs or prior screenshots; minor.
- Manual UI verification required for Tasks 4 and 7 — Compose test harness is not set up for the hydration card specifically.
