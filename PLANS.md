# Implementation Plan

**Status:** IN_PROGRESS
**Branch:** feat/HEA-90-sync-ux-improvements
**Issues:** HEA-90, HEA-91, HEA-92, HEA-93, HEA-94, HEA-95
**Created:** 2026-02-27
**Last Updated:** 2026-02-27

## Summary

Six improvements to the sync experience: fix the misleading sync interval slider (bug), check Health Connect permission on launch (bug), enrich sync result summaries, store and display when sync actually ran, show next scheduled sync time, and show the last 3 synced meals on the main screen.

## Issues

### HEA-90: Sync interval slider allows values below WorkManager 15-minute minimum

**Priority:** Medium
**Labels:** Bug
**Description:** The Settings screen slider allows 5–120 minutes, but WorkManager enforces a 15-minute minimum. Values under 15 are silently clamped by `SyncScheduler.MIN_INTERVAL_MINUTES`, misleading users into thinking sync runs every 5 minutes.

**Acceptance Criteria:**
- [ ] Slider range is 15–120 minutes with 5-minute increments
- [ ] Default sync interval is 15 (not 5) in SettingsUiState, PersistedSettings, and DataStoreSettingsRepository
- [ ] Existing users with interval < 15 stored in DataStore see 15 on the slider (clamped to range start)

### HEA-91: Health Connect permission status not checked on app launch

**Priority:** High
**Labels:** —
**Description:** `SyncUiState.permissionGranted` defaults to `false` and is only updated when the user taps the permission button. On every app launch, users see "Write Nutrition permission is required" and the Sync Now button is disabled — even when permission has already been granted.

**Acceptance Criteria:**
- [ ] Permission status checked in `SyncViewModel.init` via `HealthConnectClient.permissionController.getGrantedPermissions()`
- [ ] If already granted, Sync Now button is immediately enabled on launch
- [ ] If check fails (exception), falls back to current behavior (show permission prompt)
- [ ] If HC client is null, `permissionGranted` stays false

### HEA-92: Store and display last sync timestamp

**Priority:** Medium
**Labels:** Feature
**Description:** Currently shows "Last synced: 2026-02-27" (calendar date range), not when sync actually ran. Users can't tell if sync ran 5 minutes ago or 5 hours ago.

**Acceptance Criteria:**
- [ ] Actual sync completion timestamp (epoch millis) stored in DataStore
- [ ] Displayed as relative time: "Just now", "5 min ago", "2 hr ago", "3 days ago"
- [ ] Refreshes periodically (~30s) while screen is visible
- [ ] Persisted across app restarts
- [ ] Timestamp set for both manual sync (via SyncNutritionUseCase) and background sync (SyncWorker calls the same use case)

### HEA-93: Improve sync result summary line

**Priority:** Low
**Labels:** Improvement
**Description:** Sync result shows "Synced 12 records" with no context on how many days were covered. The day count is available in `SyncProgress` but discarded after sync completes.

**Acceptance Criteria:**
- [ ] Success format: "Synced X meals across Y days" (or "1 day" singular)
- [ ] Zero records format: "No new meals"
- [ ] Day count comes from `SyncResult.Success` (enriched model), not from transient progress

### HEA-94: Show next scheduled sync time

**Priority:** Low
**Labels:** Feature
**Description:** Users don't know when the next background sync will run. No visibility into the WorkManager schedule.

**Acceptance Criteria:**
- [ ] Shows "Next sync in ~Xm" or "Next sync at HH:MM" on main screen
- [ ] Shows "Auto-sync: off" when not configured or sync not scheduled
- [ ] Updates after sync completes or interval changes
- [ ] Works on API 28+ with fallback from `WorkInfo.nextScheduleTimeMillis`

### HEA-95: Show last 3 synced meals on main screen

**Priority:** Medium
**Labels:** Feature
**Description:** After sync, users see "Synced 12 records" with no detail about what was actually synced. Show the 3 most recent synced meals in a compact format, persisted across app restarts.

**Acceptance Criteria:**
- [ ] Compact format: "Chicken Salad · Lunch · 450 cal"
- [ ] Shows max 3 meals, most recent first
- [ ] Persisted in DataStore as JSON string
- [ ] Visible immediately on app launch (loaded from storage)
- [ ] Updated after each successful sync

## Prerequisites

- [ ] Build compiles: `./gradlew assembleDebug`
- [ ] Tests pass: `./gradlew test`
- [ ] On `main` branch with clean working tree

## Implementation Tasks

### Task 1: Fix sync interval slider minimum and defaults

**Issue:** HEA-90
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify — `SettingsUiState` and `PersistedSettings` defaults)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify — `DEFAULT_SYNC_INTERVAL`)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` (modify — slider `valueRange` and `steps`)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Update default interval test:
   - In `SettingsViewModelTest`, change the `default SettingsUiState has syncInterval of 5` test to assert `syncInterval == 15`
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SettingsViewModelTest"`
   - Verify: fails (default is still 5)

2. **GREEN** — Apply fixes:
   - Change `SettingsUiState.syncInterval` default from `5` to `15`
   - Change `SettingsViewModel.PersistedSettings.syncInterval` default from `5` to `15`
   - Change `DataStoreSettingsRepository.DEFAULT_SYNC_INTERVAL` from `5` to `15`
   - Change slider in `SettingsScreen.kt`: `valueRange = 15f..120f`, `steps = 20`
   - Run tests: verify passes

3. **REFACTOR** — Keep `SyncScheduler.MIN_INTERVAL_MINUTES` clamp as a defensive safety net. Do not remove it.

**Defensive Requirements:**
- Existing users with `syncInterval < 15` in DataStore will read a value < 15. The Compose Slider clamps displayed position to range start (15f). `SyncScheduler.MIN_INTERVAL_MINUTES` ensures WorkManager interval is never < 15 regardless.
- No new error paths introduced.

**Notes:**
- Slider `steps = 20` gives 22 total discrete values: 15, 20, 25, ..., 120 (5-min increments). Formula: `(120 - 15) / 5 + 1 = 22 values`, `steps = 22 - 2 = 20`.
- Reference: `SettingsScreen.kt:111-117` for current slider, `DataStoreSettingsRepository.kt:32` for default

---

### Task 2: Check Health Connect permission on app launch

**Issue:** HEA-91
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify — init block)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests for permission check on init:
   - Test: `permissionGranted is true on init when permission already granted` — mock `healthConnectClient.permissionController.getGrantedPermissions()` to return a set containing the write NutritionRecord permission, create ViewModel, assert `permissionGranted == true`
   - Test: `permissionGranted stays false on init when permission not granted` — mock `getGrantedPermissions()` to return empty set, assert `permissionGranted == false`
   - Test: `permissionGranted stays false when getGrantedPermissions throws` — mock to throw RuntimeException, assert `permissionGranted == false` (graceful fallback)
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SyncViewModelTest"`
   - Verify: new tests fail

2. **GREEN** — Add permission check in `SyncViewModel.init`:
   - After the existing `healthConnectAvailable` update, launch a new coroutine that:
     - Guards on `healthConnectClient != null`
     - Calls `healthConnectClient.permissionController.getGrantedPermissions()`
     - Checks if result contains `HealthPermission.getWritePermission(NutritionRecord::class)`
     - Updates `_uiState` with `permissionGranted = true` or `false`
     - Wraps in try-catch: on any exception, log with `Timber.e` and leave `permissionGranted = false`
   - Run tests: verify passes

3. **REFACTOR** — Consider extracting the permission constant to a companion object on `SyncViewModel` to share with `SyncScreen.kt:38` (currently defined as `NUTRITION_PERMISSION` private val in SyncScreen)

**Defensive Requirements:**
- `getGrantedPermissions()` is a suspend function that can throw if Health Connect service is unavailable or the app process is in a bad state. Must wrap in try-catch.
- If `healthConnectClient` is null, skip the check entirely — do not attempt any calls.
- The permission check must not block other init work (settings flows, sync scheduling). Run in a separate coroutine.

**Notes:**
- Existing `onPermissionResult()` method remains — still needed for the launcher callback when user manually grants/revokes permission.
- The HealthConnectClient mock in tests is already `mockk(relaxed = true)` — the `permissionController` mock needs explicit setup for `getGrantedPermissions()`.
- Reference: `SyncScreen.kt:38` for the permission constant, `SyncViewModel.kt:47-86` for existing init coroutines

---

### Task 3: Enrich sync result with day count

**Issue:** HEA-93
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncResult.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Update model and tests:
   - Add `daysProcessed: Int` parameter to `SyncResult.Success`
   - All existing tests referencing `SyncResult.Success(recordCount)` will fail to compile — update them to `SyncResult.Success(recordCount, expectedDays)`
   - Add use case test: `singleDaySync` asserts `result.daysProcessed == 1`
   - Add use case test: `multiDaySync` with 2 past days asserts `result.daysProcessed == 2`
   - Add ViewModel test: result formatted as "Synced X meals across Y days"
   - Add ViewModel test: "No new meals" when `recordsSynced == 0` and result is Success
   - Add ViewModel test: singular "1 day" when `daysProcessed == 1`
   - Run: `./gradlew test`
   - Verify: tests fail

2. **GREEN** — Implement:
   - Change `SyncResult.Success` from `data class Success(val recordsSynced: Int)` to `data class Success(val recordsSynced: Int, val daysProcessed: Int)`
   - In `SyncNutritionUseCase.invoke()`, pass `successfulDays` as `daysProcessed` in the return: `SyncResult.Success(totalRecordsSynced, successfulDays)`
   - In `SyncViewModel.triggerSync()`, update result message formatting:
     - `recordsSynced == 0` → "No new meals"
     - `daysProcessed == 1` → "Synced {recordsSynced} meals across 1 day"
     - `daysProcessed > 1` → "Synced {recordsSynced} meals across {daysProcessed} days"
   - Run tests: verify passes

3. **REFACTOR** — Verify all test files updated consistently for the new `SyncResult.Success` constructor

**Defensive Requirements:**
- `daysProcessed` is always >= 0. It represents days where the API returned successfully, not total days attempted.
- No new error paths — purely a data enrichment change.

**Notes:**
- The `successfulDays` variable already exists in `SyncNutritionUseCase.kt:44` — just needs to be passed to the return value.
- Reference: `SyncNutritionUseCase.kt:102-107` for current return, `SyncViewModel.kt:101-105` for current formatting

---

### Task 4: Store last sync timestamp in repository

**Issue:** HEA-92
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write repository tests:
   - Test: `lastSyncTimestampFlow emits 0L by default`
   - Test: `setLastSyncTimestamp stores value and emits it`
   - Run: `./gradlew test --tests "com.healthhelper.app.data.repository.DataStoreSettingsRepositoryTest"`
   - Verify: fails (property doesn't exist)

2. **GREEN** — Implement:
   - Add to `SettingsRepository` interface:
     - `val lastSyncTimestampFlow: Flow<Long>`
     - `suspend fun setLastSyncTimestamp(value: Long)`
   - Add to `DataStoreSettingsRepository`:
     - `LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")` in companion object
     - `lastSyncTimestampFlow = dataStore.data.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }`
     - `setLastSyncTimestamp` via `dataStore.edit { it[LAST_SYNC_TIMESTAMP] = value }`
   - Run tests: verify passes

3. **REFACTOR** — Update all mock setups across test files to provide a default for `lastSyncTimestampFlow`: `every { settingsRepository.lastSyncTimestampFlow } returns flowOf(0L)`

**Defensive Requirements:**
- `0L` as default clearly indicates "never synced" — the UI layer must treat 0L as "no timestamp available" and show nothing.
- DataStore handles corruption internally; `dataStore.data.map` won't throw.

**Notes:**
- Follow exact pattern of `syncIntervalFlow` at `DataStoreSettingsRepository.kt:84-85`
- Need `longPreferencesKey` import from `androidx.datastore.preferences.core`
- All test files that mock `SettingsRepository` must be updated: `SyncViewModelTest`, `SyncNutritionUseCaseTest`, `SettingsViewModelTest`, `DataStoreSettingsRepositoryTest`

---

### Task 5: Save sync timestamp and display relative time

**Issue:** HEA-92
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify — SyncUiState + init + helper)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - Use case test: `invoke sets lastSyncTimestamp on successful sync` — verify `settingsRepository.setLastSyncTimestamp()` is called with a value > 0
   - Use case test: `invoke does not set lastSyncTimestamp when all days fail` — verify `setLastSyncTimestamp` is NOT called when result is Error (all days failed)
   - ViewModel test: `lastSyncTime formats recent timestamp as relative time` — mock `lastSyncTimestampFlow` to emit `System.currentTimeMillis() - 300_000` (5 min ago), assert `lastSyncTime` contains "min ago"
   - ViewModel test: `lastSyncTime is empty when timestamp is 0` — assert empty string when no sync has occurred
   - Run: `./gradlew test`
   - Verify: fails

2. **GREEN** — Implement:
   - In `SyncNutritionUseCase.invoke()`, after the sync loop and before the return statement, call `settingsRepository.setLastSyncTimestamp(System.currentTimeMillis())` when `successfulDays > 0`
   - Add `lastSyncTime: String = ""` to `SyncUiState`
   - Create a private helper function `formatRelativeTime(timestampMillis: Long): String` in the SyncViewModel file:
     - `0L` → `""`
     - `< 60 seconds ago` → `"Just now"`
     - `< 60 minutes` → `"X min ago"`
     - `< 24 hours` → `"X hr ago"`
     - `< 7 days` → `"X days ago"`
     - `>= 7 days` → date string (e.g., "Feb 20")
   - In `SyncViewModel.init`, collect `settingsRepository.lastSyncTimestampFlow` and format to relative time string in `_uiState.lastSyncTime`
   - Add periodic refresh: launch a coroutine with `while(isActive) { delay(30_000); re-format the timestamp }` to keep "5 min ago" fresh
   - In `SyncScreen`, display `uiState.lastSyncTime` when non-empty. This replaces or supplements the existing "Last synced: {lastSyncedDate}" display
   - Run tests: verify passes

3. **REFACTOR** — Ensure the periodic refresh coroutine is in `viewModelScope` for automatic cancellation

**Defensive Requirements:**
- `System.currentTimeMillis()` in the use case makes deterministic testing harder. Use `coVerify { setLastSyncTimestamp(match { it > 0 }) }` for assertions.
- The periodic refresh coroutine must be in `viewModelScope` to auto-cancel when ViewModel is cleared.
- Handle edge cases in `formatRelativeTime`: timestamp `0L` (never synced), future timestamps (clock skew — treat as "Just now"), very old timestamps (show date).
- Wrap `setLastSyncTimestamp` in try-catch in the use case — do not let timestamp storage failure break the sync flow. Log with Timber on failure.

**Notes:**
- `formatRelativeTime` uses pure Kotlin (`System.currentTimeMillis()` and arithmetic) — no Android `DateUtils` dependency, so it's testable in JUnit.
- The periodic refresh ensures "5 min ago" becomes "6 min ago" etc. while screen is visible.
- Reference: `SyncViewModel.kt:47-69` for existing init flow collection pattern
- Reference: `SyncNutritionUseCase.kt:98-100` for where to add the timestamp save (after contiguous date logic)

---

### Task 6: Create SyncedMealSummary model and repository storage

**Issue:** HEA-95
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncedMealSummary.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/SyncedMealSummaryTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write model and repository tests:
   - Model test: `SyncedMealSummary holds foodName, mealType, and calories`
   - Model test: `SyncedMealSummary requires non-negative calories` — verify `IllegalArgumentException` on negative calories
   - Model test: `SyncedMealSummary requires non-blank foodName` — verify `IllegalArgumentException` on blank name
   - Repository test: `lastSyncedMealsFlow emits empty list by default`
   - Repository test: `setLastSyncedMeals stores meals and emits them`
   - Repository test: `setLastSyncedMeals with empty list clears stored meals`
   - Run: `./gradlew test`
   - Verify: fails

2. **GREEN** — Implement:
   - Create `SyncedMealSummary` data class in `domain/model/`:
     - `foodName: String` — name of the food item
     - `mealType: MealType` — reuses existing `MealType` enum
     - `calories: Int` — rounded from `FoodLogEntry.calories`
     - `init` block: `require(calories >= 0)`, `require(foodName.isNotBlank())`
   - Add to `SettingsRepository` interface:
     - `val lastSyncedMealsFlow: Flow<List<SyncedMealSummary>>`
     - `suspend fun setLastSyncedMeals(meals: List<SyncedMealSummary>)`
   - In `DataStoreSettingsRepository`:
     - Add `LAST_SYNCED_MEALS = stringPreferencesKey("last_synced_meals")` to companion object
     - Create an internal `@Serializable` DTO data class (e.g., `SyncedMealDto`) with `foodName: String`, `mealType: String`, `calories: Int` for JSON serialization
     - Implement `setLastSyncedMeals`: map domain models to DTOs, serialize to JSON via `Json.encodeToString()`, store in DataStore
     - Implement `lastSyncedMealsFlow`: read from DataStore, deserialize JSON, map DTOs back to domain models. On parse failure, return empty list (try-catch around deserialization)
   - Run tests: verify passes

3. **REFACTOR** — Ensure JSON deserialization handles corrupt/empty data gracefully with try-catch returning `emptyList()`

**Defensive Requirements:**
- JSON deserialization must not throw on corrupt data. Wrap in try-catch and return empty list.
- `SyncedMealSummary.calories` must be non-negative (validated in init block).
- `SyncedMealSummary.foodName` must not be blank.
- Empty string in DataStore → empty list (no meals stored yet).

**Notes:**
- `kotlinx-serialization-json` is already in `libs.versions.toml:55` and the `kotlin-serialization` plugin is declared at `libs.versions.toml:68`
- The `@Serializable` DTO stays in the data layer (not the domain model), following the pattern in `data/api/dto/FoodLogResponse.kt`
- `MealType` is serialized as its `name` string in the DTO, deserialized via `MealType.valueOf()`
- Reference: `FoodLogEntry.kt` for domain model validation pattern, `FoodLogResponse.kt` for `@Serializable` DTO pattern
- All test files mocking `SettingsRepository` must add: `every { settingsRepository.lastSyncedMealsFlow } returns flowOf(emptyList())`

---

### Task 7: Collect and persist last 3 meals during sync

**Issue:** HEA-95
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write use case tests for meal collection:
   - Test: `invoke persists last 3 meals sorted by date descending then time descending` — sync 2 days with 2 entries each, verify `setLastSyncedMeals` called with the 3 most recent entries
   - Test: `invoke persists fewer than 3 meals when fewer were synced` — sync with 1 entry total, verify list has 1 item
   - Test: `invoke sets empty meals list when no records synced` — sync with 0 entries, verify `setLastSyncedMeals(emptyList())` called
   - Test: `invoke maps FoodLogEntry to SyncedMealSummary correctly` — verify foodName, mealType, and `calories.toInt()` rounding
   - Test: `invoke handles null time in FoodLogEntry for meal sorting` — entries without time sort after entries with time on the same date
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SyncNutritionUseCaseTest"`
   - Verify: fails

2. **GREEN** — Implement:
   - In `SyncNutritionUseCase.invoke()`, declare a list to accumulate synced entries with their date: `val syncedEntries = mutableListOf<Pair<String, FoodLogEntry>>()`
   - Inside the sync loop, when `result.isSuccess && entries.isNotEmpty()`, add each entry paired with `dateStr`
   - After the sync loop (before the return), sort `syncedEntries` by date descending then time descending, take first 3
   - Map to `SyncedMealSummary(entry.foodName, entry.mealType, entry.calories.toInt())`
   - Call `settingsRepository.setLastSyncedMeals(summaries)`
   - Run tests: verify passes

3. **REFACTOR** — Consider optimizing: since dates are processed newest-first, stop accumulating after collecting 3 entries to avoid holding all entries in memory for large syncs

**Defensive Requirements:**
- `FoodLogEntry.time` is nullable — entries without time should sort after entries with time for the same date
- `FoodLogEntry.calories` is Double — round to Int with `toInt()` (truncation is acceptable for display)
- Wrap `setLastSyncedMeals` in try-catch — do not let meal persistence failure break the sync flow. Log error with Timber.

**Notes:**
- The sync loop processes dates newest-first (today, then backwards), so the first day's entries are likely the most recent meals
- Reference: `SyncNutritionUseCase.kt:49-86` for the sync loop where entries are available
- `FoodLogEntry` fields needed: `foodName`, `mealType`, `calories`, `time`

---

### Task 8: Display synced meals on main screen

**Issue:** HEA-95
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify — SyncUiState)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write ViewModel tests:
   - Test: `lastSyncedMeals populated from repository flow` — mock `lastSyncedMealsFlow` to emit 3 meals, assert `SyncUiState.lastSyncedMeals` contains them
   - Test: `lastSyncedMeals is empty list by default` — assert empty when no meals stored
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SyncViewModelTest"`
   - Verify: fails

2. **GREEN** — Implement:
   - Add `lastSyncedMeals: List<SyncedMealSummary> = emptyList()` to `SyncUiState`
   - In `SyncViewModel.init`, collect `settingsRepository.lastSyncedMealsFlow` and update `_uiState` with the meals list
   - In `SyncScreen`, add a "Recent syncs" section below the sync result area:
     - Only display when `lastSyncedMeals` is non-empty
     - Show header text: "Recent syncs:"
     - For each meal, display: `"{foodName} · {MealType display name} · {calories} cal"`
     - Format `MealType` for display: `name.lowercase().replaceFirstChar { it.uppercase() }` (e.g., "Breakfast", "Lunch")
     - Use `Text` composables with `bodySmall` or `bodyMedium` typography
   - Run tests: verify passes

3. **REFACTOR** — Ensure the UI section is visually compact (no cards, no excessive padding)

**Defensive Requirements:**
- Empty list → section not displayed at all (no empty "Recent syncs:" header)
- Long food names handled by Compose text overflow (single-line with ellipsis via `maxLines = 1` and `overflow = TextOverflow.Ellipsis`)

**Notes:**
- The meals flow can be collected separately from the existing settings `combine` in `SyncViewModel.init`, or added to it. A separate collector is simpler and avoids changing the existing combine signature.
- Reference: `SyncScreen.kt:107-113` for conditional display pattern
- Reference: `SyncScreen.kt:84-101` for existing content layout structure

---

### Task 9: Show next scheduled sync time

**Issue:** HEA-94
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncScheduler.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify — SyncUiState + init)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncSchedulerTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - SyncScheduler test: `getNextSyncTimeFlow emits timestamp from WorkInfo` — mock WorkManager to return WorkInfo with a future `nextScheduleTimeMillis`
   - SyncScheduler test: `getNextSyncTimeFlow emits null when no work is scheduled` — mock WorkManager to return empty list
   - SyncScheduler test: `getNextSyncTimeFlow emits null when nextScheduleTimeMillis is MAX_VALUE` — treat unknown as null
   - ViewModel test: `nextSyncTime shows formatted time when sync is scheduled` — mock SyncScheduler flow to emit a future timestamp
   - ViewModel test: `nextSyncTime shows empty string when not configured` — assert empty/hidden
   - ViewModel test: `nextSyncTime shows fallback estimate when SyncScheduler returns null` — compute from `lastSyncTimestamp + interval * 60_000`
   - Run: `./gradlew test`
   - Verify: fails

2. **GREEN** — Implement:
   - In `SyncScheduler`, add `fun getNextSyncTimeFlow(): Flow<Long?>`:
     - Use `workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)` → `Flow<List<WorkInfo>>`
     - Map: extract `firstOrNull()?.nextScheduleTimeMillis`, return null if list is empty or value is `Long.MAX_VALUE`
   - Add `nextSyncTime: String = ""` to `SyncUiState`
   - In `SyncViewModel.init`, combine `syncScheduler.getNextSyncTimeFlow()`, `settingsRepository.lastSyncTimestampFlow`, `settingsRepository.syncIntervalFlow`, and `isConfigured` status to compute `nextSyncTime`:
     - Not configured → `""`
     - WorkManager provides valid future timestamp → format as "Next sync at HH:MM" (for > 1 hour) or "Next sync in ~Xm" (for < 1 hour)
     - WorkManager returns null → estimate from `lastSyncTimestamp + interval * 60_000`:
       - If estimate is in the future → format same as above
       - If estimate is in the past or lastSyncTimestamp is 0 → "Sync pending..."
     - If sync not scheduled at all and not configured → `""` (hidden)
   - In `SyncScreen`, display `nextSyncTime` when non-empty, positioned near the sync status area
   - Run tests: verify passes

3. **REFACTOR** — Consider extracting time formatting helpers shared between Task 5 (relative time) and this task

**Defensive Requirements:**
- `getWorkInfosForUniqueWorkFlow()` can emit empty list if work was never scheduled — handle as null.
- `WorkInfo.nextScheduleTimeMillis` returns `Long.MAX_VALUE` when next run time is unknown — treat as unavailable, use fallback.
- WorkManager Flow collection should not crash if WorkManager is not properly initialized — wrap in try-catch or use `catch` operator on the Flow.
- The fallback calculation is approximate — WorkManager has flex time and system-imposed delays. The UI should indicate approximation with "~".

**Notes:**
- `WorkManager.getWorkInfosForUniqueWorkFlow()` is available in WorkManager 2.7+ (project uses 2.10.1 at `libs.versions.toml:21`)
- `WorkInfo.getNextScheduleTimeMillis()` is available in WorkManager 2.8+
- For time formatting: use `java.time.Instant`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter` for "HH:MM" format
- Reference: `SyncScheduler.kt:15` for `WORK_NAME` constant

---

### Task 10: Integration & Verification

**Issue:** HEA-90, HEA-91, HEA-92, HEA-93, HEA-94, HEA-95
**Files:** All modified files from Tasks 1–9

**Steps:**

1. Run full test suite: `./gradlew test`
2. Build check: `./gradlew assembleDebug`
3. Manual verification on device/emulator:
   - [ ] Settings slider starts at 15, increments by 5, max 120
   - [ ] App launch with previously granted permission → Sync Now button enabled immediately
   - [ ] After sync, result shows "Synced X meals across Y days"
   - [ ] After sync, "Last synced: X min ago" appears and refreshes over time
   - [ ] After sync, last 3 meals displayed compactly below results
   - [ ] Next sync time displayed and updates after sync or interval change
   - [ ] App restart preserves last synced meals, timestamp, and next sync info
   - [ ] All info hidden/defaults when app is freshly installed (no data stored)

## MCP Usage During Implementation

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `save_issue` | Move issues to "In Progress" when starting, "Done" when complete |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| `getGrantedPermissions()` throws | `permissionGranted` stays false, Timber.e logged | Unit test (Task 2) |
| JSON deserialization of stored meals fails | Return empty list, no crash | Unit test (Task 6) |
| WorkManager `getWorkInfosForUniqueWorkFlow` returns empty | `nextSyncTime` shows fallback estimate or empty | Unit test (Task 9) |
| `setLastSyncTimestamp` throws during sync | Sync succeeds, timestamp not persisted, error logged | Unit test (Task 5) |
| `setLastSyncedMeals` throws during sync | Sync succeeds, meals not persisted, error logged | Unit test (Task 7) |
| Stored `syncInterval` is < 15 (legacy data) | Slider clamps to 15, `SyncScheduler` clamps to 15 | Defensive (Task 1) |

## Risks & Open Questions

- [ ] `WorkInfo.nextScheduleTimeMillis` accuracy on API 28–30 may vary. The fallback estimate (`lastSyncTimestamp + interval`) is approximate. Acceptable for a "next sync" indicator.
- [ ] kotlinx-serialization-json is already a direct dependency (`libs.versions.toml:55`). Verify the `kotlin-serialization` plugin is applied in `app/build.gradle.kts` — the DTOs in `data/api/dto/` compile with `@Serializable`, so it should be.
- [ ] Periodic refresh of relative time (Task 5) adds a long-lived coroutine in `viewModelScope`. This is fine — it auto-cancels when ViewModel is cleared.

## Scope Boundaries

**In Scope:**
- Fix slider range and defaults (HEA-90)
- Check permission on init (HEA-91)
- Enrich sync result summary with day count (HEA-93)
- Store and display last sync timestamp with relative formatting (HEA-92)
- Show next scheduled sync time with fallback estimation (HEA-94)
- Store and display last 3 synced meals (HEA-95)

**Out of Scope:**
- Full meal history or browsing all synced data
- Push notifications for sync completion
- Detailed sync error diagnostics
- Sync conflict resolution UI
- Room database for meal storage (DataStore JSON is sufficient for 3 items)
- Removing `SyncScheduler.MIN_INTERVAL_MINUTES` clamp (kept as safety net)

---

## Iteration 1

**Implemented:** 2026-02-27
**Method:** Agent team (3 workers, worktree-isolated)

### Tasks Completed This Iteration
- Task 1: Fix sync interval slider minimum and defaults (worker-1)
- Task 2: Check Health Connect permission on app launch (worker-1)
- Task 3: Enrich sync result with day count (worker-2)
- Task 4: Store last sync timestamp in repository (worker-2)
- Task 5: Save sync timestamp and display relative time (worker-2)
- Task 6: Create SyncedMealSummary model and repository storage (worker-3)
- Task 7: Collect and persist last 3 meals during sync (worker-3)
- Task 8: Display synced meals on main screen (worker-3)
- Task 9: Show next scheduled sync time (worker-1)
- Task 10: Integration & Verification (lead)

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncResult.kt` — Added `daysProcessed` to `Success`
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncedMealSummary.kt` — NEW: domain model for meal summaries
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — Added `lastSyncTimestampFlow`, `lastSyncedMealsFlow`, `setLastSyncTimestamp`, `setLastSyncedMeals`
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — Implemented new repo methods, changed DEFAULT_SYNC_INTERVAL to 15, added SyncedMealDto
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` — Save timestamp, collect meals after sync, safe calorie rounding
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` — Permission check on init, timestamp display with periodic refresh, meals collection, next sync time, sanitized error messages
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` — Changed default syncInterval to 15
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` — Added lastSyncTime, recent syncs section, next sync time display
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` — Slider range 15–120, steps=20
- `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncScheduler.kt` — Added `getNextSyncTimeFlow()`
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` — 28 tests, viewModelTest wrapper for periodic refresh coroutine
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` — Tests for timestamp, meals, day count
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` — Tests for timestamp and meals storage
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncSchedulerTest.kt` — Tests for getNextSyncTimeFlow
- `app/src/test/kotlin/com/healthhelper/app/domain/model/SyncedMealSummaryTest.kt` — NEW: model validation tests
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` — Updated default interval assertion
- `.gitignore` — Added `_workers/`

### Linear Updates
- HEA-90: Todo → In Progress → Review
- HEA-91: Todo → In Progress → Review
- HEA-92: Todo → In Progress → Review
- HEA-93: Todo → In Progress → Review
- HEA-94: Todo → In Progress → Review
- HEA-95: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 5 bugs (1 HIGH, 4 MEDIUM), all fixed before commit:
  1. Meal summaries included entries whose HC write failed — moved `syncedEntries.add()` inside `if (written)`
  2. `formatNextSyncTime` displayed negative minutes when sync overdue — added `diffMs <= 0` guard
  3. `calories.toInt()` overflow risk — changed to `roundToInt().coerceAtLeast(0)`
  4. Single bad `foodName` in JSON dropped all stored meals — changed to `mapNotNull` with per-item validation
  5. Raw error message leaked to UI — sanitized to generic message, raw logged via Timber
- verifier: All tests pass, lint passed, build passed, zero warnings
- Post-bug-fix: SyncViewModelTest hang fixed (viewModelTest wrapper cancels viewModelScope before runTest cleanup)

### Work Partition
- Worker 1: Tasks 1, 2, 9 (Settings fix + Permission check + Next sync time) — 7 points
- Worker 2: Tasks 3, 4, 5 (Sync result enrichment + Timestamp) — 8 points
- Worker 3: Tasks 6, 7, 8 (Meals model + collection + display) — 8 points

### Merge Summary
- Worker 2: fast-forward (first merge, no conflicts)
- Worker 3: merged, 6 conflicts in SettingsRepository.kt, DataStoreSettingsRepository.kt, SyncNutritionUseCase.kt, SyncViewModel.kt, SyncNutritionUseCaseTest.kt, SyncViewModelTest.kt (all additive — both workers adding different features)
- Worker 1: merged, 3 conflicts in DataStoreSettingsRepository.kt, SyncViewModel.kt, SyncViewModelTest.kt (additive)
- Build gate passed after each merge

### Continuation Status
All tasks completed.
