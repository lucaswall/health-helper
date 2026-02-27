# Implementation Plan

**Status:** COMPLETE
**Branch:** feat/HEA-80-settings-save-reset
**Issues:** HEA-80, HEA-81, HEA-82, HEA-79, HEA-78
**Created:** 2026-02-27
**Last Updated:** 2026-02-27

## Summary

Implement Settings Save/Reset workflow, reduce sync interval minimum, disable navigation transitions, and improve tooling (bug-hunter agent and plan templates). The primary change (HEA-80) decouples settings input from persistence — users edit freely and explicitly save or discard changes. Supporting changes improve UX (HEA-81, HEA-82) and strengthen the development pipeline (HEA-78, HEA-79).

## Issues

### HEA-80: Settings screen persists every keystroke to DataStore instead of explicit save

**Priority:** Medium
**Labels:** Improvement
**Description:** The Settings screen text fields and slider write to DataStore on every change via ViewModel update methods. This causes wasteful I/O and gives the user no way to discard changes. Add Save and Reset buttons with dirty-state tracking.

**Acceptance Criteria:**
- [ ] Text field and slider changes update only local UI state (no immediate persistence)
- [ ] Save button persists all current settings to repository
- [ ] Reset button reverts UI to last-persisted values
- [ ] Both buttons disabled when there are no unsaved changes
- [ ] Save errors are handled gracefully (log + user feedback)
- [ ] Existing test coverage updated for new behavior

### HEA-81: Sync interval minimum is too high and default should be 5 minutes

**Priority:** Low
**Labels:** Improvement
**Description:** The sync interval slider minimum is 15 minutes with a default of 15. Change minimum to 5 minutes and default to 5 minutes.

**Acceptance Criteria:**
- [ ] Slider range is 5-120 minutes
- [ ] Default sync interval is 5 minutes (UI state and repository default)
- [ ] Slider steps recalculated for 5-minute increments
- [ ] Default is consistent between SettingsUiState, DataStoreSettingsRepository, and SyncScheduler

### HEA-82: Screen transitions show distracting fade/flash animation

**Priority:** Low
**Labels:** Improvement
**Description:** Navigation between Sync and Settings screens uses default crossfade transition which creates a visible flash. Disable transitions for instant screen switches.

**Acceptance Criteria:**
- [ ] No fade/flash animation on screen navigation
- [ ] Both forward navigation and back navigation are instant

### HEA-79: Bug-hunter agent misses durability semantics, dead test code, and UI-exposed internals

**Priority:** Medium
**Labels:** Improvement
**Description:** Bug-hunter agent missed 3 categories of bugs in the HEA-44 audit. Add checks for: SharedPreferences durability (apply vs commit), dead test variables, and raw exception messages reaching UI.

**Acceptance Criteria:**
- [ ] Bug-hunter checks for SharedPreferences/DataStore write durability in suspend functions
- [ ] Bug-hunter checks for declared-but-unasserted variables in test files
- [ ] Bug-hunter checks for raw Exception.message or server text flowing to UI state

### HEA-78: Plan template missing cross-cutting requirements checklist

**Priority:** Medium
**Labels:** Improvement
**Description:** Plans miss cross-cutting concerns that span multiple files. Add a pattern-triggered checklist to plan-backlog and plan-inline skills that catches: networking -> timeouts, UI error messages -> sanitization, coroutine launch -> error handling, SharedPreferences -> durability.

**Acceptance Criteria:**
- [ ] plan-backlog SKILL.md has a cross-cutting requirements checklist
- [ ] plan-inline SKILL.md has a cross-cutting requirements checklist
- [ ] Checklist is triggered by patterns in proposed tasks

## Prerequisites

- [ ] Build compiles successfully (`./gradlew assembleDebug`)
- [ ] All tests pass (`./gradlew test`)
- [ ] Working tree is clean on main branch

## Implementation Tasks

### Task 1: Decouple SettingsViewModel update methods from persistence

**Issue:** HEA-80
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** - Write failing tests:
   - Test: `updateApiKey updates UI state but does not persist to repository`
     - Create ViewModel, advanceUntilIdle
     - Call `viewModel.updateApiKey("new-key")`, advanceUntilIdle
     - Assert `uiState.apiKey == "new-key"`
     - Assert `coVerify(exactly = 0) { settingsRepository.setApiKey(any()) }`
   - Test: `updateBaseUrl updates UI state but does not persist to repository`
     - Same pattern for baseUrl
   - Test: `updateSyncInterval updates UI state but does not persist to repository`
     - Same pattern for syncInterval
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SettingsViewModelTest"`
   - Verify: Tests fail because current implementation calls repository on every update

2. **GREEN** - Make tests pass:
   - Remove the `viewModelScope.launch { settingsRepository.set*() }` blocks from `updateApiKey()`, `updateBaseUrl()`, `updateSyncInterval()`
   - Keep the existing `_uiState.update { it.copy(...) }` calls (these are correct)
   - Run tests, verify pass

3. **REFACTOR** - Update existing tests:
   - Invert or remove the 3 existing tests that assert `coVerify { settingsRepository.set*() }` after calling update methods — these should now verify the opposite (no repository call)
   - Clean up any stale test setup

**Notes:**
- After this task, settings edits are local only. Persistence is restored in Task 2 via `save()`
- Pattern reference: existing `_uiState.update { it.copy(...) }` in `SettingsViewModel.kt:54`
- Pattern reference: `SettingsViewModelTest.kt` for test conventions (StandardTestDispatcher, Turbine, MockK)

### Task 2: Add hasUnsavedChanges, save(), and reset() to SettingsViewModel

**Issue:** HEA-80
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** - Write failing tests:
   - Test: `hasUnsavedChanges is false when UI matches persisted state`
     - Create ViewModel, advanceUntilIdle
     - Assert `uiState.hasUnsavedChanges == false`
   - Test: `hasUnsavedChanges is true after updateApiKey with different value`
     - Create ViewModel, advanceUntilIdle
     - Call `updateApiKey("changed-key")`
     - Assert `uiState.hasUnsavedChanges == true`
   - Test: `hasUnsavedChanges is true after updateBaseUrl with different value`
   - Test: `hasUnsavedChanges is true after updateSyncInterval with different value`
   - Test: `save persists all current settings to repository`
     - Create ViewModel, advanceUntilIdle
     - Call `updateApiKey("new")`, `updateBaseUrl("new-url")`, `updateSyncInterval(60)`
     - Call `save()`, advanceUntilIdle
     - `coVerify { settingsRepository.setApiKey("new") }`
     - `coVerify { settingsRepository.setBaseUrl("new-url") }`
     - `coVerify { settingsRepository.setSyncInterval(60) }`
   - Test: `save sets hasUnsavedChanges to false`
     - After `save()`, assert `uiState.hasUnsavedChanges == false`
   - Test: `reset reverts UI state to persisted values`
     - Create ViewModel (persisted: apiKey="test-key"), advanceUntilIdle
     - Call `updateApiKey("changed")`, assert apiKey == "changed"
     - Call `reset()`
     - Assert apiKey == "test-key" (original persisted value)
   - Test: `reset sets hasUnsavedChanges to false`
   - Test: `save handles repository exception gracefully`
     - Mock repository to throw on `setApiKey()`
     - Call `save()`, advanceUntilIdle
     - Assert no crash, verify Timber.e was called or error state is set
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SettingsViewModelTest"`
   - Verify: Tests fail (methods don't exist yet)

2. **GREEN** - Make tests pass:
   - Add `hasUnsavedChanges: Boolean = false` to `SettingsUiState`
   - Track persisted state separately (private snapshot of last-loaded/saved settings)
   - Init block: when repository flows emit, update both persisted state and UI state (only update UI state if no unsaved changes exist)
   - `save()`: persist all 3 settings via repository in `viewModelScope.launch`, update persisted state on success, recalculate `hasUnsavedChanges`
   - `reset()`: copy persisted state back to UI state, set `hasUnsavedChanges = false`
   - After each update method, recalculate `hasUnsavedChanges` by comparing apiKey/baseUrl/syncInterval between UI and persisted state
   - Run tests, verify pass

3. **REFACTOR** - Clean up:
   - Ensure `isConfigured` is still derived from current UI values (not part of dirty tracking)
   - Ensure hasUnsavedChanges comparison excludes `isConfigured` (it's computed)

**Defensive Requirements:**
- `save()` must catch exceptions from repository calls, log with Timber, and not crash
- `save()` should attempt all 3 persist operations even if one fails (don't fail fast)
- `reset()` is purely local state manipulation — no error path needed
- No timeout needed (DataStore writes are local disk I/O)

**Notes:**
- Pattern reference: `SyncViewModel` for `viewModelScope.launch` error handling
- Pattern reference: existing `SettingsViewModel` init block for flow collection
- The `isConfigured` field remains computed from apiKey and baseUrl emptiness

### Task 3: Add Save and Reset buttons to SettingsScreen

**Issue:** HEA-80
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` (modify)

**TDD Steps:**

1. **RED** - No unit test (Compose UI). Verified via build check and manual testing.

2. **GREEN** - Implement:
   - Add a `Row` after the Slider containing two buttons with `Arrangement.spacedBy(8.dp)`:
     - `OutlinedButton` for Reset: calls `viewModel.reset()`, enabled when `uiState.hasUnsavedChanges`, label "Reset"
     - `Button` (filled) for Save: calls `viewModel.save()`, enabled when `uiState.hasUnsavedChanges`, label "Save"
   - Both buttons should use `Modifier.weight(1f)` within the Row for equal sizing
   - Import `Button`, `OutlinedButton`, `Row` from `androidx.compose.material3`

3. **REFACTOR** - Ensure consistent styling with rest of screen

**Notes:**
- Pattern reference: `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt:132-139` for Material 3 Button usage
- Trailing commas on multi-line parameter lists per project conventions
- Buttons placed after Slider, before any future content

### Task 4: Update sync interval range and default

**Issue:** HEA-81
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** - Write failing test:
   - Test: `default SettingsUiState has syncInterval of 5`
     - Assert `SettingsUiState().syncInterval == 5`
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SettingsViewModelTest"`
   - Verify: Test fails (default is still 15)

2. **GREEN** - Make changes:
   - `SettingsUiState`: change default `syncInterval` from `15` to `5`
   - `DataStoreSettingsRepository.kt:32`: change `DEFAULT_SYNC_INTERVAL` from `15` to `5`
   - `SettingsScreen.kt`: change Slider `valueRange` from `15f..120f` to `5f..120f`
   - `SettingsScreen.kt`: change Slider `steps` from `6` to `22`
   - Run tests, verify pass

3. **REFACTOR** - Update any tests referencing old default:
   - Search test files for hardcoded `15` as sync interval — update to `5` where it represents the default
   - Ensure `setUp()` mock values are intentional (current setup uses `30`, which is fine as a non-default test value)

**Notes:**
- Slider steps calculation: range 5-120 with 5-min increments -> (120-5)/5 = 23 intervals -> 24 positions -> steps = 24 - 2 = 22
- `DEFAULT_SYNC_INTERVAL` in `DataStoreSettingsRepository.kt:32` must match `SettingsUiState` default
- Also check `SyncScheduler` or `SyncWorker` for any hardcoded interval defaults and update if found

### Task 5: Disable navigation transition animations

**Issue:** HEA-82
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` (modify)

**TDD Steps:**

1. **RED** - No unit test (Compose navigation UI). Verified manually.

2. **GREEN** - Implement:
   - Add transition parameters to the `NavHost` composable:
     - `enterTransition = { EnterTransition.None }`
     - `exitTransition = { ExitTransition.None }`
     - `popEnterTransition = { EnterTransition.None }`
     - `popExitTransition = { ExitTransition.None }`
   - Add imports for `androidx.compose.animation.EnterTransition` and `androidx.compose.animation.ExitTransition`

3. **REFACTOR** - No further cleanup needed

**Notes:**
- Set transitions at NavHost level so they apply globally to all destinations
- Per-destination overrides can be added later if different transitions are desired for specific routes

### Task 6: Add missing checks to bug-hunter agent

**Issue:** HEA-79
**Files:**
- `.claude/agents/bug-hunter.md` (modify)

**Steps:**

1. Add three new check items to existing sections in `bug-hunter.md`:

   **Under "Resource Management" section, add a "Data Persistence" subsection:**
   - "SharedPreferences writes in suspend functions — verify `commit()` or DataStore `edit {}` for durability, not `apply()` which is fire-and-forget and may lose data if process dies"

   **Under "Test Changes > Test Validity" section, add:**
   - "Variables declared and populated in tests but never referenced in assertions or verify calls — dead test code that gives false confidence in coverage"

   **Under a new "UI Safety" subsection (after Security section):**
   - "Raw `Exception.message`, `e.localizedMessage`, or server/API response text flowing directly to UI-visible state (Text composable, Toast, Snackbar, error state fields) — use generic user-facing messages and log the raw message for debugging only"

2. Verify the new checks don't duplicate existing items and fit naturally in the document structure.

**Notes:**
- These are pattern-based checks applied when reviewing diffs
- Discovered from HEA-44 audit where the 3-reviewer review caught bugs that bug-hunter missed
- Each check should be concrete enough for the agent to grep/pattern-match in diffs

### Task 7: Add cross-cutting requirements checklist to plan skills

**Issue:** HEA-78
**Files:**
- `.claude/skills/plan-backlog/SKILL.md` (modify)
- `.claude/skills/plan-inline/SKILL.md` (modify)

**Steps:**

1. Add a "Cross-Cutting Requirements Checklist" to both skill files. The checklist is a table of pattern triggers and required specifications:

   | Pattern Detected in Plan | Required Specification |
   |--------------------------|----------------------|
   | Networking code (HTTP client, API calls, Ktor) | Timeout value and timeout error handling behavior |
   | Error messages shown to users (UI state error fields, Toast, Snackbar) | Sanitization — generic user message, raw error logged only |
   | `viewModelScope.launch` or coroutine builders | Error handling (try-catch) and behavior on exception |
   | SharedPreferences / DataStore writes in suspend context | Durability semantics (commit vs apply, edit vs write) |
   | Health Connect operations | Timeout, permission check, SDK availability check |
   | Intent / Activity launch | ActivityNotFoundException handling and fallback |
   | Repeated user-triggered operations (button clicks, pull-to-refresh) | Cancellation of in-flight work before starting new |

2. **In plan-backlog SKILL.md**: Integrate into the existing Section 4.4 "Validate Plan Against CLAUDE.md" — add the cross-cutting table as an additional validation pass after the existing per-task checks.

3. **In plan-inline SKILL.md**: Add to the validation step (step 8 in the workflow: "Validate plan against CLAUDE.md") with the same cross-cutting table.

4. The validation instruction should say: "After writing all tasks, scan the entire plan for these patterns. If a pattern is detected in ANY task, verify the corresponding specification exists in that task's Defensive Requirements or TDD steps. If missing, add it before finalizing the plan."

**Notes:**
- This supplements per-task "Defensive Requirements" with a global sweep across all tasks
- The checklist triggers on patterns found anywhere in the plan, not just per-task
- Reference: HEA-44 audit — HttpTimeout was missing because no single issue's scope explicitly included networking, but the plan added Ktor client code

### Task 8: Integration & Verification

**Issue:** HEA-80, HEA-81, HEA-82, HEA-79, HEA-78
**Files:**
- All files from previous tasks

**Steps:**

1. Run full test suite: `./gradlew test`
2. Build check: `./gradlew assembleDebug`
3. Manual verification:
   - [ ] Settings screen: type in text fields — no DataStore writes until Save pressed
   - [ ] Settings screen: change slider — no DataStore write until Save pressed
   - [ ] Settings screen: Save button persists all values, buttons become disabled
   - [ ] Settings screen: Reset button reverts to last-saved values, buttons become disabled
   - [ ] Settings screen: Save/Reset buttons are disabled when no changes made
   - [ ] Settings screen: slider range starts at 5 minutes, default is 5 minutes
   - [ ] Navigation: no fade/flash between Sync and Settings screens
   - [ ] Bug-hunter agent: review `.claude/agents/bug-hunter.md` for 3 new checks present
   - [ ] Plan skills: review `.claude/skills/plan-backlog/SKILL.md` and `.claude/skills/plan-inline/SKILL.md` for cross-cutting checklist

## MCP Usage During Implementation

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `save_issue` | Move issues to "In Progress" when starting, "Done" when complete |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| Repository throws on save() | Log error with Timber, don't crash, optionally set error state | Unit test (Task 2) |
| Individual setter fails in save() | Continue with remaining setters, don't fail fast | Unit test (Task 2) |
| Empty apiKey/baseUrl saved | Allowed — isConfigured becomes false | Existing unit test |

## Risks & Open Questions

- [ ] Back navigation with unsaved changes: Currently no confirmation dialog planned. Could be a future enhancement but out of scope for HEA-80.
- [ ] Slider with 22 steps (5-min increments from 5 to 120) may feel crowded on small screens. Acceptable for now; can be refined to coarser steps later.
- [ ] SyncScheduler/SyncWorker may have hardcoded interval defaults — implementer should check and update if found.

## Scope Boundaries

**In Scope:**
- Settings Save/Reset workflow with dirty-state tracking (HEA-80)
- Sync interval range 5-120 with 5-minute default (HEA-81)
- Disable navigation transitions (HEA-82)
- Bug-hunter agent: 3 new check categories (HEA-79)
- Plan skills: cross-cutting requirements checklist (HEA-78)

**Out of Scope:**
- Unsaved changes confirmation dialog on back navigation
- Non-linear slider step distribution
- Automated testing of agent/skill markdown changes
- Any other Backlog issues not listed above

---

## Iteration 1

**Implemented:** 2026-02-27
**Method:** Single-agent

### Tasks Completed This Iteration
- Task 1: Decouple SettingsViewModel update methods from persistence
- Task 2: Add hasUnsavedChanges, save(), and reset() to SettingsViewModel
- Task 3: Add Save and Reset buttons to SettingsScreen
- Task 4: Update sync interval range and default
- Task 5: Disable navigation transition animations
- Task 6: Add missing checks to bug-hunter agent
- Task 7: Add cross-cutting requirements checklist to plan skills
- Task 8: Integration & Verification

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` — Decoupled update methods from persistence, added `hasUnsavedChanges`, `save()`, `reset()`, `PersistedSettings` tracking, partial-failure-safe save with `withDirtyFlag()`, changed default syncInterval to 5
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` — Replaced 3 persist-on-update tests with no-persist assertions, added 9 new tests for dirty state, save, reset, error handling
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` — Added Save/Reset buttons, changed slider range to 5-120 with 22 steps
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — Changed DEFAULT_SYNC_INTERVAL from 15 to 5
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` — Updated default sync interval test from 15 to 5
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` — Added EnterTransition.None/ExitTransition.None to NavHost
- `.claude/agents/bug-hunter.md` — Added Data Persistence, dead test code, and UI Safety check sections
- `.claude/skills/plan-backlog/SKILL.md` — Added cross-cutting requirements sweep (Section 4.5)
- `.claude/skills/plan-inline/SKILL.md` — Added cross-cutting requirements sweep (step 9)

### Linear Updates
- HEA-80: Todo → In Progress → Review
- HEA-81: Todo → In Progress → Review
- HEA-82: Todo → In Progress → Review
- HEA-79: Todo → In Progress → Review
- HEA-78: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 2 bugs (partial save corruption, weak error test), fixed before proceeding
- verifier: All 138 tests pass, zero warnings, build successful

### Review Findings

Summary: 12 findings evaluated, 8 require fix (Team: security, reliability, quality reviewers)
- FIX: 7 issue(s) — Linear issues created
- DISCARDED: 4 finding(s) — false positives / not applicable

**Issues requiring fix:**
- [HIGH] BUG: Race condition — combine collector overwrites UI state during save() (`SettingsViewModel.kt:41-62,82-117`) — HEA-83
- [MEDIUM] COROUTINE: Unhandled exceptions in settings flow collection and API key migration (`SettingsViewModel.kt:41`, `DataStoreSettingsRepository.kt:68-78`) — HEA-84
- [MEDIUM] BUG: isConfigured() returns incorrect result before API key migration (`DataStoreSettingsRepository.kt:107-112`) — HEA-85
- [MEDIUM] SECURITY: EncryptedSharedPreferences crypto operations block main thread (`DataStoreSettingsRepository.kt:43,56,108`) — HEA-86
- [LOW] BUG: No user-visible error feedback when settings save fails (`SettingsViewModel.kt:82-117`) — HEA-87
- [LOW] CONVENTION: Misleading isConfigured test name and dead mock (`SettingsViewModelTest.kt:280-294`) — HEA-88
- [LOW] CONVENTION: Partial assertion in reset test — only checks apiKey (`SettingsViewModelTest.kt:219-235`) — HEA-89

**Discarded findings (not bugs):**
- [DISCARDED] SECURITY: API key may remain in plaintext if Keystore fails persistently (`DataStoreSettingsRepository.kt:28`) — Code correctly handles retry via `migrationComplete` flag; system-level Keystore failure is not actionable by app code
- [DISCARDED] SECURITY: API key held as plain String in JVM heap (`SettingsViewModel.kt:17`) — Inherent to displaying the value in a UI text field; no practical fix exists
- [DISCARDED] CONVENTION: Dead mock setup in setUp() for lastSyncedDateFlow and isConfigured() (`SettingsViewModelTest.kt:38-39`) — Style-only; standard test class pattern with zero correctness impact
- [DISCARDED] CONVENTION: Migration retry path untested (`DataStoreSettingsRepositoryTest.kt:127-148`) — Core safety behavior (migration not falsely marked complete on failure) is tested; retry mechanism is straightforward

### Linear Updates
- HEA-80: Review → Merge (original task completed)
- HEA-81: Review → Merge (original task completed)
- HEA-82: Review → Merge (original task completed)
- HEA-79: Review → Merge (original task completed)
- HEA-78: Review → Merge (original task completed)
- HEA-83: Created in Todo (Fix: race condition in save)
- HEA-84: Created in Todo (Fix: unhandled exceptions in flow collection)
- HEA-85: Created in Todo (Fix: isConfigured pre-migration)
- HEA-86: Created in Todo (Fix: EncryptedPrefs main thread blocking)
- HEA-87: Created in Todo (Fix: save error UI feedback)
- HEA-88: Created in Todo (Fix: misleading test name)
- HEA-89: Created in Todo (Fix: partial reset assertion)

<!-- REVIEW COMPLETE -->

### Continuation Status
All tasks completed. Fix Plan required.

---

## Fix Plan

**Source:** Review findings from Iteration 1
**Linear Issues:** [HEA-83](https://linear.app/lw-claude/issue/HEA-83), [HEA-84](https://linear.app/lw-claude/issue/HEA-84), [HEA-85](https://linear.app/lw-claude/issue/HEA-85), [HEA-86](https://linear.app/lw-claude/issue/HEA-86), [HEA-87](https://linear.app/lw-claude/issue/HEA-87), [HEA-88](https://linear.app/lw-claude/issue/HEA-88), [HEA-89](https://linear.app/lw-claude/issue/HEA-89)

### Fix 1: Race condition — combine collector overwrites UI during save
**Linear Issue:** [HEA-83](https://linear.app/lw-claude/issue/HEA-83)

1. Write test in `SettingsViewModelTest.kt`: `save does not overwrite UI state with intermediate flow emissions` — modify apiKey, baseUrl, syncInterval, call save(), verify UI state retains all 3 values throughout (not just after completion)
2. Add `isSaving` flag to `SettingsViewModel`. Set `true` before launching save coroutine, `false` after all writes complete
3. In the `combine().collect` handler: when `isSaving` is true, update `persistedSettings` but skip `_uiState.update` — the save coroutine handles UI state when it finishes
4. After save completes: update `persistedSettings` with successfully saved values, recalculate dirty flag, clear `isSaving`

### Fix 2: Unhandled exceptions in flow collection and migration
**Linear Issue:** [HEA-84](https://linear.app/lw-claude/issue/HEA-84)

1. Write test in `SettingsViewModelTest.kt`: `init handles repository flow exception gracefully` — mock apiKeyFlow to throw IOException, verify ViewModel doesn't crash and UI stays at defaults
2. Write test in `DataStoreSettingsRepositoryTest.kt`: `apiKeyFlow handles migration failure gracefully` — configure encryptedPrefs to throw on getString, verify flow still emits (empty default)
3. Wrap `migrateIfNeeded()` call in `callbackFlow` with try-catch; on failure, log with Timber and emit empty default
4. Wrap `combine().collect` in `SettingsViewModel.init` with try-catch; on failure, log with Timber (UI stays at defaults)

### Fix 3: isConfigured() missing migration call
**Linear Issue:** [HEA-85](https://linear.app/lw-claude/issue/HEA-85)

1. Write test in `DataStoreSettingsRepositoryTest.kt`: `isConfigured returns true when API key exists only in DataStore (pre-migration)` — populate DataStore with legacy key, verify `isConfigured()` returns true
2. Add `migrateIfNeeded()` call at the start of `isConfigured()`

### Fix 4: EncryptedPrefs reads on main thread
**Linear Issue:** [HEA-86](https://linear.app/lw-claude/issue/HEA-86)

1. Wrap `migrateIfNeeded()` body entirely in `withContext(Dispatchers.IO)` (lines 40-65) — this covers getString calls at lines 43 and 56, plus the existing commit at line 52-53 (already IO but nested is fine)
2. Wrap `isConfigured()` body in `withContext(Dispatchers.IO)` (lines 107-112) — covers getString at line 108

### Fix 5: Save error UI feedback
**Linear Issue:** [HEA-87](https://linear.app/lw-claude/issue/HEA-87)

1. Write test in `SettingsViewModelTest.kt`: `save sets error message when repository write fails` — mock setApiKey to throw, call save(), verify `uiState.saveError` is non-null with user-facing message
2. Write test: `saveError is cleared on next successful save`
3. Add `saveError: String? = null` field to `SettingsUiState`
4. In `save()`: if `anyFailed`, set `saveError = "Some settings could not be saved. Please try again."`; if all succeed, set `saveError = null`
5. In `SettingsScreen.kt`: show `saveError` as a `Text` with `MaterialTheme.colorScheme.error` color, below the Save/Reset buttons, when non-null

### Fix 6: Misleading test name and dead mock
**Linear Issue:** [HEA-88](https://linear.app/lw-claude/issue/HEA-88)

1. Rename test from `isConfigured state reflects repository isConfigured` to `isConfigured is false when apiKey and baseUrl are empty`
2. Remove the dead `coEvery { settingsRepository.isConfigured() } returns false` line from the test
3. Remove the dead `coEvery { settingsRepository.isConfigured() } returns true` line from `setUp()` (line 39)

### Fix 7: Partial assertion in reset test
**Linear Issue:** [HEA-89](https://linear.app/lw-claude/issue/HEA-89)

1. In the existing `reset reverts UI state to persisted values` test:
   - Before reset: also call `updateBaseUrl("changed-url")` and `updateSyncInterval(99)`
   - After reset: add assertions for `baseUrl == "https://example.com"` and `syncInterval == 30` (the persisted values from setUp mock)

---

## Status: COMPLETE

All tasks implemented and reviewed successfully. All Linear issues moved to Merge.
