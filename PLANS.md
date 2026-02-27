# Implementation Plan

**Created:** 2026-02-27
**Source:** Inline request: Food Scanner API integration — sync meal data to Health Connect via NutritionRecord, with settings screen and background sync
**Linear Issues:** HEA-TBD, HEA-TBD, HEA-TBD, HEA-TBD, HEA-TBD, HEA-TBD, HEA-TBD, HEA-TBD

## Context Gathered

### Codebase Analysis
- **Current state:** App reads step counts from Health Connect. All steps-related code will be removed.
- **Files to remove:** HealthRecord, HealthRecordType, HealthConnectStatus, PermissionStatus, StepsResult, ReadStepsUseCase, CheckHealthConnectStatusUseCase, HealthConnectStatusProvider, HealthConnectRepositoryImpl, HealthConnectRepository (domain), HealthViewModel, HealthScreen, all 3 test files
- **Files to keep:** HealthHelperApp.kt (Timber init), MainActivity.kt (update for nav), Theme.kt, AppModule.kt (rewrite)
- **Existing patterns:** Constructor injection via @Inject, Hilt modules in di/, StateFlow in ViewModels, trailing commas, UseCase/Repository/Screen/ViewModel suffixes
- **Test conventions:** JUnit 5 + MockK + runTest, coEvery/coVerify for suspend fns, StandardTestDispatcher, Turbine for Flow testing

### Food Scanner API
- **Base URL:** User-configurable (self-hosted)
- **Auth:** Bearer token (`fsk_<64 hex chars>`)
- **Endpoint used:** `GET /api/v1/food-log?date=YYYY-MM-DD` — returns meals grouped by mealTypeId with per-entry nutrition data
- **Rate limit:** 60 req/min (database-only route)
- **Response envelope:** `{ success: bool, data: NutritionSummary, timestamp: number }`

### Health Connect Mapping
- **Record type:** `NutritionRecord` (IntervalRecord) — one per food log entry
- **Mapped fields:** name→name, calories→energy (kilocalories), proteinG→protein (grams), carbsG→totalCarbohydrate (grams), fatG→totalFat (grams), fiberG→dietaryFiber (grams), sodiumMg→sodium (milligrams), saturatedFatG→saturatedFat (grams), transFatG→transFat (grams), sugarsG→sugar (grams), caloriesFromFat→energyFromFat (kilocalories)
- **Meal type mapping:** 1→BREAKFAST, 3→LUNCH, 5→DINNER, 2/4/7→SNACK
- **Idempotent sync:** Use `clientRecordId` derived from food-scanner entry ID
- **Time handling:** `startTime` from entry `time` field + date, `endTime` = startTime + 1 minute

### HTTP Client Decision
- **Ktor Client 3.4.0** — Kotlin-native coroutines, built-in bearerAuth(), first-class kotlinx.serialization, JetBrains-backed, MockEngine for testing
- **Engine:** OkHttp (Android standard)
- **Dependencies:** ktor-client-core, ktor-client-okhttp, ktor-client-content-negotiation, ktor-serialization-kotlinx-json

## Original Plan

### Task 1: Wipe existing steps code and add new dependencies
**Linear Issue:** HEA-TBD

This is an infrastructure task — no TDD. Remove all steps-related code and add dependencies for the new feature.

**Remove files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecord.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthRecordType.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/model/HealthConnectStatus.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/model/PermissionStatus.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/model/StepsResult.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/HealthConnectRepository.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCase.kt`
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCase.kt`
- `app/src/main/kotlin/com/healthhelper/app/data/HealthConnectStatusProvider.kt`
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectRepositoryImpl.kt`
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModel.kt`
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/HealthScreen.kt`
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/ReadStepsUseCaseTest.kt`
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/CheckHealthConnectStatusUseCaseTest.kt`
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/HealthViewModelTest.kt`

**Update `gradle/libs.versions.toml`** — add:
- `ktor = "3.4.0"` with artifacts: ktor-client-core, ktor-client-okhttp, ktor-client-content-negotiation, ktor-serialization-kotlinx-json
- `kotlinx-serialization-json` (for @Serializable DTOs)
- `datastore-preferences` (for settings storage)
- `work-runtime-ktx` (for WorkManager background sync)
- `hilt-work` + `hilt-compiler` for WorkManager Hilt integration
- `navigation-compose` is already present (2.8.5)

**Update `app/build.gradle.kts`** — add:
- `kotlin("plugin.serialization")` plugin (uses AGP's bundled Kotlin 2.2.10 — do NOT specify separate version)
- All new library dependencies
- Keep existing: Compose BOM, Hilt, Health Connect, Timber, JUnit 5, MockK, coroutines-test, Turbine

**Update `app/src/main/AndroidManifest.xml`**:
- Remove `android.permission.health.READ_STEPS`
- Add `android.permission.health.WRITE_NUTRITION`
- Keep ViewPermissionUsageActivity alias

**Gut `di/AppModule.kt`** — remove all existing provides (they reference deleted classes). Leave empty module shell.

**Update `MainActivity.kt`** — replace HealthScreen() with a placeholder Composable (Text("HealthHelper")) so the app compiles.

**Verify:** `./gradlew assembleDebug` succeeds with zero errors. No tests to run (all deleted).

---

### Task 2: Domain models
**Linear Issue:** HEA-TBD

Pure Kotlin domain models. No Android dependencies.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/domain/model/MealTypeTest.kt`:
   - Test mapping of each food-scanner mealTypeId (1, 2, 3, 4, 5, 7) to correct MealType enum value
   - Test unknown mealTypeId returns UNKNOWN
2. Run verifier (expect fail)
3. Create domain models:
   - `app/src/main/kotlin/com/healthhelper/app/domain/model/MealType.kt` — enum with BREAKFAST, LUNCH, DINNER, SNACK, UNKNOWN + companion `fromFoodScannerId(Int): MealType` factory
   - `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogEntry.kt` — data class with: id (Int), foodName (String), mealType (MealType), time (String?, HH:mm:ss), calories (Double), proteinG (Double), carbsG (Double), fatG (Double), fiberG (Double), sodiumMg (Double), saturatedFatG (Double?), transFatG (Double?), sugarsG (Double?), caloriesFromFat (Double?)
   - `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncResult.kt` — sealed class: Success(recordsSynced: Int), Error(message: String), NeedsConfiguration (no API key/URL set)
   - `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncProgress.kt` — data class: currentDate (String), totalDays (Int), completedDays (Int), recordsSynced (Int)
4. Run verifier (expect pass)

---

### Task 3: Settings repository
**Linear Issue:** HEA-TBD

DataStore-backed settings storage. Domain interface + data implementation.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt`:
   - Test storing and retrieving API key
   - Test storing and retrieving base URL
   - Test storing and retrieving sync interval (Int, minutes)
   - Test default values (empty string for key/URL, 10 for interval)
   - Test storing and retrieving last synced date (String, YYYY-MM-DD format)
   - Test `isConfigured()` returns false when API key or base URL is empty, true when both set
   - Use a real DataStore with a test-scoped temporary file (JUnit 5 TempDir) — DataStore is pure Kotlin, no Android deps needed for preferences
2. Run verifier (expect fail)
3. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — interface with: `apiKeyFlow: Flow<String>`, `baseUrlFlow: Flow<String>`, `syncIntervalFlow: Flow<Int>`, `lastSyncedDateFlow: Flow<String>`, `suspend setApiKey(String)`, `suspend setBaseUrl(String)`, `suspend setSyncInterval(Int)`, `suspend setLastSyncedDate(String)`, `suspend isConfigured(): Boolean`
   - `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — implementation using `DataStore<Preferences>`, injected via constructor. Use preference keys: `api_key`, `base_url`, `sync_interval`, `last_synced_date`.
4. Run verifier (expect pass)

---

### Task 4: Food Scanner API client
**Linear Issue:** HEA-TBD

Ktor HTTP client with DTOs and response envelope parsing.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt`:
   - Use Ktor MockEngine to simulate API responses
   - Test successful food-log response deserializes correctly into domain FoodLogEntry list
   - Test Bearer token is sent in Authorization header
   - Test correct URL construction with date parameter
   - Test error response (success: false) returns appropriate error
   - Test 401 response returns auth error
   - Test 429 response returns rate limit error
   - Test network failure returns error
   - Test null/missing optional fields (saturatedFatG, transFatG, sugarsG, caloriesFromFat) handled gracefully
   - Test meal type IDs are correctly mapped to domain MealType via FoodLogEntry construction
2. Run verifier (expect fail)
3. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/data/api/dto/FoodLogResponse.kt` — @Serializable DTOs matching the food-scanner API response: `ApiEnvelope<T>` (success, data, timestamp, error), `NutritionSummaryDto` (date, meals, totals), `MealGroupDto` (mealTypeId, entries, subtotal), `MealEntryDto` (id, foodName, time, calories, proteinG, carbsG, fatG, fiberG, sodiumMg, saturatedFatG, transFatG, sugarsG, caloriesFromFat), `ApiErrorDto` (code, message, details)
   - `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — class with constructor-injected `HttpClient`. Single method: `suspend fun getFoodLog(baseUrl: String, apiKey: String, date: String): Result<List<FoodLogEntry>>`. Internally: GET `$baseUrl/api/v1/food-log?date=$date` with `bearerAuth(apiKey)`, deserialize `ApiEnvelope<NutritionSummaryDto>`, map `MealEntryDto` list → `FoodLogEntry` list (flatten all meal groups, apply `MealType.fromFoodScannerId()`). Error handling: catch `ResponseException` for HTTP errors, `SerializationException` for parse errors, generic Exception for network.
4. Run verifier (expect pass)

---

### Task 5: Health Connect nutrition writer
**Linear Issue:** HEA-TBD

Repository that writes NutritionRecords to Health Connect.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapperTest.kt`:
   - Test mapping FoodLogEntry → NutritionRecord fields: name, energy (kilocalories), protein (grams), totalCarbohydrate (grams), totalFat (grams), dietaryFiber (grams), sodium (milligrams), saturatedFat (grams), transFat (grams), sugar (grams), energyFromFat (kilocalories)
   - Test meal type mapping: BREAKFAST→MEAL_TYPE_BREAKFAST, LUNCH→MEAL_TYPE_LUNCH, DINNER→MEAL_TYPE_DINNER, SNACK→MEAL_TYPE_SNACK, UNKNOWN→MEAL_TYPE_UNKNOWN
   - Test time parsing: "12:30:00" on date "2026-01-15" → correct Instant (local timezone), endTime = startTime + 60s
   - Test null time falls back to noon of the given date
   - Test nullable nutrition fields: null saturatedFatG → null saturatedFat on NutritionRecord
   - Test clientRecordId is set to "foodscanner-{entryId}" for idempotent upsert
   - Note: NutritionRecord is a real Health Connect class — these tests verify the mapper function output. Use the actual NutritionRecord constructor (it's a data class, not mocked).
2. Run verifier (expect fail)
3. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/domain/repository/NutritionRepository.kt` — interface: `suspend fun writeNutritionRecords(date: String, entries: List<FoodLogEntry>): Boolean`
   - `app/src/main/kotlin/com/healthhelper/app/data/repository/NutritionRecordMapper.kt` — pure function `fun mapToNutritionRecord(entry: FoodLogEntry, date: String): NutritionRecord`. Handles: Energy.kilocalories(), Mass.grams(), Mass.milligrams(), MealType int constants, time parsing (LocalTime.parse + LocalDate.parse → ZonedDateTime → Instant), Metadata with clientRecordId = "foodscanner-${entry.id}"
   - `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectNutritionRepository.kt` — implementation of NutritionRepository. Constructor-injected `HealthConnectClient`. Uses mapper to convert entries, calls `client.insertRecords(records)`. Try-catch at boundary, returns Boolean. Uses Timber for logging.
4. Run verifier (expect pass)

---

### Task 6: SyncNutritionUseCase
**Linear Issue:** HEA-TBD

Orchestrates the full sync: fetch from API → write to Health Connect → track progress.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt`:
   - Mock FoodScannerApiClient, NutritionRepository, SettingsRepository
   - Test: not configured (no API key) → returns SyncResult.NeedsConfiguration
   - Test: single day sync — fetches food log, writes to HC, updates lastSyncedDate, returns Success with count
   - Test: multi-day sync — syncs from today backwards, calls API for each date, aggregates record count
   - Test: API error on one day doesn't stop sync of other days (continues, reports partial success)
   - Test: HC write failure on one day doesn't stop other days
   - Test: empty food log for a date (no entries) → skips HC write, still counts as synced
   - Test: respects lastSyncedDate — doesn't re-sync dates already synced (except today which always re-syncs)
   - Test: caps at 365 days back from today
   - Test: emits SyncProgress updates via a callback/Flow parameter
2. Run verifier (expect fail)
3. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` — class with @Inject constructor taking FoodScannerApiClient, NutritionRepository, SettingsRepository. Method: `suspend fun invoke(onProgress: (SyncProgress) -> Unit = {}): SyncResult`. Logic:
     - Check isConfigured() → NeedsConfiguration if false
     - Collect current apiKey, baseUrl, lastSyncedDate from settings
     - Calculate date range: today down to max(lastSyncedDate + 1 day, today - 365 days). Always include today.
     - Iterate dates newest-first. For each date: call apiClient.getFoodLog(), on success call nutritionRepo.writeNutritionRecords(), emit progress
     - After each successful date (not today), update lastSyncedDate if this date is older than current marker
     - On completion, return Success(totalRecordsSynced) or Error if zero days succeeded
     - Add small delay between API calls (500ms) to stay well under 60 req/min rate limit
4. Run verifier (expect pass)

---

### Task 7: Settings and sync UI
**Linear Issue:** HEA-TBD

ViewModels and Compose screens for settings configuration and sync control.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt`:
   - Mock SettingsRepository
   - Test: initial state loads current settings from repository flows
   - Test: updateApiKey() calls repository.setApiKey()
   - Test: updateBaseUrl() calls repository.setBaseUrl()
   - Test: updateSyncInterval() calls repository.setSyncInterval()
   - Test: isConfigured state reflects repository.isConfigured()
2. Write tests in `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt`:
   - Mock SyncNutritionUseCase, SettingsRepository
   - Test: initial state shows idle, not syncing
   - Test: triggerSync() sets isSyncing=true, calls use case, sets isSyncing=false on completion
   - Test: sync success updates lastSyncResult with record count
   - Test: sync error updates lastSyncResult with error message
   - Test: sync NeedsConfiguration updates state accordingly
   - Test: progress updates are reflected in UI state
   - Test: cannot trigger sync while already syncing
3. Run verifier (expect fail)
4. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` — @HiltViewModel with SettingsRepository. UiState: apiKey, baseUrl, syncInterval, isConfigured. Methods: updateApiKey(), updateBaseUrl(), updateSyncInterval(). Collects settings flows into StateFlow.
   - `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` — @HiltViewModel with SyncNutritionUseCase, SettingsRepository. UiState: isSyncing, lastSyncResult (String?), syncProgress (SyncProgress?), isConfigured, lastSyncedDate. Method: triggerSync(). Collects isConfigured and lastSyncedDate from settings.
   - `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` — Composable: OutlinedTextField for base URL, OutlinedTextField for API key (visualTransformation = PasswordVisualTransformation for masking, with toggle visibility icon), dropdown or stepper for sync interval (minutes), "Save" implicit (write on change via debounce or on focus loss). Back navigation.
   - `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` — Composable: Shows sync status (idle/syncing/last result), progress bar during sync (completedDays/totalDays), "Sync Now" button (disabled when syncing or not configured), last synced date display, settings icon/button to navigate to SettingsScreen. If not configured, show a message prompting to configure API in settings.
   - `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` — NavHost with two routes: "sync" (startDestination) and "settings". Wire navigation between screens.
   - Update `MainActivity.kt` — replace placeholder with `AppNavigation()` inside HealthHelperTheme.
5. Run verifier (expect pass)

---

### Task 8: Background sync with WorkManager and final wiring
**Linear Issue:** HEA-TBD

WorkManager periodic sync, Hilt DI wiring, and permission handling.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/data/sync/SyncSchedulerTest.kt`:
   - Mock WorkManager
   - Test: schedulePeriodic() enqueues PeriodicWorkRequest with correct interval
   - Test: cancelSync() cancels work by unique name
   - Test: updateInterval() cancels existing and re-enqueues with new interval
2. Run verifier (expect fail)
3. Implement:
   - `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncWorker.kt` — @HiltWorker class extending CoroutineWorker. Injects SyncNutritionUseCase via @AssistedInject. doWork(): calls syncUseCase(), returns Result.success() or Result.retry() on transient error, Result.failure() on permanent error. Uses Timber for logging.
   - `app/src/main/kotlin/com/healthhelper/app/data/sync/SyncScheduler.kt` — class with @Inject constructor taking WorkManager. Methods: `fun schedulePeriodic(intervalMinutes: Int)` — enqueues PeriodicWorkRequestBuilder with ExistingPeriodicWorkPolicy.UPDATE, unique work name "nutrition_sync", constraint: NetworkType.CONNECTED. `fun cancelSync()`. `fun updateInterval(minutes: Int)` — calls schedulePeriodic.
   - `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` — rewrite completely. Provides: SettingsRepository (DataStoreSettingsRepository), NutritionRepository (HealthConnectNutritionRepository), HttpClient (Ktor with OkHttp engine + ContentNegotiation + Json), FoodScannerApiClient, HealthConnectClient (via HealthConnectClient.getOrCreate(context)), WorkManager (via WorkManager.getInstance(context)), SyncScheduler, DataStore<Preferences>.
   - `app/src/main/kotlin/com/healthhelper/app/di/WorkerModule.kt` — Hilt module for WorkManager initialization. Use `@InstallIn(SingletonComponent::class)` with HiltWorkerFactory binding.
   - Update `HealthHelperApp.kt` — implement `Configuration.Provider` for Hilt WorkManager integration, override `workManagerConfiguration` property.
   - Update `SyncViewModel` — inject SyncScheduler, call schedulePeriodic() when sync interval changes, call it on init if configured.
   - Update `AndroidManifest.xml` — add `FOREGROUND_SERVICE` permission if needed for long syncs (evaluate if needed), ensure `WRITE_NUTRITION` permission is present. Remove the default WorkManager initializer (add `<provider>` removal for `androidx.startup`).
   - Add runtime permission request for `WRITE_NUTRITION` in SyncScreen — use `PermissionController.createRequestPermissionResultContract()`, request on first sync attempt.
4. Run verifier (expect pass)

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review all changes for bugs, security issues, coroutine problems
2. Run `verifier` agent — Verify all tests pass, build succeeds, zero warnings
3. Manual verification: Install on device, configure API key/URL, trigger sync, verify NutritionRecords appear in Health Connect app

---

## Plan Summary

**Objective:** Replace the steps-reading app with a food-scanner-to-Health-Connect nutrition sync app

**Request:** Wipe existing steps code. Add settings screen for API key and base URL. Integrate with food-scanner's /api/v1/food-log endpoint via Ktor Client. Map meal entries to Health Connect NutritionRecords and write them. Support manual sync + configurable background sync (default 10 min). Sync up to 1 year of history. Write-only — no Health Connect reads. Ignore goals endpoints.

**Linear Issues:** HEA-TBD (8 issues to be created)

**Approach:** Bottom-up TDD implementation. First wipe old code and add dependencies (Ktor 3.4.0, DataStore, WorkManager, kotlinx-serialization). Then build domain models, settings storage, API client, and HC writer independently. Wire them together in SyncNutritionUseCase. Build UI with two screens (sync status + settings) using Compose Navigation. Finally add WorkManager for background sync.

**Scope:**
- Tasks: 8
- Files affected: ~25 (15 deleted, ~20 new, ~5 modified)
- New tests: yes (8+ test files)

**Key Decisions:**
- Ktor Client 3.4.0 over Retrofit — Kotlin-native coroutines, built-in bearerAuth, first-class kotlinx.serialization, MockEngine for testing
- One NutritionRecord per food log entry (not per meal or per day) — Health Connect aggregates automatically
- clientRecordId = "foodscanner-{entryId}" for idempotent upsert — prevents duplicates on re-sync
- Sync newest-first (today → backwards) so recent data appears immediately
- Today always re-synced (food may be added throughout the day), past dates synced once
- 500ms delay between API calls to stay well under 60 req/min rate limit
- DataStore for settings (not Room) — simple key-value storage is sufficient

**Risks/Considerations:**
- First full sync of 365 days requires ~365 API calls (~6 minutes at 60/min with throttling). Progress UI is essential.
- Health Connect WRITE_NUTRITION permission requires runtime request — must handle denied state gracefully
- Ktor 3.4.0 compatibility with AGP 9 / Kotlin 2.2.10 should be verified during Task 1 (dependency resolution)
- kotlinx-serialization plugin must use AGP's bundled Kotlin version — do NOT specify a separate version
- WorkManager minimum interval is 15 minutes — if user sets 10 min, we need to document this Android limitation or enforce 15 min minimum
