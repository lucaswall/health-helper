# Implementation Plan

**Status:** IN_PROGRESS
**Branch:** feat/HEA-44-backlog-audit-fixes
**Issues:** HEA-44, HEA-45, HEA-46, HEA-47, HEA-48, HEA-49, HEA-50, HEA-51, HEA-52, HEA-53, HEA-56, HEA-57, HEA-58, HEA-59, HEA-60, HEA-62, HEA-64, HEA-65, HEA-66, HEA-67, HEA-68, HEA-69, HEA-70, HEA-71
**Created:** 2026-02-27
**Last Updated:** 2026-02-27

## Summary

Comprehensive code audit fix plan addressing 23 backlog issues across security, bugs, architecture, conventions, test quality, and CI infrastructure. Issues range from critical (CancellationException swallowing, missing try/finally) to low (import consistency, duplicate tests). Ordered domain-first per Clean Architecture: domain models/use cases, then data layer, then presentation, then build config, then test improvements.

## Issues

### HEA-50: Domain use case directly imports concrete data-layer class

**Priority:** High | **Labels:** Convention
**Description:** `SyncNutritionUseCase` imports `FoodScannerApiClient` (concrete data-layer class), violating Clean Architecture. Domain must depend only on domain-layer interfaces.
**Acceptance Criteria:**
- [ ] Domain use case depends on a domain-layer interface, not FoodScannerApiClient
- [ ] DI module binds the interface to a data-layer implementation

### HEA-64: No init-block validation in FoodLogEntry data class

**Priority:** Low | **Labels:** Bug
**Description:** `FoodLogEntry` accepts negative values for calories, proteinG, etc. Health Connect rejects negatives at write time; fail-fast at domain layer is better.
**Acceptance Criteria:**
- [ ] Negative nutritional values rejected at construction via `require()` blocks
- [ ] Tests cover validation of negative values

### HEA-49: Unhandled DateTimeParseException in date parsing at two locations

**Priority:** High | **Labels:** Bug
**Description:** `LocalDate.parse()` in `SyncNutritionUseCase:31` has no try-catch. Corrupt `lastSyncedDate` in DataStore throws unchecked `DateTimeParseException`.
**Acceptance Criteria:**
- [ ] `DateTimeParseException` caught and handled gracefully
- [ ] Corrupt date treated as empty (full re-sync from maxPastDate)
- [ ] Tests cover malformed date input

### HEA-47: CancellationException swallowed by broad catch(Exception) in two locations

**Priority:** High | **Labels:** Bug
**Description:** `catch (e: Exception)` in `FoodScannerApiClient:73` and `HealthConnectNutritionRepository:29` catches `CancellationException`, breaking structured concurrency.
**Acceptance Criteria:**
- [ ] `CancellationException` rethrown in both catch blocks
- [ ] Tests verify CancellationException propagates

### HEA-46: No HTTPS scheme validation on user-supplied base URL

**Priority:** High | **Labels:** Security
**Description:** User-configured `baseUrl` used without scheme validation. An `http://` URL transmits the bearer token in cleartext.
**Acceptance Criteria:**
- [ ] URLs not starting with `https://` are rejected
- [ ] Tests cover http:// rejection and https:// acceptance

### HEA-48: triggerSync() has no try/finally — isSyncing stuck permanently on error

**Priority:** High | **Labels:** Bug
**Description:** If `syncNutritionUseCase.invoke()` throws (e.g., `DateTimeParseException`), `isSyncing` stays true permanently, disabling the Sync button.
**Acceptance Criteria:**
- [ ] `isSyncing` always reset to false via try/finally
- [ ] Error message shown to user on unexpected exception
- [ ] Test verifies isSyncing resets on exception

### HEA-52: Default sync interval (10min) below WorkManager minimum (15min)

**Priority:** Medium | **Labels:** Bug
**Description:** `DEFAULT_SYNC_INTERVAL = 10` but `SyncScheduler.MIN_INTERVAL_MINUTES = 15` and UI slider starts at 15. Fresh install shows 10 in UI, gets clamped to 15.
**Acceptance Criteria:**
- [ ] Default changed to 15 to match WorkManager minimum and UI slider

### HEA-53: LaunchedEffect missing permissionGranted key in SyncScreen

**Priority:** Medium | **Labels:** Bug
**Description:** `LaunchedEffect(uiState.healthConnectAvailable)` body reads `permissionGranted` but doesn't include it as a key.
**Acceptance Criteria:**
- [ ] `permissionGranted` added to LaunchedEffect key set

### HEA-65: Redundant isConfigured() call in SettingsViewModel combine block

**Priority:** Low | **Labels:** Convention
**Description:** `settingsRepository.isConfigured()` called inside combine/collect when `apiKey` and `baseUrl` are already available for inline derivation.
**Acceptance Criteria:**
- [ ] `isConfigured` derived inline from combine parameters (matching SyncViewModel pattern)

### HEA-67: HTTP 401 Unauthorized logged at ERROR instead of WARN

**Priority:** Low | **Labels:** Bug
**Description:** All HTTP errors logged at `Timber.e`. 401 is an expected misconfiguration, not a system failure.
**Acceptance Criteria:**
- [ ] 401 logged at WARN level; other HTTP errors remain at ERROR

### HEA-68: Spurious state updates from combine including unused syncIntervalFlow

**Priority:** Low | **Labels:** Convention
**Description:** First `combine` in SyncViewModel includes `syncIntervalFlow` as unused `_` parameter. Interval changes trigger unnecessary state updates.
**Acceptance Criteria:**
- [ ] `syncIntervalFlow` removed from the first combine block

### HEA-66: String templates used instead of Timber format specifiers in SyncWorker

**Priority:** Low | **Labels:** Convention
**Description:** `${}` string templates in Timber calls evaluate even in release builds without a DebugTree.
**Acceptance Criteria:**
- [ ] String templates replaced with `%s`/`%d` format specifiers

### HEA-62: No explicit network security config in AndroidManifest

**Priority:** Low | **Labels:** Security
**Description:** No `android:networkSecurityConfig` attribute. Android 28+ blocks cleartext by default, but explicit config documents intent.
**Acceptance Criteria:**
- [ ] `network_security_config.xml` created blocking cleartext traffic
- [ ] Referenced in AndroidManifest.xml

### HEA-45: API key stored in plain unencrypted DataStore

**Priority:** Urgent | **Labels:** Security
**Description:** API bearer token stored as raw string in `DataStore<Preferences>` under `"api_key"`. Unencrypted and accessible via root/backup.
**Acceptance Criteria:**
- [ ] API key stored in EncryptedSharedPreferences
- [ ] Migration from plain DataStore to encrypted storage on first run
- [ ] Flow-based reactive interface preserved
- [ ] Old plain-text key removed from DataStore after migration

### HEA-44: R8/ProGuard obfuscation disabled in release build

**Priority:** Urgent | **Labels:** Security
**Description:** `isMinifyEnabled = false` for release. APK completely unobfuscated.
**Acceptance Criteria:**
- [ ] R8 enabled for release builds
- [ ] ProGuard rules for all dependencies (Kotlin serialization, Ktor, Health Connect, Hilt)
- [ ] Release build compiles successfully

### HEA-51: NutritionRecordMapperTest assertion fragility (Int/Long)

**Priority:** High | **Labels:** Bug
**Description:** `assertEquals(60, duration.seconds)` — works via Kotlin literal widening but fragile if refactored to use a variable.
**Acceptance Criteria:**
- [ ] Literal changed to `60L` for explicit Long comparison

### HEA-56: SyncViewModelTest double-trigger guard assertion always passes

**Priority:** Medium | **Labels:** Technical Debt
**Description:** `assertTrue(callCount >= 1)` at line 211 is trivially true. Does not test the double-trigger guard.
**Acceptance Criteria:**
- [ ] Test removed (functionality covered by `cannot trigger sync when isSyncing is true` test at line 215)

### HEA-57: SyncSchedulerTest does not verify clamped interval value

**Priority:** Medium | **Labels:** Technical Debt
**Description:** Clamping test only checks `enqueueUniquePeriodicWork` was called, not that the interval was clamped.
**Acceptance Criteria:**
- [ ] Test captures `PeriodicWorkRequest` and verifies interval is 15 minutes (not 5)

### HEA-59: Force unwrap (!!) on nullable capturedUrl in FoodScannerApiClientTest

**Priority:** Medium | **Labels:** Technical Debt
**Description:** `capturedUrl!!` force-unwrap produces unclear NPE instead of assertion failure.
**Acceptance Criteria:**
- [ ] Force unwraps replaced with `assertNotNull` for clear failure messages

### HEA-69: MealTypeTest uses JUnit 5 import instead of kotlin.test

**Priority:** Low | **Labels:** Technical Debt
**Description:** `import org.junit.jupiter.api.Assertions.assertEquals` instead of `kotlin.test.assertEquals` used everywhere else.
**Acceptance Criteria:**
- [ ] Import changed to `kotlin.test.assertEquals`

### HEA-70: SyncSchedulerTest has duplicate test assertions

**Priority:** Low | **Labels:** Technical Debt
**Description:** `uses correct work name` and `uses UPDATE policy` tests duplicate assertions already in the first test.
**Acceptance Criteria:**
- [ ] Redundant tests removed

### HEA-58: HealthConnectNutritionRepository missing test coverage for error paths

**Priority:** Medium | **Labels:** Technical Debt
**Description:** Only 1 test (null client). SecurityException, general Exception, and successful write paths untested.
**Acceptance Criteria:**
- [ ] Tests for SecurityException → returns false
- [ ] Tests for general Exception → returns false
- [ ] Tests for successful write → returns true
- [ ] Tests for empty entries list handling

### HEA-60: No unit tests for SyncWorker

**Priority:** Medium | **Labels:** Technical Debt
**Description:** `SyncWorker.doWork()` branching logic (Success/Error/NeedsConfiguration) is untested.
**Acceptance Criteria:**
- [ ] Tests for Success → Result.success()
- [ ] Tests for Error → Result.retry()
- [ ] Tests for NeedsConfiguration → Result.failure()

### HEA-71: Add GitHub Actions workflow to run tests on PRs

**Priority:** Medium | **Labels:** Technical Debt
**Description:** No CI workflow runs tests on pull requests. Existing workflows are Claude Code integrations only (code review + PR assistant). Test regressions can be merged undetected.
**Acceptance Criteria:**
- [ ] GitHub Actions workflow triggers on PRs to `main`
- [ ] Runs `./gradlew test` (unit tests)
- [ ] Runs `./gradlew assembleDebug` (build verification)
- [ ] Uses JDK 17 and caches Gradle dependencies
- [ ] Reports test results clearly on PR checks

## Prerequisites

- [x] Gradle dependencies are up to date
- [x] `gradle/libs.versions.toml` has required library versions
- [x] Build compiles successfully
- [x] All existing tests pass
- [ ] `security-crypto` version added to `libs.versions.toml` (needed for HEA-45)

## Implementation Tasks

### Task 1: Create FoodLogRepository domain interface

**Issue:** HEA-50
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/FoodLogRepository.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/FoodScannerFoodLogRepository.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)

**TDD Steps:**

1. **RED** — Update `SyncNutritionUseCaseTest` to mock a `FoodLogRepository` interface instead of `FoodScannerApiClient`. The interface should have `suspend fun getFoodLog(baseUrl: String, apiKey: String, date: String): Result<List<FoodLogEntry>>`. Tests will fail to compile because the interface doesn't exist yet.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SyncNutritionUseCaseTest"`
   - Verify: Compilation fails — `FoodLogRepository` not found

2. **GREEN** — Create `FoodLogRepository` interface in `domain/repository/`. Create `FoodScannerFoodLogRepository` in `data/repository/` that wraps `FoodScannerApiClient` and implements the interface. Update `SyncNutritionUseCase` constructor to take `FoodLogRepository` instead of `FoodScannerApiClient`. Update `AppModule` to bind `FoodLogRepository` to `FoodScannerFoodLogRepository`.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SyncNutritionUseCaseTest"`
   - Verify: All 16 existing tests pass

3. **REFACTOR** — Verify `SyncNutritionUseCase` has no data-layer imports. The only imports should be from `domain/` packages and standard library.

**Notes:**
- Follow existing pattern: `NutritionRepository` interface in `domain/repository/`, `HealthConnectNutritionRepository` impl in `data/repository/`
- The wrapper delegates directly to `FoodScannerApiClient.getFoodLog()` — no new logic
- Reference: `domain/repository/NutritionRepository.kt` and `data/repository/HealthConnectNutritionRepository.kt`

### Task 2: Add FoodLogEntry init validation

**Issue:** HEA-64
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogEntry.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/FoodLogEntryTest.kt` (create)

**TDD Steps:**

1. **RED** — Create `FoodLogEntryTest` with tests asserting that negative `calories`, `proteinG`, `carbsG`, `fatG`, `fiberG`, `sodiumMg` throw `IllegalArgumentException`. Also test that negative nullable fields (`saturatedFatG`, `transFatG`, `sugarsG`, `caloriesFromFat`) throw when non-null and negative. Test that zero and positive values succeed.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.FoodLogEntryTest"`
   - Verify: Negative-value tests pass (no validation yet — data class accepts anything)

   Wait — tests for "negative throws" will PASS incorrectly since no validation exists. Adjust: write tests that assert `assertThrows<IllegalArgumentException>` for negative values. These will FAIL because no exception is thrown yet.

2. **GREEN** — Add an `init` block to `FoodLogEntry` with `require()` calls for each non-negative constraint. Required (non-nullable) fields: `calories >= 0`, `proteinG >= 0`, `carbsG >= 0`, `fatG >= 0`, `fiberG >= 0`, `sodiumMg >= 0`. Nullable fields: validate only when non-null.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.FoodLogEntryTest"`
   - Verify: All validation tests pass

3. **REFACTOR** — Verify existing tests in `FoodScannerApiClientTest`, `SyncNutritionUseCaseTest`, and `NutritionRecordMapperTest` still pass (they use valid non-negative test data).
   - Run: `./gradlew test`
   - Verify: Full suite passes

**Notes:**
- Use descriptive `require` messages: `require(calories >= 0) { "calories must be non-negative, was $calories" }`
- Reference: standard Kotlin `require()` pattern

### Task 3: Handle DateTimeParseException in SyncNutritionUseCase

**Issue:** HEA-49
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add test: when `lastSyncedDateFlow` returns a malformed date string (e.g., `"not-a-date"`), `invoke()` should treat it as empty and sync from `maxPastDate` (not throw). Assert the result is `SyncResult.Success` or `SyncResult.Error` (depending on API responses), NOT an uncaught exception.
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SyncNutritionUseCaseTest"`
   - Verify: Test fails with `DateTimeParseException`

2. **GREEN** — Wrap the `LocalDate.parse(lastSyncedDate, ...)` call in a try-catch for `DateTimeParseException`. On catch, log a warning and treat as empty string (fall through to `maxPastDate`).
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SyncNutritionUseCaseTest"`
   - Verify: Test passes

3. **REFACTOR** — Extract the date parsing into a small private function for clarity.

**Defensive Requirements:**
- `DateTimeParseException` caught and logged at WARN level
- Corrupt date triggers full re-sync (safe fallback)
- No state corruption — `lastSyncedDate` in DataStore is only updated on successful sync

### Task 4: Rethrow CancellationException in data layer

**Issue:** HEA-47
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectNutritionRepository.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectNutritionRepositoryTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add tests to both test files asserting that `CancellationException` propagates (is not caught). In `FoodScannerApiClientTest`: mock the HTTP engine to throw `CancellationException`, assert it propagates. In `HealthConnectNutritionRepositoryTest`: mock `insertRecords` to throw `CancellationException`, assert it propagates.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.api.FoodScannerApiClientTest" --tests "com.healthhelper.app.data.repository.HealthConnectNutritionRepositoryTest"`
   - Verify: Tests fail (CancellationException is caught and swallowed)

2. **GREEN** — In both `catch (e: Exception)` blocks, add `if (e is CancellationException) throw e` as the first line. This preserves structured concurrency while keeping the existing error handling for other exceptions.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.api.FoodScannerApiClientTest" --tests "com.healthhelper.app.data.repository.HealthConnectNutritionRepositoryTest"`
   - Verify: All tests pass (including existing ones)

**Defensive Requirements:**
- `kotlin.coroutines.cancellation.CancellationException` must propagate through all coroutine boundaries
- Existing error handling for `SerializationException`, `SecurityException`, and general `Exception` remains unchanged
- Import `kotlin.coroutines.cancellation.CancellationException` (not `java.util.concurrent.CancellationException`)

### Task 5: Add HTTPS scheme validation on base URL

**Issue:** HEA-46
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add test: calling `getFoodLog("http://food.example.com", ...)` returns `Result.failure` with message indicating HTTPS is required. Add test: `getFoodLog("https://food.example.com", ...)` continues to work.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.api.FoodScannerApiClientTest"`
   - Verify: HTTP test fails (no validation exists, request proceeds)

2. **GREEN** — Add a scheme check at the start of `getFoodLog()`: if `baseUrl` does not start with `https://` (case-insensitive), return `Result.failure(Exception("HTTPS required for API connections"))`.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.api.FoodScannerApiClientTest"`
   - Verify: All tests pass

**Defensive Requirements:**
- Check is case-insensitive (`HTTP://` also rejected)
- Empty/blank baseUrl also rejected
- Validation happens before any network call (no token leakage)

### Task 6: Add try/finally to triggerSync in SyncViewModel

**Issue:** HEA-48
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add test: when `syncNutritionUseCase.invoke()` throws an unexpected exception (e.g., `RuntimeException("unexpected")`), `isSyncing` should be reset to `false` and `lastSyncResult` should contain an error message.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SyncViewModelTest"`
   - Verify: Test fails (isSyncing stays true, lastSyncResult is null)

2. **GREEN** — Wrap the `syncNutritionUseCase.invoke()` call and result handling in `try { ... } catch (e: Exception) { ... } finally { ... }`. In `catch`: set error message. In `finally`: always set `isSyncing = false` and `syncProgress = null`.
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SyncViewModelTest"`
   - Verify: All tests pass

**Defensive Requirements:**
- `finally` block always executes, even on `CancellationException`
- Error message in catch should be generic: "Unexpected error: ${e.message}"
- `CancellationException` should be rethrown after cleanup (coroutine cancellation must propagate)

### Task 7: Small bug fixes and convention cleanup

**Issues:** HEA-52, HEA-53, HEA-65, HEA-66, HEA-67, HEA-68
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify — HEA-52)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify — HEA-53)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify — HEA-65)
- `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncWorker.kt` (modify — HEA-66)
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify — HEA-67)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify — HEA-68)

**Steps (surgical changes, no TDD needed for config/convention fixes):**

1. **HEA-52** — In `DataStoreSettingsRepository`, change `const val DEFAULT_SYNC_INTERVAL = 10` to `15`.

2. **HEA-53** — In `SyncScreen`, change `LaunchedEffect(uiState.healthConnectAvailable)` to `LaunchedEffect(uiState.healthConnectAvailable, uiState.permissionGranted)`.

3. **HEA-65** — In `SettingsViewModel`, replace the `settingsRepository.isConfigured()` call inside the combine/collect with inline derivation: `val configured = apiKey.isNotEmpty() && baseUrl.isNotEmpty()`. Follow the pattern already used in `SyncViewModel:58`.

4. **HEA-66** — In `SyncWorker`, replace string templates with Timber format specifiers:
   - Line 24: `"SyncWorker: success, synced ${result.recordsSynced} records"` → use `%d` for recordsSynced
   - Line 28: `"SyncWorker: error — ${result.message}, will retry"` → use `%s` for message

5. **HEA-67** — In `FoodScannerApiClient`, split the Timber log for HTTP errors: use `Timber.w` for 401 status, keep `Timber.e` for all other HTTP error statuses. Move the log call inside the `when` block or add a conditional.

6. **HEA-68** — In `SyncViewModel`, remove `settingsRepository.syncIntervalFlow` from the first `combine` block (lines 52-67). Change the combine from 4 flows to 3 flows (apiKeyFlow, baseUrlFlow, lastSyncedDateFlow). Remove the unused `_` lambda parameter. The second combine (lines 72-84) already handles sync interval changes separately.

**Verification:**
- Run: `./gradlew test`
- Verify: All existing tests pass (these are non-behavior-changing fixes except HEA-52 which changes a default value)

### Task 8: Add network security configuration

**Issue:** HEA-62
**Files:**
- `app/src/main/res/xml/network_security_config.xml` (create)
- `app/src/main/AndroidManifest.xml` (modify)

**Steps:**

1. Create `app/src/main/res/xml/network_security_config.xml` that explicitly disallows cleartext traffic (matching Android 28+ default but making it explicit).

2. Add `android:networkSecurityConfig="@xml/network_security_config"` to the `<application>` tag in `AndroidManifest.xml`.

**Verification:**
- Run: `./gradlew assembleDebug`
- Verify: Build succeeds with the new config

**Notes:**
- Keep it simple: `<network-security-config><base-config cleartextTrafficPermitted="false" /></network-security-config>`

### Task 9: Migrate API key to encrypted storage

**Issue:** HEA-45
**Files:**
- `gradle/libs.versions.toml` (modify — add security-crypto version)
- `app/build.gradle.kts` (modify — add security-crypto dependency)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify if exists)

**TDD Steps:**

1. **RED** — Add/modify tests for `DataStoreSettingsRepository` that verify:
   - API key is stored and retrieved correctly through encrypted storage
   - Migration from plain DataStore to encrypted storage works (key readable after migration)
   - Old plain-text key is removed from DataStore after migration
   - Other settings (baseUrl, syncInterval, lastSyncedDate) remain in DataStore unchanged
   - Run tests — fail because encryption isn't implemented yet

2. **GREEN** — Implementation approach:
   - Add `androidx.security:security-crypto` dependency (version in `libs.versions.toml`)
   - Modify `DataStoreSettingsRepository` to accept an `EncryptedSharedPreferences` instance alongside DataStore
   - Store/retrieve `apiKey` from `EncryptedSharedPreferences` instead of DataStore
   - Implement `apiKeyFlow` using `callbackFlow` that reads from encrypted prefs and listens for changes
   - Add migration logic in repository init: if DataStore has an `api_key` value, move it to encrypted prefs and clear from DataStore
   - Update `AppModule` to provide `EncryptedSharedPreferences` via `MasterKey` and inject into repository

3. **REFACTOR** — Verify the Flow-based interface works reactively: setting a key emits on the flow.

**Defensive Requirements:**
- `MasterKey` creation can fail on devices with broken Keystore — catch and fall back to plain storage with a WARN log
- First-run migration must be atomic: read from DataStore, write to encrypted, verify read-back, then clear from DataStore
- If encrypted prefs already has the key (re-run), skip migration
- Thread safety: migration runs in coroutine scope, guarded by mutex or init block

**Notes:**
- `EncryptedSharedPreferences` uses AES256-SIV for keys and AES256-GCM for values
- `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`
- Reference: [AndroidX Security Crypto docs](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)

### Task 10: Enable R8 for release builds

**Issue:** HEA-44
**Files:**
- `app/build.gradle.kts` (modify)
- `app/proguard-rules.pro` (create)

**Steps:**

1. In `app/build.gradle.kts`, update the release buildType:
   - Set `isMinifyEnabled = true`
   - Set `isShrinkResources = true`
   - Add `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`

2. Create `app/proguard-rules.pro` with rules for:
   - **Kotlin Serialization**: Keep `@Serializable` annotated classes and their serializer companions
   - **Ktor**: Keep engine and plugin classes
   - **Health Connect**: Keep API classes used via reflection
   - **Hilt**: Already includes its own rules via annotation processor
   - **WorkManager**: Keep worker classes referenced by name
   - **DataStore**: Keep preference key classes

3. **Verification:**
   - Run: `./gradlew assembleRelease`
   - Verify: Release APK builds without errors
   - Run: `./gradlew test` (unit tests use debug variant, should still pass)

**Notes:**
- Start with conservative rules (keep more than necessary) and tighten later
- Kotlin serialization requires `-keepclassmembers` for `@Serializable` classes and their `Companion` objects
- Ktor OkHttp engine needs `-dontwarn` rules for optional dependencies

### Task 11: Fix existing test issues

**Issues:** HEA-51, HEA-56, HEA-57, HEA-59, HEA-69, HEA-70
**Files:**
- `app/src/test/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapperTest.kt` (modify — HEA-51)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify — HEA-56)
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncSchedulerTest.kt` (modify — HEA-57, HEA-70)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify — HEA-59)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/MealTypeTest.kt` (modify — HEA-69)

**Steps:**

1. **HEA-51** — In `NutritionRecordMapperTest:162`, change `assertEquals(60, ...)` to `assertEquals(60L, ...)` for explicit Long comparison.

2. **HEA-56** — In `SyncViewModelTest`, remove the test `cannot trigger sync while already syncing` (lines 188-212). The behavior is already properly tested by `cannot trigger sync when isSyncing is true` (lines 215-238) which uses a delay to hold the coroutine and verifies `assertEquals(1, callCount)`.

3. **HEA-57** — In `SyncSchedulerTest`, enhance the `schedulePeriodic clamps interval to minimum 15 minutes` test: capture the `PeriodicWorkRequest` via a slot, then verify the interval. Access `requestSlot.captured.workSpec.intervalDuration` and assert it equals `TimeUnit.MINUTES.toMillis(15)`. If `workSpec` is not accessible, verify by checking that the captured request's tags or constraints match expectations, or use reflection.

4. **HEA-59** — In `FoodScannerApiClientTest`, replace all `capturedUrl!!` and `capturedAuth!!` with `assertNotNull(capturedUrl)` / `assertNotNull(capturedAuth)` followed by assertions on the returned non-null value. Locations: `correctUrlConstruction` test (lines 127-128) and `trailingSlashHandled` test (lines 265-266).

5. **HEA-69** — In `MealTypeTest:3`, change `import org.junit.jupiter.api.Assertions.assertEquals` to `import kotlin.test.assertEquals`.

6. **HEA-70** — In `SyncSchedulerTest`, remove the two redundant test methods:
   - `schedulePeriodic uses correct work name` (lines 83-96) — already asserted in first test at line 43
   - `schedulePeriodic uses UPDATE policy` (lines 98-111) — already asserted in first test at line 44

**Verification:**
- Run: `./gradlew test`
- Verify: All tests pass with fewer test methods (removed duplicates)

### Task 12: Add HealthConnectNutritionRepository error path tests

**Issue:** HEA-58
**Files:**
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectNutritionRepositoryTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add test cases:
   - `writeNutritionRecords returns false when insertRecords throws SecurityException` — mock `HealthConnectClient`, mock `insertRecords` to throw `SecurityException`, assert `writeNutritionRecords` returns `false`
   - `writeNutritionRecords returns false when insertRecords throws general Exception` — same pattern with generic `Exception`
   - `writeNutritionRecords returns true on successful write` — mock `insertRecords` to succeed (returns `InsertRecordsResponse`), assert returns `true`
   - `writeNutritionRecords handles empty entries list` — pass empty list, verify no `insertRecords` call or successful return

   Tests will initially fail because `HealthConnectClient` mocking setup doesn't exist in the test class yet.

2. **GREEN** — Set up MockK mocks for `HealthConnectClient` in the test class. Use `coEvery { healthConnectClient.insertRecords(any()) }` to control behavior. Create repository with the mocked client. Verify results.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.repository.HealthConnectNutritionRepositoryTest"`
   - Verify: All tests pass

**Notes:**
- `HealthConnectClient` is an interface — mock it with `mockk<HealthConnectClient>()`
- `insertRecords` is a suspend function — use `coEvery`/`coThrows`
- `mapToNutritionRecord` needs a valid `FoodLogEntry` and date string — reuse `testEntry` from existing test

### Task 13: Add SyncWorker unit tests

**Issue:** HEA-60
**Files:**
- `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncWorkerTest.kt` (create)

**TDD Steps:**

1. **RED** — Create `SyncWorkerTest` with three tests:
   - `doWork returns success when sync succeeds` — mock `SyncNutritionUseCase` to return `SyncResult.Success(5)`, call `doWork()`, assert `Result.success()`
   - `doWork returns retry when sync errors` — mock to return `SyncResult.Error("fail")`, assert `Result.retry()`
   - `doWork returns failure when needs configuration` — mock to return `SyncResult.NeedsConfiguration`, assert `Result.failure()`

   Tests will fail because the test file doesn't exist yet.

2. **GREEN** — Create the test class. Construct `SyncWorker` directly with:
   - `context: Context = mockk(relaxed = true)`
   - `workerParams: WorkerParameters = mockk(relaxed = true)`
   - `syncNutritionUseCase: SyncNutritionUseCase = mockk()`

   Call `worker.doWork()` in `runTest` and assert the return value.
   - Run: `./gradlew test --tests "com.healthhelper.app.data.sync.SyncWorkerTest"`
   - Verify: All 3 tests pass

**Notes:**
- `SyncWorker` extends `CoroutineWorker` — `doWork()` is a `suspend fun`
- Direct construction bypasses Hilt — that's fine for unit tests
- `ListenableWorker.Result.success()`, `.retry()`, `.failure()` are comparable with `assertEquals`
- MockK relaxed mocks handle the `WorkerParameters` internals that `ListenableWorker` accesses

### Task 14: Add GitHub Actions CI workflow

**Issue:** HEA-71
**Files:**
- `.github/workflows/ci.yml` (create)

**Steps:**

1. Create `.github/workflows/ci.yml` with:
   - **Triggers:** Pull requests targeting `main` (opened, synchronize, reopened). Also trigger on pushes to `main` to keep status current.
   - **Runner:** `ubuntu-latest`
   - **JDK:** Set up JDK 17 using `actions/setup-java@v4` with `distribution: 'temurin'`
   - **Gradle caching:** Use `actions/setup-java`'s built-in `cache: 'gradle'` parameter, or `gradle/actions/setup-gradle@v4` for advanced caching
   - **Android SDK:** Use `android-actions/setup-android@v3` to install the Android SDK
   - **Steps:**
     1. Checkout code
     2. Set up JDK 17
     3. Set up Android SDK
     4. Grant Gradle wrapper execute permission: `chmod +x ./gradlew`
     5. Run unit tests: `./gradlew test`
     6. Build debug APK: `./gradlew assembleDebug`
   - **Test results:** Use `dorny/test-reporter@v1` or `mikepenz/action-junit-report@v4` to publish JUnit XML results from `app/build/test-results/` as PR check annotations

2. **Verification:**
   - Push the workflow file to a branch and open a test PR
   - Verify the workflow triggers and passes
   - Verify test results appear as PR check annotations

**Notes:**
- Keep the workflow simple — single job with sequential steps
- Gradle wrapper is already committed to the repo (`gradlew`, `gradle/wrapper/`)
- Unit tests don't need an Android emulator (they run on JVM)
- `assembleDebug` verifies the full build pipeline without needing a device
- Reference existing workflows in `.github/workflows/` for permission patterns

### Task 15: Integration & Verification

**Issues:** All
**Files:** Various from previous tasks

**Steps:**

1. Run full test suite: `./gradlew test`
2. Build debug APK: `./gradlew assembleDebug`
3. Build release APK: `./gradlew assembleRelease` (verifies R8 rules)
4. Manual verification checklist:
   - [ ] `SyncNutritionUseCase` has no imports from `com.healthhelper.app.data` package
   - [ ] `FoodLogEntry` rejects negative calories in tests
   - [ ] `FoodScannerApiClient` rejects `http://` URLs
   - [ ] `SyncViewModel.triggerSync()` has try/finally
   - [ ] `DataStoreSettingsRepository` uses EncryptedSharedPreferences for API key
   - [ ] `network_security_config.xml` exists and is referenced in manifest
   - [ ] Release build has `isMinifyEnabled = true`
   - [ ] No `org.junit.jupiter.api.Assertions` imports remain in test files
   - [ ] `.github/workflows/ci.yml` exists and runs tests + build on PRs

## MCP Usage During Implementation

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `save_issue` | Move issues to "In Progress" when starting each task, "Done" when complete |
| Linear | `create_comment` | Add implementation notes to issues if needed |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| Negative nutrition values | `IllegalArgumentException` at construction | Task 2 unit tests |
| Malformed lastSyncedDate | Treat as empty, full re-sync | Task 3 unit tests |
| CancellationException in API call | Rethrown, not caught | Task 4 unit tests |
| http:// base URL | `Result.failure` before network call | Task 5 unit tests |
| Exception during triggerSync | isSyncing reset, error shown | Task 6 unit tests |
| Broken Android Keystore (HEA-45) | Fall back to plain DataStore with warning | Task 9 defensive check |
| R8 strips needed classes | ProGuard keep rules prevent it | Task 10 release build test |

## Risks & Open Questions

- [ ] **HEA-45 Keystore compatibility:** Some devices (especially older/rooted) have broken Keystore implementations. Plan includes fallback to plain storage.
- [ ] **HEA-44 ProGuard rules completeness:** R8 rules may need iteration. Initial rules should be conservative (keep more). Tighten after successful release build.
- [ ] **HEA-57 WorkSpec accessibility:** `PeriodicWorkRequest.workSpec.intervalDuration` may be internal API. If inaccessible, the test may need reflection or an alternative verification approach.
- [ ] **HEA-60 CoroutineWorker test setup:** Direct construction with mocked Context/WorkerParameters should work, but if `ListenableWorker` accesses Context in unexpected ways, may need `work-testing` library or Robolectric.

## Scope Boundaries

**In Scope:**
- All 23 valid backlog issues listed above
- Domain architecture fix (FoodLogRepository interface)
- Security hardening (encrypted storage, R8, HTTPS validation, network config)
- Bug fixes (CancellationException, try/finally, date parsing, defaults, log levels)
- Convention cleanup (Timber specifiers, redundant calls, unused flows)
- Test quality improvements (fix assertions, remove duplicates, add coverage)
- CI infrastructure (GitHub Actions workflow for PR test/build gating)

**Out of Scope:**
- HEA-54 (Canceled): API key in UI state — must be there for settings editing
- HEA-55 (Canceled): Server message in SyncWorker — server messages don't reach SyncWorker
- HEA-61 (Canceled): SSL pinning — incompatible with user-configurable base URLs
- HEA-63 (Canceled): HttpClient close — standard Android singleton pattern
- UI/UX changes beyond the specific bug fixes
- New features or enhancements not in the backlog
- Instrumented tests (only unit tests in scope)
