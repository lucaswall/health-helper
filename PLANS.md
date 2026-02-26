# Implementation Plan

**Status:** COMPLETE
**Branch:** feat/HEA-1-backlog-sweep
**Issues:** HEA-1, HEA-2, HEA-3, HEA-4, HEA-5, HEA-6, HEA-7, HEA-8, HEA-9, HEA-10, HEA-11, HEA-12, HEA-13, HEA-14
**Created:** 2026-02-26
**Last Updated:** 2026-02-26

## Summary

Comprehensive backlog sweep addressing all 14 Backlog issues. This plan transforms the app from a non-functional state (crashes without Health Connect, never requests permissions, swallows all errors) into a robust foundation with proper error handling, permission management, logging, and clean architecture compliance.

Key themes:
- **Critical fixes**: Health Connect availability check (HEA-2), permission request flow (HEA-1), error propagation (HEA-3)
- **Architecture**: Move repo interface to domain (HEA-8), type-safe domain model (HEA-11), stable IDs (HEA-10)
- **Observability**: Timber logging across all layers (HEA-14)
- **UI**: Permission/availability states (HEA-4), retry + guidance (HEA-5), lifecycle-aware collection (HEA-7)
- **Cleanup**: Remove unused permission (HEA-6), disable backup (HEA-9), remove unused dependency (HEA-13), fix tests (HEA-12)

## Issues

### HEA-1: App never requests Health Connect permissions

**Priority:** Urgent
**Labels:** Bug
**Description:** The app declares Health Connect permissions in the manifest but never requests them at runtime. `readRecords()` throws `SecurityException`, the repository swallows it, and the user sees "No step records found" with no way to grant permissions. The app is functionally broken.

**Acceptance Criteria:**
- [ ] Use `PermissionController.createRequestPermissionResultContract()` to request Health Connect permissions
- [ ] Check permission status before attempting to read data
- [ ] Show a clear permission rationale screen when permissions not granted
- [ ] Handle permission denial gracefully (show explanation, link to Settings)
- [ ] Re-check permissions on app resume (user may grant in Settings)

### HEA-2: App crashes if Health Connect is not installed

**Priority:** Urgent
**Labels:** Bug
**Description:** `HealthConnectClient.getOrCreate(context)` is called unconditionally in `AppModule.kt:22-23`. If Health Connect is not installed or unavailable, this throws during DI, crashing on launch.

**Acceptance Criteria:**
- [ ] Check `HealthConnectClient.getSdkStatus(context)` before creating the client
- [ ] Handle `SDK_UNAVAILABLE` — show message directing user to install Health Connect
- [ ] Handle `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` — prompt user to update
- [ ] Make `HealthConnectClient` nullable or wrap in a provider that handles unavailability
- [ ] App does not crash when Health Connect is absent

### HEA-3: Repository silently swallows all exceptions as empty list

**Priority:** High
**Labels:** Bug
**Description:** `HealthConnectRepositoryImpl.readSteps()` catches all exceptions and returns `emptyList()`. Impossible to distinguish "no data" from "error" (permission denied, service unavailable, IO failure). The ViewModel's catch block at line 41 is dead code.

**Acceptance Criteria:**
- [ ] Repository propagates exceptions instead of swallowing them (remove catch-all)
- [ ] ViewModel catches exceptions and maps to meaningful error states
- [ ] ViewModel error test added: when use case throws, uiState has errorMessage and isLoading=false
- [ ] ViewModel retry test added: loadSteps() can be called again after error

### HEA-4: HealthUiState has no permission or availability states

**Priority:** High
**Labels:** Bug
**Description:** `HealthUiState` only models `isLoading`, `records`, and `errorMessage`. No representation for Health Connect availability or permission state, making it impossible to show appropriate UI.

**Acceptance Criteria:**
- [ ] UiState models Health Connect availability (Available, NotInstalled, NeedsUpdate)
- [ ] UiState models permission status (NotRequested, Denied, Granted)
- [ ] UI renders distinct screens/messages for each state with appropriate CTAs
- [ ] ViewModel checks availability and permission status on init and on resume

### HEA-5: Error and empty states have no actionable guidance or retry

**Priority:** High
**Labels:** Improvement
**Description:** Error state shows static "Failed to load steps" with no retry. Empty state shows "No step records found" with no guidance. Users cannot recover from errors.

**Acceptance Criteria:**
- [ ] Error state includes a Retry button that calls `viewModel.loadSteps()`
- [ ] Empty state includes guidance (e.g., "Make sure Health Connect is tracking your steps")
- [ ] Error messages are specific when possible
- [ ] Pull-to-refresh as alternative refresh mechanism

### HEA-6: Manifest declares unused READ_HEART_RATE permission

**Priority:** Medium
**Labels:** Convention
**Description:** `AndroidManifest.xml:6` declares `READ_HEART_RATE` but no heart rate reading code exists. Over-requesting permissions reduces user trust.

**Acceptance Criteria:**
- [ ] Remove `READ_HEART_RATE` permission from manifest
- [ ] Only declare permissions actively used by the app

### HEA-7: Use collectAsStateWithLifecycle instead of collectAsState

**Priority:** Medium
**Labels:** Bug
**Description:** `HealthScreen.kt:38` uses `collectAsState()` which continues collecting when backgrounded. Should use `collectAsStateWithLifecycle()`.

**Acceptance Criteria:**
- [ ] Add `androidx.lifecycle:lifecycle-runtime-compose` dependency
- [ ] Replace `collectAsState()` with `collectAsStateWithLifecycle()` in HealthScreen
- [ ] Import from `androidx.lifecycle.compose.collectAsStateWithLifecycle`

### HEA-8: Repository interface lives in data layer, violating clean architecture

**Priority:** Medium
**Labels:** Convention
**Description:** `HealthConnectRepository` interface is in `data/repository/` alongside its implementation. `ReadStepsUseCase` imports from `data.repository`, violating the clean architecture dependency rule.

**Acceptance Criteria:**
- [ ] Move `HealthConnectRepository` interface to `domain/repository/` package
- [ ] Keep `HealthConnectRepositoryImpl` in `data/repository/`
- [ ] Update imports in `ReadStepsUseCase` and `AppModule`
- [ ] Domain layer should have zero imports from `data` package

### HEA-9: android:allowBackup="true" exposes sensitive health data

**Priority:** Medium
**Labels:** Security
**Description:** `AndroidManifest.xml:10` has `android:allowBackup="true"`. Health data could be exposed through cloud backups or ADB extraction.

**Acceptance Criteria:**
- [ ] Set `android:allowBackup="false"`
- [ ] Add `android:dataExtractionRules` for API 31+ and `android:fullBackupContent` for pre-31 to explicitly exclude all data

### HEA-10: LazyColumn items() missing key parameter

**Priority:** Low
**Labels:** Performance
**Description:** `HealthScreen.kt:94` — `items(uiState.records)` without `key`. `HealthRecord` has no stable ID, so Compose recomposes the entire list on any change.

**Acceptance Criteria:**
- [ ] Add a stable `id` field to `HealthRecord` (map from Health Connect `Metadata.id`)
- [ ] Provide `key = { it.id }` to `items()` in LazyColumn

### HEA-11: HealthRecord.type is an untyped String

**Priority:** Medium
**Labels:** Improvement
**Description:** `HealthRecord.type` is `String` (e.g., "Steps"). No compile-time safety — typos compile, `when` lacks exhaustiveness checks.

**Acceptance Criteria:**
- [ ] Replace `type: String` with an enum `HealthRecordType` (e.g., `Steps`, `HeartRate`)
- [ ] Update repository mapping to use the enum
- [ ] Update UI to handle the enum (when expressions get exhaustiveness checking)

### HEA-12: Tests use runBlocking instead of runTest, missing error path coverage

**Priority:** Low
**Labels:** Convention
**Description:** `ReadStepsUseCaseTest` uses `runBlocking` instead of `runTest`. `HealthViewModelTest` has no test for the exception case.

**Acceptance Criteria:**
- [ ] Replace `runBlocking` with `runTest` in ReadStepsUseCaseTest
- [ ] Add ViewModel test: when use case throws, uiState has errorMessage and isLoading=false
- [ ] Add ViewModel test: loadSteps() can be called again after error (retry works)

### HEA-13: Unused navigation-compose dependency

**Priority:** Low
**Labels:** Technical Debt
**Description:** `navigation-compose` is declared but no NavHost, NavController, or navigation graph exists. Single screen loaded directly in `setContent`.

**Acceptance Criteria:**
- [ ] Remove navigation-compose from `libs.versions.toml` and `build.gradle.kts`
- [ ] Remove hilt-navigation-compose if solely pulled in for navigation

### HEA-14: Add Timber logging framework and instrument all layers

**Priority:** High
**Labels:** Improvement
**Description:** Zero logging anywhere. Exceptions swallowed silently, Health Connect operations produce no output, nothing visible in `adb logcat`.

**Acceptance Criteria:**
- [ ] Add Timber 5.0.1 dependency to `libs.versions.toml` and `build.gradle.kts`
- [ ] Plant `Timber.DebugTree()` in `HealthHelperApp.onCreate()` for debug builds
- [ ] Log repository operations: start, success with record count, failure with exception
- [ ] Log ViewModel state transitions: loading, success, error
- [ ] Log DI: Health Connect client creation and SDK status
- [ ] No sensitive health data values logged (counts and types only, not raw values)

## Prerequisites

Before starting implementation:
- [ ] Add Timber 5.0.1 to `gradle/libs.versions.toml` (version + library declaration)
- [ ] Add `lifecycle-runtime-compose` to `gradle/libs.versions.toml` (version + library declaration)
- [ ] Add both dependencies to `app/build.gradle.kts`
- [ ] Verify build compiles: `./gradlew assembleDebug`
- [ ] Verify existing tests pass: `./gradlew test`

## Implementation Tasks

### Task 1: Move repository interface to domain layer (HEA-8)

**Issue:** HEA-8
**Dependencies:** None (foundational, do first)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/HealthConnectRepository.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepository.kt` (modify — remove interface, keep impl only)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` (rename from above after extracting interface)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCase.kt` (modify import)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify import)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` (modify import)

**TDD Steps:**

1. **RED** — Update `ReadStepsUseCaseTest` to import interface from `domain.repository` instead of `data.repository`. Test fails because the interface doesn't exist in the domain package yet.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.ReadStepsUseCaseTest"`
   - Expect: Compilation error — unresolved reference

2. **GREEN** — Create `domain/repository/HealthConnectRepository.kt` with the interface (same signature, new package). Move the interface out of the data layer file, leaving only `HealthConnectRepositoryImpl` in `data/repository/`. Update all imports: `ReadStepsUseCase`, `AppModule`, `HealthConnectRepositoryImpl`.
   - Run: `./gradlew test`
   - Expect: All tests pass

3. **REFACTOR** — Rename the data layer file from `HealthConnectRepository.kt` to `HealthConnectRepositoryImpl.kt` since it now only contains the implementation class. Verify `ReadStepsUseCase` has zero imports from `com.healthhelper.app.data`.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All tests pass, build succeeds

**Notes:**
- The domain interface should only depend on domain types (`HealthRecord`, `Instant`)
- Follow existing package naming: `com.healthhelper.app.domain.repository`
- `HealthConnectRepositoryImpl` stays in `data/repository/` and imports the domain interface

---

### Task 2: Replace HealthRecord.type with enum (HEA-11)

**Issue:** HEA-11
**Dependencies:** Task 1 (repo interface is now in domain)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecordType.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecord.kt` (modify — change `type: String` to `type: HealthRecordType`)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` (modify — use enum)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` (modify — use enum in test data)

**TDD Steps:**

1. **RED** — Update `ReadStepsUseCaseTest` to construct `HealthRecord` with `HealthRecordType.Steps` instead of `"Steps"` string. Test fails because enum doesn't exist.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.ReadStepsUseCaseTest"`
   - Expect: Compilation error

2. **GREEN** — Create `HealthRecordType` enum in `domain/model/` with `Steps` entry. Change `HealthRecord.type` from `String` to `HealthRecordType`. Update `HealthConnectRepositoryImpl` to use `HealthRecordType.Steps`.
   - Run: `./gradlew test`
   - Expect: All tests pass

3. **REFACTOR** — Verify no string-based type comparisons remain in the codebase. The UI currently doesn't branch on type (it only shows steps), so no UI changes needed yet.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds with no warnings

**Notes:**
- Enum should live in `domain/model/` alongside `HealthRecord`
- Start with just `Steps` variant — `HeartRate` can be added when heart rate reading is implemented
- The enum enables `when` exhaustiveness checking for future record type handling

---

### Task 3: Add stable ID to HealthRecord (HEA-10)

**Issue:** HEA-10
**Dependencies:** Task 2 (HealthRecord model already modified)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecord.kt` (modify — add `id: String`)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` (modify — map `record.metadata.id`)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` (modify — add `key` to `items()`)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` (modify — add id to test data)

**TDD Steps:**

1. **RED** — Update test records in `ReadStepsUseCaseTest` to include an `id` field. Test fails because `HealthRecord` doesn't have `id` parameter.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.ReadStepsUseCaseTest"`
   - Expect: Compilation error

2. **GREEN** — Add `val id: String` to `HealthRecord` data class. Map `record.metadata.id` in `HealthConnectRepositoryImpl`. Add `key = { it.id }` to `items()` call in `HealthScreen.kt:94`.
   - Run: `./gradlew test`
   - Expect: All tests pass

3. **REFACTOR** — Ensure `id` is the first parameter in `HealthRecord` (conventional for entity IDs).
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

**Notes:**
- Health Connect's `record.metadata.id` returns a stable string ID for each record
- The `id` field makes `HealthRecord` a proper entity with identity
- LazyColumn will now use this for stable item recomposition

---

### Task 4: Modernize test patterns (HEA-12 partial)

**Issue:** HEA-12
**Dependencies:** Tasks 1-3 (model changes complete, tests already updated for new model)
**Files:**
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` (modify — runBlocking → runTest)

**TDD Steps:**

1. **RED** — Replace all `runBlocking` calls with `runTest` in `ReadStepsUseCaseTest`. The tests should still pass since `ReadStepsUseCase` is a simple suspend function, but verify the import is correct (`kotlinx.coroutines.test.runTest`).
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.ReadStepsUseCaseTest"`
   - Expect: All tests pass (this is a non-behavioral change)

2. **GREEN** — N/A (no behavior change, just test infrastructure modernization)

3. **REFACTOR** — Verify consistency: both test files now use `runTest`.
   - Run: `./gradlew test`
   - Expect: All tests pass

**Notes:**
- `runTest` is already used in `HealthViewModelTest` — follow the same pattern
- The ViewModel error path tests (other part of HEA-12 acceptance criteria) will be added in Task 5 as part of HEA-3's TDD cycle
- `runTest` provides virtual time control and proper coroutine scheduling, even though it's not strictly needed for these simple suspend functions

---

### Task 5: Repository error propagation (HEA-3 + HEA-12 completion)

**Issue:** HEA-3, HEA-12
**Dependencies:** Task 4 (test patterns modernized)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` (modify — remove catch-all)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` (modify — improve error handling in catch block)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt` (modify — add error path and retry tests)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` (modify — add test for exception propagation)

**TDD Steps:**

1. **RED** — Write ViewModel error test: mock `ReadStepsUseCase` to throw `RuntimeException("Service unavailable")`. Assert `uiState.errorMessage` is non-null and `isLoading` is false. Test fails because current ViewModel catch block is dead code (repo swallows everything) — but structurally this test should still work since the mock throws directly.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Test passes (mock bypasses repository, throws directly into ViewModel). This validates the ViewModel's existing catch block works.

2. **RED** — Write ViewModel retry test: after error state, call `loadSteps()` again with a successful mock response. Assert state transitions back to success with records.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Test passes (retry logic already exists via public `loadSteps()`)

3. **RED** — Write UseCase exception propagation test: mock repository to throw `SecurityException`. Assert the use case propagates it (does not catch).
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.ReadStepsUseCaseTest"`
   - Expect: Test fails — current repository mock doesn't throw (it returns emptyList), but this test mocks the repo to throw. The use case should propagate it.

4. **GREEN** — Remove the `catch (e: Exception) { emptyList() }` block from `HealthConnectRepositoryImpl.readSteps()`. Let exceptions propagate through the use case to the ViewModel. The ViewModel's existing catch block will now actually handle real exceptions.
   - Run: `./gradlew test`
   - Expect: All tests pass

5. **REFACTOR** — Improve the ViewModel's catch block to extract meaningful error messages from specific exception types (SecurityException → "Permission denied", IOException → "Service temporarily unavailable", else → "Failed to load steps"). Keep message strings simple and user-facing.
   - Run: `./gradlew test`
   - Expect: All tests pass

**Notes:**
- This task completes HEA-12's remaining acceptance criteria (ViewModel error + retry tests)
- The repository becomes a thin mapping layer that either succeeds or propagates exceptions
- The ViewModel is now the error boundary — it catches, logs (Task 6), and maps to UI state
- Follow existing ViewModel pattern: `_uiState.value = _uiState.value.copy(...)` for state updates

---

### Task 6: Add Timber logging (HEA-14)

**Issue:** HEA-14
**Dependencies:** Task 5 (error propagation in place — logging now has errors to log), Prerequisites (Timber dependency added)
**Files:**
- `gradle/libs.versions.toml` (modify — add timber version + library)
- `app/build.gradle.kts` (modify — add timber implementation dependency)
- `app/src/main/kotlin/com/healthhelper/app/HealthHelperApp.kt` (modify — plant DebugTree)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` (modify — add logging)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` (modify — add logging)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify — add logging)

**TDD Steps:**

1. **RED** — Timber logging is not directly unit-testable in the traditional sense (it's observability, not behavior). Instead, verify the dependency setup: add Timber to version catalog and build file, then build.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds with Timber available

2. **GREEN** — Plant `Timber.DebugTree()` in `HealthHelperApp.onCreate()` (guarded by `BuildConfig.DEBUG` check). Add `Timber.d()` calls in repository (before and after Health Connect calls, with record count on success), ViewModel (state transitions), and DI module (client creation). Add `Timber.e(exception)` in ViewModel catch block.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All tests pass, build succeeds

3. **REFACTOR** — Ensure no sensitive health data values are logged. Log record counts and types only (e.g., `Timber.d("Loaded %d step records", records.size)`), never raw step counts or personal data.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

**Notes:**
- Timber 5.0.1 — latest Kotlin-rewritten version
- `DebugTree` auto-tags with class name, zero config needed
- Only plant tree in debug builds — no logging in release by default
- A production crash-reporting tree (e.g., Firebase Crashlytics) can be added later as a separate issue
- Keep log messages concise: operation name + outcome + count/duration

---

### Task 7: Health Connect availability check (HEA-2)

**Issue:** HEA-2
**Dependencies:** Task 6 (Timber available for logging SDK status)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthConnectStatus.kt` (create — enum for SDK availability)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCase.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify — make HealthConnectClient provision conditional)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCaseTest.kt` (create)

**TDD Steps:**

1. **RED** — Create `CheckHealthConnectStatusUseCaseTest`. Test that when SDK status is `SDK_AVAILABLE`, use case returns `HealthConnectStatus.Available`. Test fails because the use case doesn't exist.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCaseTest"`
   - Expect: Compilation error

2. **GREEN** — Create `HealthConnectStatus` enum in domain layer with variants: `Available`, `NotInstalled`, `NeedsUpdate`. Create `CheckHealthConnectStatusUseCase` that wraps `HealthConnectClient.getSdkStatus(context)` and maps the SDK int constants to the domain enum. Inject `Context` via `@ApplicationContext`.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCaseTest"`
   - Expect: Tests pass

3. **RED** — Add tests for `NotInstalled` and `NeedsUpdate` status mapping.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.CheckHealthConnectStatusUseCaseTest"`
   - Expect: Tests pass (implementation already handles all cases)

4. **GREEN** — Refactor `AppModule` to make `HealthConnectClient` optional. Instead of calling `getOrCreate()` eagerly, provide it only when SDK is available. The repository needs to handle the case where the client is null (throw a domain exception). Alternatively, wrap the client in a provider class.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All tests pass, build succeeds

5. **REFACTOR** — Add Timber logging in the availability check: log SDK status on every check.
   - Run: `./gradlew test`
   - Expect: All tests pass

**Notes:**
- `HealthConnectClient.getSdkStatus(context)` returns int constants: `SDK_AVAILABLE`, `SDK_UNAVAILABLE`, `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`
- The DI module must NOT call `getOrCreate()` if SDK is unavailable — that's the crash from HEA-2
- Consider providing `HealthConnectClient?` (nullable) or wrapping in a `HealthConnectClientProvider` that checks status first
- The ViewModel will use this status in Task 8 to determine what to show

---

### Task 8: Expand HealthUiState with availability and permission states (HEA-4)

**Issue:** HEA-4
**Dependencies:** Task 7 (availability check use case exists)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/PermissionStatus.kt` (create — enum)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` (modify — expand `HealthUiState`, add availability/permission checking)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` (modify — render new states)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt` (modify — test new states)

**TDD Steps:**

1. **RED** — Write ViewModel test: when `CheckHealthConnectStatusUseCase` returns `NotInstalled`, uiState reflects `healthConnectStatus = HealthConnectStatus.NotInstalled` and `isLoading = false`. Test fails because HealthUiState doesn't have this field.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Compilation error

2. **GREEN** — Expand `HealthUiState` with `healthConnectStatus: HealthConnectStatus?` field (null = not yet checked). Inject `CheckHealthConnectStatusUseCase` into ViewModel. In `init`, check availability FIRST — if not available, set status and don't attempt to load data.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: New test passes

3. **RED** — Write ViewModel test: when SDK is available but permissions not granted, uiState reflects `permissionStatus = PermissionStatus.NotGranted`. Test fails because field doesn't exist.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Compilation error

4. **GREEN** — Create `PermissionStatus` enum (`NotRequested`, `Granted`, `Denied`) in domain model. Add `permissionStatus: PermissionStatus` to `HealthUiState`. ViewModel checks permission status after confirming availability. Add a `checkPermissionsAndLoad()` method that gates data loading on permission grant.
   - Run: `./gradlew test`
   - Expect: All tests pass

5. **REFACTOR** — Update `HealthScreen.kt` to render distinct UI for each combination: not-installed (message + install CTA), needs-update (message + update CTA), not-permitted (rationale + request button), error (message + retry), empty (guidance), data (current list). Structure the `when` block by priority: availability → permissions → loading → error → empty → data.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

**Notes:**
- The ViewModel's `init` block should check availability first, then permissions, then load data
- Use a `checkAndLoad()` method that can be called on resume to re-check permissions
- The `when` block in HealthScreen becomes the main state machine — order matters for priority
- Create `PermissionStatus` in `domain/model/` alongside `HealthConnectStatus`
- Keep UI composables focused: extract each state into a private composable for readability

---

### Task 9: Implement permission request flow (HEA-1)

**Issue:** HEA-1
**Dependencies:** Task 8 (UI state model supports permission states)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` (modify — add permission check/request coordination)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` (modify — wire up permission request launcher)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt` (modify — permission state transitions)

**TDD Steps:**

1. **RED** — Write ViewModel test: call `onPermissionsResult(granted = setOf(READ_STEPS))` → uiState transitions to `permissionStatus = Granted` and triggers data load. Test fails because method doesn't exist.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Compilation error

2. **GREEN** — Add `onPermissionsResult(granted: Set<String>)` to ViewModel. When required permissions are in the granted set, update status to `Granted` and call `loadSteps()`. When not granted, set to `Denied`.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Test passes

3. **RED** — Write test: when permissions are denied, `permissionStatus = Denied`.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Test passes (already implemented in step 2)

4. **GREEN** — Wire up `HealthScreen` with `rememberLauncherForActivityResult()` using `PermissionController.createRequestPermissionResultContract()`. The permission-not-granted UI state shows a "Grant Permissions" button that launches the request. The result callback calls `viewModel.onPermissionsResult()`.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

5. **REFACTOR** — Add a `getRequiredPermissions()` helper that returns the set of Health Connect permissions the app needs (`HealthPermission.getReadPermission(StepsRecord::class)`). This is the single source of truth for required permissions. Log permission request and result with Timber.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All pass

**Notes:**
- Health Connect permissions use `PermissionController.createRequestPermissionResultContract()`, NOT standard Android runtime permissions
- The permission contract returns `Set<String>` of granted permissions
- Required permissions: `HealthPermission.getReadPermission(StepsRecord::class)` (only READ_STEPS since HEA-6 removes READ_HEART_RATE)
- The denied state should show explanation text and a button that opens the Health Connect app settings
- Re-check permissions in `onResume` (user may grant in Settings) — expose a `recheckPermissions()` method on ViewModel, call it from a `LifecycleEventObserver` in the composable

---

### Task 10: Error and empty states with retry and guidance (HEA-5)

**Issue:** HEA-5
**Dependencies:** Task 9 (permission flow complete, error states have meaningful messages from Task 5)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` (modify — add retry button, guidance, pull-to-refresh)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` (modify — expose refresh function)
- `gradle/libs.versions.toml` (check if material3 pull-to-refresh is available in current Compose BOM)

**TDD Steps:**

1. **RED** — This task is primarily UI work. Write a ViewModel test: `refreshSteps()` method exists and behaves identically to `loadSteps()` (delegates to the same loading logic). Test fails because method doesn't exist.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.HealthViewModelTest"`
   - Expect: Compilation error (or method not found)

2. **GREEN** — If `loadSteps()` is already public, `refreshSteps()` can be an alias or `loadSteps()` can be used directly. The key ViewModel behavior is: calling load after an error clears the error and retries. This is already tested in Task 5.
   - Run: `./gradlew test`
   - Expect: All tests pass

3. **REFACTOR** — Update HealthScreen error state: replace static `Text` with a `Column` containing the error message + a `Button("Retry") { viewModel.loadSteps() }`. Update empty state: add guidance text below "No step records found" (e.g., "Walk around with your phone to track steps, or check Health Connect settings"). Add pull-to-refresh wrapping the LazyColumn using Material 3's `PullToRefreshBox` or similar.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

**Notes:**
- Material 3 pull-to-refresh: use `PullToRefreshBox` from `androidx.compose.material3` (available in Compose BOM 2025.12.00)
- Keep error messages from Task 5's exception-specific mapping
- The retry button should be prominent (filled button, not text button)
- Empty state guidance should be helpful but not condescending
- Pull-to-refresh should work on the data list AND the empty state

---

### Task 11: Use collectAsStateWithLifecycle (HEA-7)

**Issue:** HEA-7
**Dependencies:** Prerequisites (lifecycle-runtime-compose dependency added)
**Files:**
- `gradle/libs.versions.toml` (modify — add lifecycle-runtime-compose if not done in prerequisites)
- `app/build.gradle.kts` (modify — add dependency if not done in prerequisites)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` (modify — swap import and function call)

**TDD Steps:**

1. **RED** — This is a dependency + one-line code change. No unit test needed (lifecycle behavior is a framework concern). Change the import and function call.
   - Replace: `import androidx.compose.runtime.collectAsState`
   - With: `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
   - Replace: `viewModel.uiState.collectAsState()`
   - With: `viewModel.uiState.collectAsStateWithLifecycle()`

2. **GREEN** — Verify build compiles with new import.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

3. **REFACTOR** — Remove unused `collectAsState` import if still present.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All pass

**Notes:**
- `lifecycle-runtime-compose` provides `collectAsStateWithLifecycle` extension
- This stops collection when the composable is below STARTED lifecycle state (backgrounded)
- Functionally identical to `collectAsState` when in foreground — no behavior change visible in tests

---

### Task 12: Manifest and dependency cleanup (HEA-6, HEA-9, HEA-13)

**Issue:** HEA-6, HEA-9, HEA-13
**Dependencies:** Task 9 (permission flow uses only READ_STEPS, safe to remove READ_HEART_RATE)
**Files:**
- `app/src/main/AndroidManifest.xml` (modify — remove READ_HEART_RATE, set allowBackup=false, add dataExtractionRules)
- `app/src/main/res/xml/backup_rules.xml` (create — empty exclude-all rules for API 31+)
- `app/src/main/res/xml/data_extraction_rules.xml` (create — exclude all for API 31+)
- `gradle/libs.versions.toml` (modify — remove navigation-compose entries)
- `app/build.gradle.kts` (modify — remove navigation-compose and hilt-navigation-compose dependencies)

**TDD Steps:**

1. **HEA-6** — Remove `<uses-permission android:name="android.permission.health.READ_HEART_RATE" />` from `AndroidManifest.xml:6`. Verify the permission request flow from Task 9 only requests READ_STEPS.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

2. **HEA-9** — Change `android:allowBackup="true"` to `android:allowBackup="false"` in `AndroidManifest.xml:10`. Create backup rules XML files that exclude all data. Add `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="@xml/backup_rules"` attributes.
   - Run: `./gradlew assembleDebug`
   - Expect: Build succeeds

3. **HEA-13** — Remove `navigation-compose` version and library entries from `libs.versions.toml`. Remove `implementation(libs.navigation.compose)` from `build.gradle.kts`. Note: keep `hilt-navigation-compose` — it provides `hiltViewModel()` which is used in `HealthScreen`. Only remove the standalone `navigation-compose` if it's declared separately from hilt-navigation-compose.
   - Run: `./gradlew test && ./gradlew assembleDebug`
   - Expect: All pass

**IMPORTANT for HEA-13:** Before removing `navigation-compose`, check if `hilt-navigation-compose` has a transitive dependency on it. If so, the explicit declaration may be redundant. If `hilt-navigation-compose` is the only dependency providing `hiltViewModel()`, it must stay. Only remove what is truly unused.

**Notes:**
- HEA-6: The permission is currently never requested at runtime (only declared), so removal has zero functional impact
- HEA-9: `dataExtractionRules` is for API 31+ (cloud backup), `fullBackupContent` is for pre-31 (legacy backup). Both should exclude all data.
- HEA-13: Be careful not to break `hiltViewModel()` — test by building after removal

---

### Task 13: Integration and verification

**Issue:** All (HEA-1 through HEA-14)
**Dependencies:** All previous tasks complete
**Files:** None (verification only)

**Steps:**

1. Run full test suite:
   - `./gradlew test`
   - Expect: All tests pass, including new tests from Tasks 1-10

2. Run lint:
   - `./gradlew lint`
   - Expect: No new warnings or errors introduced

3. Build debug APK:
   - `./gradlew assembleDebug`
   - Expect: Clean build, no warnings

4. Manual verification checklist:
   - [ ] App launches without crash on device with Health Connect installed
   - [ ] App shows availability error on device without Health Connect
   - [ ] Permission request dialog appears when "Grant Permissions" is tapped
   - [ ] After granting permissions, step data loads and displays
   - [ ] After denying permissions, denial state shows with explanation
   - [ ] Error state shows retry button that works
   - [ ] Empty state shows helpful guidance text
   - [ ] Pull-to-refresh works on data list
   - [ ] `adb logcat -s HealthConnectRepositoryImpl:* HealthViewModel:*` shows Timber logs
   - [ ] No sensitive health data values appear in logs (only counts)

5. Verify architecture:
   - [ ] `ReadStepsUseCase` has zero imports from `com.healthhelper.app.data`
   - [ ] `HealthRecord.type` is `HealthRecordType` enum, not String
   - [ ] `HealthRecord.id` field exists and is used as LazyColumn key
   - [ ] No `runBlocking` in test files
   - [ ] `READ_HEART_RATE` permission not in manifest
   - [ ] `allowBackup="false"` in manifest
   - [ ] `collectAsStateWithLifecycle` used instead of `collectAsState`

## MCP Usage During Implementation

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `save_issue` | Move issues to "In Progress" when starting each task |
| Linear | `save_issue` | Move issues to "Done" when task passes verification |
| Linear | `create_comment` | Add implementation notes to issues if deviations from plan |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| Health Connect SDK not installed | Show "Not Installed" UI with install link | ViewModel test (Task 8) |
| Health Connect needs update | Show "Update Required" UI with update link | ViewModel test (Task 8) |
| Permissions not granted | Show rationale with "Grant Permissions" button | ViewModel test (Task 9) |
| Permissions denied | Show explanation with Settings link | ViewModel test (Task 9) |
| SecurityException from Health Connect | Show "Permission denied" error with retry | ViewModel test (Task 5) |
| IOException from Health Connect | Show "Service unavailable" error with retry | ViewModel test (Task 5) |
| Generic exception | Show "Failed to load steps" with retry | ViewModel test (Task 5) |
| Empty data (no records) | Show guidance + pull-to-refresh | UI (Task 10) |

## Risks & Open Questions

- [ ] **Risk: HealthConnectClient nullable DI** — Making the client nullable in DI affects the repository injection chain. May need a wrapper/provider pattern to avoid null checks propagating through the codebase. Mitigation: encapsulate nullability in a `HealthConnectClientProvider` that throws a domain-specific exception when client is unavailable, caught by the ViewModel.
- [ ] **Risk: hilt-navigation-compose depends on navigation-compose** — Removing `navigation-compose` (HEA-13) may break `hiltViewModel()` if it's not transitively available through `hilt-navigation-compose`. Mitigation: check dependency tree with `./gradlew dependencies` before removing.
- [ ] **Risk: Permission re-check on resume** — Using `LifecycleEventObserver` in a composable to trigger permission re-check could cause unnecessary re-checks. Mitigation: debounce or flag to avoid redundant checks within the same session.
- [ ] **Question: Pull-to-refresh API** — Material 3's pull-to-refresh API has changed across Compose BOM versions. Verify `PullToRefreshBox` or equivalent is available in `composeBom = 2025.12.00`. Fallback: use `SwipeRefresh` from accompanist if needed.

## Scope Boundaries

**In Scope:**
- All 14 Backlog issues (HEA-1 through HEA-14)
- Architecture fixes, error handling, permissions, availability, logging, UI states, cleanup
- Tests for all new behavior (TDD)

**Out of Scope:**
- Heart rate reading feature (removed unused permission instead)
- Multi-screen navigation (removed unused dependency instead)
- Production crash reporting tree for Timber (can be a future issue)
- Compose UI tests / instrumented tests (unit tests only in this plan)
- Health Connect data write operations
- Any features not mentioned in the 14 issues

---

## Iteration 1

**Implemented:** 2026-02-26
**Method:** Single-agent (sequential dependencies across all tasks)

### Tasks Completed This Iteration
- Task 1: Move repository interface to domain layer (HEA-8)
- Task 2: Replace HealthRecord.type with enum (HEA-11)
- Task 3: Add stable ID to HealthRecord (HEA-10)
- Task 4: Modernize test patterns — runBlocking → runTest (HEA-12 partial)
- Task 5: Repository error propagation + ViewModel error/retry tests (HEA-3, HEA-12)
- Task 6: Add Timber logging framework and instrument all layers (HEA-14)
- Task 7: Health Connect availability check with provider pattern (HEA-2)
- Task 8: Expand HealthUiState with availability and permission states (HEA-4)
- Task 9: Implement permission request flow with HC permission contract (HEA-1)
- Task 10: Error and empty states with retry, guidance, and pull-to-refresh (HEA-5)
- Task 11: Use collectAsStateWithLifecycle (HEA-7)
- Task 12: Manifest cleanup — remove READ_HEART_RATE, disable backup, remove navigation-compose (HEA-6, HEA-9, HEA-13)
- Task 13: Full verification

### Files Modified
- `gradle/libs.versions.toml` — Added timber, lifecycle-runtime-compose; removed navigation-compose
- `app/build.gradle.kts` — Added timber, lifecycle-runtime-compose, kotlin-test; removed navigation-compose; enabled buildConfig
- `app/src/main/AndroidManifest.xml` — Removed READ_HEART_RATE, set allowBackup=false, added data extraction rules
- `app/src/main/res/xml/backup_rules.xml` — Created (exclude all backup data)
- `app/src/main/res/xml/data_extraction_rules.xml` — Created (exclude all cloud backup/device transfer)
- `app/src/main/kotlin/com/healthhelper/app/HealthHelperApp.kt` — Plant Timber.DebugTree in debug builds
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/HealthConnectRepository.kt` — Created (interface moved from data layer)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecord.kt` — Added id field, changed type to HealthRecordType enum
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecordType.kt` — Created (Steps enum)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthConnectStatus.kt` — Created (Available, NotInstalled, NeedsUpdate)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/PermissionStatus.kt` — Created (NotRequested, Granted, Denied)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCase.kt` — Updated import to domain.repository
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCase.kt` — Created (delegates to provider)
- `app/src/main/kotlin/com/healthhelper/app/data/HealthConnectStatusProvider.kt` — Created (wraps SDK status check, maps to domain enum)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` — Renamed from HealthConnectRepository.kt; removed catch-all; lazy client creation; added Timber logging
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepository.kt` — Deleted (interface moved to domain)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` — Simplified to provide StatusProvider and Repository only; removed direct HealthConnectClient singleton
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` — Expanded HealthUiState with healthConnectStatus and permissionStatus; added checkAndLoad(), onPermissionsResult(); exception-specific error messages; Timber logging
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` — Full rewrite: availability states, permission states, retry, guidance, pull-to-refresh, lifecycle-aware collection, safe Play Store intent
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` — Updated imports, enum types, id field, runTest, added exception propagation test
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCaseTest.kt` — Created (3 tests for provider delegation)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt` — Expanded from 2 to 8 tests covering error, retry, availability, and permission states

### Linear Updates
- HEA-8: Todo → In Progress → Review
- HEA-11: Todo → In Progress → Review
- HEA-10: Todo → In Progress → Review
- HEA-12: Todo → In Progress → Review
- HEA-3: Todo → In Progress → Review
- HEA-14: Todo → In Progress → Review
- HEA-2: Todo → In Progress → Review
- HEA-4: Todo → In Progress → Review
- HEA-1: Todo → In Progress → Review
- HEA-5: Todo → In Progress → Review
- HEA-7: Todo → In Progress → Review
- HEA-6: Todo → In Progress → Review
- HEA-9: Todo → In Progress → Review
- HEA-13: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 4 bugs + 1 convention issue, all fixed before commit
  - HIGH: startActivity crash on non-GMS devices — added try/catch with HTTPS fallback
  - HIGH: Broken nested runTest in propagatesExceptions — rewrote with assertFailsWith
  - MEDIUM: Singleton HealthConnectClient never refreshes — refactored to lazy creation in repository
  - MEDIUM: Null healthConnectStatus falls through to permission UI — added explicit null → loading branch
  - LOW: Domain use case imported HC constants — moved mapping to data layer provider
- verifier: All 18 tests pass, build succeeds, zero warnings

### Review Findings

Summary: 8 issue(s) found (Team: security, reliability, quality reviewers)
- FIX: 8 issue(s) — Linear issues created (HEA-15 through HEA-22)
- DISCARDED: 4 finding(s) — false positives / not applicable

**Issues requiring fix:**
- [HIGH] BUG: startActivity crash on manage health permissions intent (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt:149-152`) — No ActivityNotFoundException handler; crashes on devices where ACTION_MANAGE_HEALTH_PERMISSIONS is not resolved (HEA-15)
- [MEDIUM] BUG: Permission grant check uses isNotEmpty() instead of containsAll() (`app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt:56`) — Partial grant incorrectly sets Granted if additional permissions added (HEA-16)
- [MEDIUM] BUG: permissionStatus not reset to Denied on SecurityException (`app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt:75-86`) — User gets stuck in error-retry loop after mid-session permission revocation (HEA-17)
- [MEDIUM] COROUTINE: No in-flight cancellation guard in loadSteps() (`app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt:64`) — Race between onPermissionsResult and ON_RESUME both calling loadSteps() (HEA-18)
- [MEDIUM] TIMEOUT: No timeout on readRecords() (`app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt:30-35`) — Health Connect IPC can hang indefinitely (HEA-19)
- [MEDIUM] CONVENTION: Timber in domain layer + double-logging (`app/src/main/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCase.kt:5,13`) — Domain must be pure Kotlin per CLAUDE.md; same status logged in use case and ViewModel (HEA-20)
- [LOW] LOGGING: Missing duration logging for readRecords API call (`app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt:30`) — Review checklist requires duration on external API calls (HEA-21)
- [LOW] CONVENTION: junit-platform-launcher not in version catalog (`app/build.gradle.kts:70`) — Hardcoded string literal violates CLAUDE.md rule (HEA-22)

**Discarded findings (not bugs):**
- [DISCARDED] SECURITY: isMinifyEnabled = false for release (`app/build.gradle.kts:22`) — Standard boilerplate for apps in active development. Enabling R8 without proguard rules could introduce runtime crashes. Release readiness concern, not a correctness bug.
- [DISCARDED] TYPE: Force unwrap `!!` on errorMessage (`HealthScreen.kt:174`) — Safe due to `when` branch guard at line 170 (`uiState.errorMessage != null`). Smart cast doesn't apply across property accessor chains; `!!` is justified by the null check in the same branch.
- [DISCARDED] CONVENTION: DisposableEffect used as fully-qualified name (`HealthScreen.kt:71`) — Pure cosmetic preference not enforced by CLAUDE.md. Zero correctness impact.
- [DISCARDED] EDGE CASE: daysBack ≤ 0 guard in ReadStepsUseCase (`ReadStepsUseCase.kt:16`) — Only called from one location with safe default value of 7. Not a system boundary; adding a guard would be over-engineering per CLAUDE.md conventions.

### Linear Updates
- HEA-1 through HEA-14: Review → Merge (original tasks completed)
- HEA-15: Created in Todo (Fix: startActivity crash)
- HEA-16: Created in Todo (Fix: permission grant check)
- HEA-17: Created in Todo (Fix: permissionStatus not reset)
- HEA-18: Created in Todo (Fix: loadSteps race condition)
- HEA-19: Created in Todo (Fix: readRecords timeout)
- HEA-20: Created in Todo (Fix: Timber in domain layer)
- HEA-21: Created in Todo (Fix: missing duration logging)
- HEA-22: Created in Todo (Fix: junit-platform-launcher catalog)

<!-- REVIEW COMPLETE -->

---

## Fix Plan

**Source:** Review findings from Iteration 1
**Linear Issues:** [HEA-15](https://linear.app/lw-claude/issue/HEA-15), [HEA-16](https://linear.app/lw-claude/issue/HEA-16), [HEA-17](https://linear.app/lw-claude/issue/HEA-17), [HEA-18](https://linear.app/lw-claude/issue/HEA-18), [HEA-19](https://linear.app/lw-claude/issue/HEA-19), [HEA-20](https://linear.app/lw-claude/issue/HEA-20), [HEA-21](https://linear.app/lw-claude/issue/HEA-21), [HEA-22](https://linear.app/lw-claude/issue/HEA-22)

### Fix 1: startActivity crash on manage health permissions intent (HEA-15)
**Linear Issue:** [HEA-15](https://linear.app/lw-claude/issue/HEA-15)

1. Write test: launch Settings intent in HealthScreen when no activity resolves — verify no crash (UI test or verify pattern consistency)
2. Wrap `context.startActivity(intent)` at `HealthScreen.kt:152` in try/catch for `ActivityNotFoundException`, with fallback to `openHealthConnectPlayStore(context)` — same pattern as line 229

### Fix 2: Permission grant check uses isNotEmpty() instead of containsAll() (HEA-16)
**Linear Issue:** [HEA-16](https://linear.app/lw-claude/issue/HEA-16)

1. Write test in `HealthViewModelTest.kt`: call `onPermissionsResult(setOf("wrong.permission"))` → assert `permissionStatus == Denied`
2. Move `REQUIRED_PERMISSIONS` to a companion object in `HealthViewModel` (shared source of truth)
3. Change `granted.isNotEmpty()` to `granted.containsAll(REQUIRED_PERMISSIONS)` in `HealthViewModel.kt:56`
4. Update `HealthScreen.kt` to reference `HealthViewModel.REQUIRED_PERMISSIONS`

### Fix 3: permissionStatus not reset to Denied on SecurityException (HEA-17)
**Linear Issue:** [HEA-17](https://linear.app/lw-claude/issue/HEA-17)

1. Write test in `HealthViewModelTest.kt`: set permissionStatus to Granted, mock use case to throw `SecurityException` → call `loadSteps()` → assert `permissionStatus == Denied`
2. In `HealthViewModel.kt:82-85`, add `permissionStatus = PermissionStatus.Denied` to the state copy in the `SecurityException` catch branch

### Fix 4: No in-flight cancellation guard in loadSteps() (HEA-18)
**Linear Issue:** [HEA-18](https://linear.app/lw-claude/issue/HEA-18)

1. Write test in `HealthViewModelTest.kt`: call `loadSteps()` twice rapidly → assert only one loading cycle completes (no duplicate records)
2. Add `private var loadStepsJob: Job? = null` field to `HealthViewModel`
3. In `loadSteps()`, cancel existing job before launching: `loadStepsJob?.cancel(); loadStepsJob = viewModelScope.launch { ... }`
4. Ensure `CancellationException` is not caught by the generic `catch (e: Exception)` — add `if (e is CancellationException) throw e` or use `ensureActive()`

### Fix 5: No timeout on readRecords() (HEA-19)
**Linear Issue:** [HEA-19](https://linear.app/lw-claude/issue/HEA-19)

1. Write test in `ReadStepsUseCaseTest.kt`: mock repository to delay indefinitely → call with timeout → verify `TimeoutCancellationException` propagates
2. Wrap `client.readRecords()` in `withTimeout(30_000L)` at `HealthConnectRepositoryImpl.kt:30`
3. Add timeout-specific error message in `HealthViewModel.kt` catch block: `is kotlinx.coroutines.TimeoutCancellationException -> "Request timed out. Please try again."`

### Fix 6: Timber in domain layer + double-logging (HEA-20)
**Linear Issue:** [HEA-20](https://linear.app/lw-claude/issue/HEA-20)

1. Remove `import timber.log.Timber` from `CheckHealthConnectStatusUseCase.kt:5`
2. Remove `Timber.d("Health Connect status: %s", status)` from `CheckHealthConnectStatusUseCase.kt:13`
3. Run `./gradlew test` to verify no breakage
4. Verify domain layer has zero Android imports: grep for `android\.|timber\.` in `domain/` package

### Fix 7: Missing duration logging for readRecords (HEA-21)
**Linear Issue:** [HEA-21](https://linear.app/lw-claude/issue/HEA-21)

1. Use `kotlin.time.measureTimedValue` around `client.readRecords()` call at `HealthConnectRepositoryImpl.kt:30-35`
2. Update success log to include duration: `Timber.d("Loaded %d step records in %s", response.records.size, duration)`

### Fix 8: junit-platform-launcher not in version catalog (HEA-22)
**Linear Issue:** [HEA-22](https://linear.app/lw-claude/issue/HEA-22)

1. Add `junit-platform-launcher` version and library entry to `gradle/libs.versions.toml`
2. Replace hardcoded string in `app/build.gradle.kts:70` with `testRuntimeOnly(libs.junit.platform.launcher)`
3. Run `./gradlew test` to verify

---

## Iteration 2

**Implemented:** 2026-02-26
**Method:** Single-agent (low effort score — 10 points across 2 units)

### Tasks Completed This Iteration
- Fix 1: startActivity crash on manage health permissions intent (HEA-15) — wrapped in try/catch with PlayStore fallback
- Fix 2: Permission grant check uses containsAll() instead of isNotEmpty() (HEA-16) — moved REQUIRED_PERMISSIONS to ViewModel companion, updated HealthScreen
- Fix 3: permissionStatus reset to Denied on SecurityException (HEA-17) — added permissionStatus update in catch branch
- Fix 4: Job cancellation guard in loadSteps() (HEA-18) — added loadStepsJob field, cancel before re-launch, rethrow CancellationException
- Fix 5: withTimeout on readRecords() (HEA-19) — 30s timeout, dedicated TimeoutCancellationException handler with user-friendly message
- Fix 6: Remove Timber from domain layer (HEA-20) — removed import and log call from CheckHealthConnectStatusUseCase
- Fix 7: Duration logging for readRecords (HEA-21) — measureTimedValue wrapping withTimeout block
- Fix 8: junit-platform-launcher to version catalog (HEA-22) — added to libs.versions.toml, replaced hardcoded string

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt` — containsAll() check, SecurityException permission reset, Job cancellation, TimeoutCancellationException handler, REQUIRED_PERMISSIONS companion object, import ordering
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt` — startActivity try/catch, reference HealthViewModel.REQUIRED_PERMISSIONS, removed local REQUIRED_PERMISSIONS
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt` — withTimeout(30s), measureTimedValue duration logging
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCase.kt` — removed Timber import and log call
- `gradle/libs.versions.toml` — added junitPlatformLauncher version and library entry
- `app/build.gradle.kts` — replaced hardcoded junit-platform-launcher with catalog reference
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt` — added 3 new tests (timeout, SecurityException reset, partial grant), replaced hardcoded permission strings with REQUIRED_PERMISSIONS
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt` — added timeout propagation test

### Linear Updates
- HEA-15: Todo → In Progress → Review
- HEA-16: Todo → In Progress → Review
- HEA-17: Todo → In Progress → Review
- HEA-18: Todo → In Progress → Review
- HEA-19: Todo → In Progress → Review
- HEA-20: Todo → In Progress → Review
- HEA-21: Todo → In Progress → Review
- HEA-22: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 2 issues, both fixed before commit
  - MEDIUM: Hardcoded permission strings in tests diverge from production constant — replaced with HealthViewModel.REQUIRED_PERMISSIONS
  - LOW: Import ordering violation in HealthViewModel — reordered to standard Kotlin/Android convention
- verifier: All tests pass, build succeeds, zero warnings

### Review Findings

Summary: 11 finding(s) found, fixed inline (Team: security, reliability, quality reviewers)
- FIXED INLINE: 6 issue(s) — verified via TDD + bug-hunter
- DISCARDED: 5 finding(s) — false positives / not applicable

**Issues fixed inline:**
- [MEDIUM] BUG: Pull-to-refresh shows full-screen spinner instead of in-list indicator — added `isRefreshing` state + `refreshSteps()` method (HEA-23)
- [LOW] BUG: Double checkAndLoad() on screen creation — removed `init { checkAndLoad() }`, ON_RESUME handles it (HEA-24)
- [MEDIUM] CONVENTION: Repository missing try-catch at data layer boundary — added `StepsResult` sealed class, repository try-catch, removed IOException from ViewModel (HEA-25)
- [MEDIUM] CONVENTION: Turbine missing from test dependencies — added Turbine + intermediate state tests (HEA-26)
- [LOW] TEST: No edge case coverage for invalid daysBack — added `require(daysBack >= 1)` + tests (HEA-27)
- [LOW] TEST: loadsStepsOnInit doesn't exercise pre-granted permissions path — added `loadsStepsOnResumeWithPreGrantedPermissions` test (HEA-28)

**Discarded findings (not bugs):**
- [DISCARDED] SECURITY: `isMinifyEnabled = false` in release build (`app/build.gradle.kts:23`) — Development phase configuration; minification is a pre-release hardening step, not a correctness bug
- [DISCARDED] SECURITY: Health Connect permissions logged to Logcat (`HealthViewModel.kt:67`) — Timber is correctly gated to debug builds only; standard debug logging practice
- [DISCARDED] COROUTINE: `HealthConnectClient.getOrCreate(context)` runs on Main thread (`HealthConnectRepositoryImpl.kt:30`) — Lightweight synchronous factory returning cached singleton; standard Health Connect SDK usage pattern
- [DISCARDED] TYPE: Force unwrap `!!` in HealthScreen.kt:172 — Safe within `errorMessage != null` when-branch; Compose snapshot guarantees value stability within recomposition
- [DISCARDED] CONVENTION: IOException reference in HealthViewModel.kt:100 — Merged into HEA-25 (same root cause as missing data layer try-catch)

### Linear Updates
- HEA-15: Review → Merge
- HEA-16: Review → Merge
- HEA-17: Review → Merge
- HEA-18: Review → Merge
- HEA-19: Review → Merge
- HEA-20: Review → Merge
- HEA-21: Review → Merge
- HEA-22: Review → Merge
- HEA-23: Created in Merge (Fix: pull-to-refresh — fixed inline)
- HEA-24: Created in Merge (Fix: double checkAndLoad — fixed inline)
- HEA-25: Created in Merge (Fix: data layer error handling — fixed inline)
- HEA-26: Created in Merge (Fix: Turbine missing — fixed inline)
- HEA-27: Created in Merge (Fix: daysBack edge cases — fixed inline)
- HEA-28: Created in Merge (Fix: test init path — fixed inline)

<!-- REVIEW COMPLETE -->

### Continuation Status
All tasks completed.

### Inline Fix Verification
- Unit tests: 27 pass, 0 fail
- Bug-hunter: no new issues

---

## Status: COMPLETE

All tasks implemented and reviewed successfully. All Linear issues moved to Merge.
