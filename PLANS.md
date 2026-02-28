# Implementation Plan

**Created:** 2026-02-28
**Source:** Inline request: Blood Pressure Scanner — photograph BP monitor, extract readings via Claude Haiku, write BloodPressureRecord to Health Connect
**Linear Issues:** [HEA-98](https://linear.app/lw-claude/issue/HEA-98), [HEA-99](https://linear.app/lw-claude/issue/HEA-99), [HEA-100](https://linear.app/lw-claude/issue/HEA-100), [HEA-101](https://linear.app/lw-claude/issue/HEA-101), [HEA-102](https://linear.app/lw-claude/issue/HEA-102), [HEA-103](https://linear.app/lw-claude/issue/HEA-103), [HEA-104](https://linear.app/lw-claude/issue/HEA-104), [HEA-105](https://linear.app/lw-claude/issue/HEA-105), [HEA-106](https://linear.app/lw-claude/issue/HEA-106), [HEA-107](https://linear.app/lw-claude/issue/HEA-107), [HEA-108](https://linear.app/lw-claude/issue/HEA-108), [HEA-109](https://linear.app/lw-claude/issue/HEA-109)

## Context Gathered

### Codebase Analysis
- **Health Connect pattern:** `HealthConnectNutritionRepository` writes records via `healthConnectClient.insertRecords()`, returns Boolean, catches SecurityException and CancellationException — follow this exactly for BP records
- **API client pattern:** `FoodScannerApiClient` (Ktor-based, `HttpClient` with OkHttp engine, 30s timeout, kotlinx-serialization) — follow for Anthropic API client
- **Record mapper pattern:** `NutritionRecordMapper.kt` — standalone `mapTo*Record()` function, uses `Metadata.manualEntry(clientRecordId = "...")`, handles time zones
- **Settings pattern:** API key in encrypted `SharedPreferences` via `ENCRYPTED_API_KEY`, non-sensitive settings in `DataStore` — follow for Anthropic API key
- **ViewModel pattern:** `SyncViewModel` uses `MutableStateFlow` + `StateFlow`, `viewModelScope.launch`, collects repository flows in `init`
- **Screen pattern:** `SyncScreen` uses `Scaffold` + `Column`, `collectAsStateWithLifecycle()`, `hiltViewModel()`
- **Navigation:** `AppNavigation.kt` — `NavHost` with string routes ("sync", "settings"), simple `composable()` blocks
- **Test pattern:** JUnit 5 + MockK + Turbine, `viewModelTest` wrapper cancels `viewModelScope`, `StandardTestDispatcher`, `advanceTimeBy`
- **Domain model pattern:** `FoodLogEntry` — data class with `init` block `require()` validation
- **Existing permissions:** Only `WRITE_NUTRITION` and `INTERNET` in AndroidManifest
- **No CameraX or Anthropic dependencies exist** — need to add both

### MCP Context
- **MCPs used:** Linear (Health Helper team, ID `7b911426-efe2-48cb-93a4-4d69cd4592a6`)
- **Findings:** No existing BP-related Linear issues. No glucose scanner issues either — all scanner features are in ROADMAP.md only.

## Original Plan

### Task 1: Add CameraX dependencies and camera feature declaration
**Linear Issue:** [HEA-98](https://linear.app/lw-claude/issue/HEA-98)

1. Add CameraX version (`1.5.1` or latest stable) to `gradle/libs.versions.toml` under `[versions]`
2. Add library entries under `[libraries]`: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose`
3. Add `implementation` dependencies to `app/build.gradle.kts`
4. Add `<uses-feature android:name="android.hardware.camera" android:required="false" />` to `app/src/main/AndroidManifest.xml` — `required="false"` because the app functions without camera (nutrition sync still works)
5. Run verifier — build must compile successfully

**Notes:**
- CameraX `camera-compose` provides `CameraXViewfinder` composable for Compose-native preview (no `AndroidView` wrapper needed)
- All CameraX artifacts should use the same version
- `camera-camera2` is the Camera2 implementation backend

---

### Task 2: BloodPressureReading domain model and enums
**Linear Issue:** [HEA-99](https://linear.app/lw-claude/issue/HEA-99)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/BodyPosition.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/MeasurementLocation.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/BloodPressureReading.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/BloodPressureParseResult.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/BloodPressureReadingTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - Valid reading (120, 80) with defaults succeeds
   - Systolic below 60 throws `IllegalArgumentException`
   - Systolic above 300 throws `IllegalArgumentException`
   - Diastolic below 30 throws `IllegalArgumentException`
   - Diastolic above 200 throws `IllegalArgumentException`
   - Systolic <= diastolic throws `IllegalArgumentException`
   - All `BodyPosition` enum values exist: STANDING_UP, SITTING_DOWN, LYING_DOWN, RECLINING, UNKNOWN
   - All `MeasurementLocation` enum values exist: LEFT_UPPER_ARM, RIGHT_UPPER_ARM, LEFT_WRIST, RIGHT_WRIST, UNKNOWN
   - `BloodPressureParseResult.Success` holds systolic and diastolic integers
   - `BloodPressureParseResult.Error` holds message string
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - `BodyPosition` enum with values matching Health Connect constants
   - `MeasurementLocation` enum with values matching Health Connect constants
   - `BloodPressureReading` data class: `systolic: Int`, `diastolic: Int`, `bodyPosition: BodyPosition = BodyPosition.UNKNOWN`, `measurementLocation: MeasurementLocation = MeasurementLocation.UNKNOWN`, `timestamp: java.time.Instant = Instant.now()`
   - `init` block with `require(systolic in 60..300)`, `require(diastolic in 30..200)`, `require(systolic > diastolic)`
   - `BloodPressureParseResult` sealed class: `Success(systolic: Int, diastolic: Int)`, `Error(message: String)`
   - Follow validation pattern from `FoodLogEntry.kt`
   - Run verifier (expect pass)

**Defensive Requirements:**
- Domain models must be pure Kotlin (no Android imports) per CLAUDE.md
- `timestamp` uses `java.time.Instant` (pure Java, not Android-specific)

---

### Task 3: Blood pressure Health Connect repository
**Linear Issue:** [HEA-100](https://linear.app/lw-claude/issue/HEA-100)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodPressureRepository.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepository.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/BloodPressureRecordMapper.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepositoryTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/BloodPressureRecordMapperTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - Repository: `writeBloodPressureRecord` returns false when `HealthConnectClient` is null
   - Repository: `writeBloodPressureRecord` returns true on successful insert
   - Repository: `writeBloodPressureRecord` returns false on `SecurityException`
   - Repository: `writeBloodPressureRecord` returns false on general `Exception`
   - Repository: `CancellationException` propagates (not caught)
   - Repository: `getLastReading` returns null when `HealthConnectClient` is null
   - Repository: `getLastReading` returns null when no records exist
   - Repository: `getLastReading` returns most recent reading
   - Repository: `getLastReading` returns null on exception (does not throw)
   - Mapper: maps `BloodPressureReading` to `BloodPressureRecord` with correct systolic/diastolic `Pressure.millimetersOfMercury()` values
   - Mapper: maps `BodyPosition` enum values to Health Connect `BODY_POSITION_*` constants
   - Mapper: maps `MeasurementLocation` enum values to Health Connect `MEASUREMENT_LOCATION_*` constants
   - Mapper: sets `Metadata.manualEntry()` with clientRecordId prefix `"bloodpressure-"`
   - Mapper: sets time and zoneOffset from the reading's timestamp
   - Follow test pattern from `HealthConnectNutritionRepositoryTest.kt`
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - `BloodPressureRepository` interface: `suspend fun writeBloodPressureRecord(reading: BloodPressureReading): Boolean`, `suspend fun getLastReading(): BloodPressureReading?`
   - `HealthConnectBloodPressureRepository` with `@Inject constructor(healthConnectClient: HealthConnectClient?)` — follow `HealthConnectNutritionRepository` pattern exactly
   - `getLastReading()`: use `healthConnectClient.readRecords(ReadRecordsRequest(BloodPressureRecord::class, TimeRangeFilter.after(...)))`, sort by time descending, take first, map back to domain model
   - `mapToBloodPressureRecord()` function — follow `NutritionRecordMapper.kt` pattern
   - Reverse mapper from `BloodPressureRecord` to `BloodPressureReading` for `getLastReading()`
   - Add `provideBloodPressureRepository` to `AppModule.kt` — `@Provides @Singleton`
   - Run verifier (expect pass)

**Defensive Requirements:**
- `getLastReading()` must catch all exceptions (including SecurityException) and return null — do not let read failures crash the app
- Use `TimeRangeFilter.after(Instant.now().minus(30, ChronoUnit.DAYS))` for the read query — Health Connect limits reads to 30 days
- Timeout: `readRecords` call should be wrapped in `withTimeout(10_000L)` to prevent blocking on HC IPC

---

### Task 4: Anthropic API key in settings
**Linear Issue:** [HEA-101](https://linear.app/lw-claude/issue/HEA-101)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModel.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SettingsScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepositoryTest.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SettingsViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - Repository: `anthropicApiKeyFlow` emits empty string by default
   - Repository: `setAnthropicApiKey` stores value and emits it
   - ViewModel: initial state has empty `anthropicApiKey`
   - ViewModel: `updateAnthropicApiKey` sets `hasUnsavedChanges` to true
   - ViewModel: `save()` calls `settingsRepository.setAnthropicApiKey()`
   - ViewModel: `reset()` restores persisted anthropicApiKey
   - All existing tests that mock `SettingsRepository` must add: `every { settingsRepository.anthropicApiKeyFlow } returns flowOf("")`
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - Add to `SettingsRepository` interface: `val anthropicApiKeyFlow: Flow<String>`, `suspend fun setAnthropicApiKey(value: String)`
   - Add to `DataStoreSettingsRepository`: `ENCRYPTED_ANTHROPIC_KEY = "anthropic_api_key"` constant, `anthropicApiKeyFlow` using `callbackFlow` on `encryptedPrefs` (same pattern as `apiKeyFlow`), `setAnthropicApiKey` via `encryptedPrefs.edit().putString().commit()`
   - Add `anthropicApiKey: String = ""` to `SettingsUiState`
   - Add to `SettingsViewModel`: `updateAnthropicApiKey()` function, include in `save()` and `reset()`, add to `PersistedSettings`, include `anthropicApiKeyFlow` in the `combine` collector
   - Add `OutlinedTextField` to `SettingsScreen` for "Anthropic API Key" with same password visibility toggle pattern as existing API Key field
   - Run verifier (expect pass)

**Defensive Requirements:**
- Anthropic API key stored in `EncryptedSharedPreferences` (same as Food Scanner API key) — never in plaintext DataStore
- `callbackFlow` must register/unregister `OnSharedPreferenceChangeListener` — follow `apiKeyFlow` pattern exactly
- `save()` must handle `setAnthropicApiKey` failure independently of other fields (same try-catch pattern as existing save)

**Notes:**
- All test files mocking `SettingsRepository` need the new flow mock added: `SyncViewModelTest`, `SyncNutritionUseCaseTest`, `SettingsViewModelTest`, `DataStoreSettingsRepositoryTest`
- Reference: `DataStoreSettingsRepository.kt:80-94` for `apiKeyFlow` pattern, `SettingsViewModel.kt:90-135` for save pattern

---

### Task 5: AnthropicApiClient for blood pressure image analysis
**Linear Issue:** [HEA-102](https://linear.app/lw-claude/issue/HEA-102)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/AnthropicApiClient.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/data/api/dto/AnthropicDtos.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/AnthropicApiClientTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - Returns `BloodPressureParseResult.Success(120, 80)` when Haiku responds with valid systolic/diastolic
   - Returns `BloodPressureParseResult.Error` when Haiku response indicates unreadable display
   - Returns `BloodPressureParseResult.Error` on HTTP 401 (bad API key)
   - Returns `BloodPressureParseResult.Error` on HTTP 429 (rate limited)
   - Returns `BloodPressureParseResult.Error` on network timeout
   - Returns `BloodPressureParseResult.Error` on malformed response JSON
   - Validates parsed systolic is in 60-300 range, diastolic in 30-200, systolic > diastolic — otherwise returns Error
   - CancellationException propagates
   - Use `ktor-client-mock` (already in test deps) to mock HTTP responses — follow `FoodScannerApiClientTest.kt` pattern
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - Create `@Serializable` DTO classes for Anthropic Messages API request/response in `AnthropicDtos.kt`
   - `AnthropicApiClient` with `@Inject constructor(httpClient: HttpClient)`:
     - `suspend fun parseBloodPressureImage(apiKey: String, imageBytes: ByteArray): BloodPressureParseResult`
     - POST to `https://api.anthropic.com/v1/messages`
     - Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`
     - Body: model `claude-haiku-4-5-20251001`, max_tokens 256, messages with image (base64-encoded JPEG) and text prompt
     - System prompt instructs Haiku to: identify systolic (largest/topmost number), diastolic (middle number), ignore pulse/heart rate, return JSON `{"systolic": N, "diastolic": N}` or `{"error": "reason"}`
     - Parse response text as JSON, validate ranges, return `BloodPressureParseResult`
   - Add `provideAnthropicApiClient` to `AppModule.kt`
   - Run verifier (expect pass)

**Defensive Requirements:**
- API key must never be logged — only log "Authentication failed" on 401, not the key value
- Timeout: 30s (inherited from existing HttpClient config in AppModule)
- CancellationException must propagate (follow `FoodScannerApiClient.kt` pattern)
- Error messages returned to caller must be sanitized (generic user-facing text, raw error logged via Timber only)
- Base64 encoding of image bytes should happen inside the client
- Validate response JSON defensively — malformed/missing fields return Error, not crash

**Notes:**
- The existing `HttpClient` in AppModule has `ContentNegotiation` with `Json { ignoreUnknownKeys = true }` and `HttpTimeout { requestTimeoutMillis = 30_000L }` — reuse for Anthropic calls
- Reference: `FoodScannerApiClient.kt` for error handling patterns, `FoodScannerApiClientTest.kt` for Ktor mock client testing
- Image should be resized to max 1568px on long edge before sending (reduces tokens and latency without losing accuracy)

---

### Task 6: Blood pressure use cases
**Linear Issue:** [HEA-103](https://linear.app/lw-claude/issue/HEA-103)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCase.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/GetLastBloodPressureReadingUseCase.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCaseTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/GetLastBloodPressureReadingUseCaseTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests for `WriteBloodPressureReadingUseCase`:
   - Returns true when repository write succeeds
   - Returns false when repository write fails
   - Passes the `BloodPressureReading` domain model to repository
   - Test with various valid readings (different body positions, measurement locations)
   - Run verifier (expect fail)

2. **GREEN** — Implement `WriteBloodPressureReadingUseCase`:
   - `@Inject constructor(bloodPressureRepository: BloodPressureRepository)`
   - `suspend fun invoke(reading: BloodPressureReading): Boolean` — delegates to `repository.writeBloodPressureRecord(reading)`
   - Run verifier (expect pass)

3. **RED** — Write tests for `GetLastBloodPressureReadingUseCase`:
   - Returns reading when repository has data
   - Returns null when repository returns null
   - Returns null when repository throws (does not propagate exception)
   - Run verifier (expect fail)

4. **GREEN** — Implement `GetLastBloodPressureReadingUseCase`:
   - `@Inject constructor(bloodPressureRepository: BloodPressureRepository)`
   - `suspend fun invoke(): BloodPressureReading?` — delegates to `repository.getLastReading()`, wraps in try-catch returning null on failure
   - Run verifier (expect pass)

**Defensive Requirements:**
- `GetLastBloodPressureReadingUseCase` must not throw — catch all exceptions, log with Timber, return null
- Both use cases are pure domain layer — no Android imports

---

### Task 7: Update AndroidManifest permissions and upfront permission request
**Linear Issue:** [HEA-104](https://linear.app/lw-claude/issue/HEA-104)

**Files:**
- `app/src/main/AndroidManifest.xml` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - ViewModel: permission check on init verifies ALL Health Connect permissions (WRITE_NUTRITION, WRITE_BLOOD_PRESSURE, READ_BLOOD_PRESSURE)
   - ViewModel: `permissionGranted` is true only when ALL required HC permissions are granted
   - ViewModel: `cameraPermissionGranted` field tracks camera permission separately
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - Add to `AndroidManifest.xml`: `<uses-permission android:name="android.permission.health.WRITE_BLOOD_PRESSURE" />`, `<uses-permission android:name="android.permission.health.READ_BLOOD_PRESSURE" />`, `<uses-permission android:name="android.permission.CAMERA" />`
   - Update `SyncViewModel` companion object: define all required HC permissions as a `Set` (WRITE_NUTRITION, WRITE_BLOOD_PRESSURE, READ_BLOOD_PRESSURE)
   - Update permission check in `SyncViewModel.init`: check all HC permissions are granted (current check only verifies WRITE_NUTRITION)
   - Add `cameraPermissionGranted: Boolean = false` to `SyncUiState`
   - Update `SyncScreen`: request ALL HC permissions at once via `permissionLauncher.launch(allHcPermissions)`, add separate camera permission launcher using `ActivityResultContracts.RequestPermission()` for `CAMERA`, trigger both on first launch via `LaunchedEffect`
   - Update `onPermissionResult` to accept the full set of granted permissions and check all required ones
   - Run verifier (expect pass)

**Defensive Requirements:**
- If any HC permission is denied, show which permissions are still needed
- Camera permission failure should not block the rest of the app — only the "Log Blood Pressure" button should be disabled
- Permission check in init must handle `getGrantedPermissions()` throwing — leave all permissions false on failure (existing pattern)
- Both permission dialogs should show on first launch: HC permissions first, then camera permission

**Notes:**
- Health Connect permissions use `PermissionController.createRequestPermissionResultContract()` — supports requesting multiple HC permissions in one call
- Camera permission uses standard Android `ActivityResultContracts.RequestPermission()`
- Reference: `SyncScreen.kt:50-54` for current permission launcher, `SyncViewModel.kt:70-85` for current permission check

---

### Task 8: Restructure home screen with fixed sections and last BP reading
**Linear Issue:** [HEA-105](https://linear.app/lw-claude/issue/HEA-105)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - ViewModel: `lastBpReading` is null by default
   - ViewModel: `lastBpReading` populated after init loads from `GetLastBloodPressureReadingUseCase`
   - ViewModel: `lastBpReadingDisplay` formats as "120/80 mmHg" when reading exists
   - ViewModel: `lastBpReadingTime` formats as relative time (e.g., "2 hr ago") when reading exists
   - ViewModel: `refreshLastBpReading()` reloads from use case (called after returning from BP flow)
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - Add `GetLastBloodPressureReadingUseCase` as dependency to `SyncViewModel` constructor
   - Add to `SyncUiState`: `lastBpReading: BloodPressureReading? = null`, `lastBpReadingDisplay: String = ""`, `lastBpReadingTime: String = ""`
   - In `SyncViewModel.init`, launch coroutine to load last BP reading and format for display
   - Add `refreshLastBpReading()` function that re-loads the last reading
   - Restructure `SyncScreen` layout:
     - Wrap content in `Column` with `verticalScroll(rememberScrollState())`
     - **Section 1: Nutrition Sync** — wrap existing sync content in a `Card` or `Surface` with `Modifier.fillMaxWidth()` and a minimum height. Include: permission status, configuration status, last sync time, next sync time, sync result, recent meals, sync button
     - **Section 2: Blood Pressure** — new `Card` or `Surface` with `Modifier.fillMaxWidth()` and minimum height. Include: last reading display ("120/80 mmHg · Sitting · 2 hr ago") or "No readings yet" placeholder, "Log Blood Pressure" button (enabled when HC permissions + camera permission granted)
     - Both sections always visible with fixed vertical space allocation, even when empty
   - Run verifier (expect pass)

**Defensive Requirements:**
- `GetLastBloodPressureReadingUseCase` is nullable in practice (returns null if no readings) — handle gracefully
- Periodic refresh of `lastBpReadingTime` reuses existing `formatRelativeTime()` helper
- "Log Blood Pressure" button enabled only when `permissionGranted && cameraPermissionGranted` — both HC and camera permissions required

**Notes:**
- The `SyncViewModel` constructor grows by one dependency (`GetLastBloodPressureReadingUseCase`) — update all test `createViewModel()` helpers
- Consider `Modifier.defaultMinSize(minHeight = X.dp)` for section cards to maintain fixed space
- Reference: `SyncScreen.kt` for existing layout, `SyncViewModel.kt` for existing state management

---

### Task 9: Camera capture screen with CameraX and Haiku integration
**Linear Issue:** [HEA-106](https://linear.app/lw-claude/issue/HEA-106)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModelTest.kt` (create)

**TDD Steps:**

1. **RED** — Write ViewModel tests:
   - Initial state: `isCapturing = false`, `isProcessing = false`, `error = null`
   - `onPhotoCaptured(bytes)` sets `isProcessing = true`, calls `AnthropicApiClient`
   - On `BloodPressureParseResult.Success`: sets `navigateToConfirmation` event with systolic/diastolic values
   - On `BloodPressureParseResult.Error`: sets `error` with message, `isProcessing = false`
   - `onRetake()` clears error, returns to camera state
   - `onPhotoCaptured` while already processing is ignored (guard)
   - API call failure (network error) shows generic error message, raw error logged
   - CancellationException propagates
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - `CameraCaptureUiState` data class: `isProcessing: Boolean = false`, `error: String? = null`
   - `CameraCaptureViewModel` with `@Inject constructor(anthropicApiClient: AnthropicApiClient, settingsRepository: SettingsRepository)`
   - Navigation event: `navigateToConfirmation: SharedFlow<Pair<Int, Int>>` (systolic, diastolic) — one-shot event via `MutableSharedFlow`
   - `onPhotoCaptured(imageBytes: ByteArray)`: guard on not already processing, set processing, launch coroutine to call `anthropicApiClient.parseBloodPressureImage(apiKey, imageBytes)`, handle result
   - `onRetake()`: clear error state
   - `CameraCaptureScreen` composable:
     - CameraX preview using `CameraXViewfinder` composable (or `PreviewView` via `AndroidView` if `camera-compose` API differs)
     - `ImageCapture` use case for photo capture
     - Shutter button at bottom
     - When processing: overlay with loading indicator
     - When error: error message + "Retake" button
     - Collect `navigateToConfirmation` flow for navigation
   - Run verifier (expect pass)

**Defensive Requirements:**
- API key read from `settingsRepository.anthropicApiKeyFlow` — must be non-empty before calling API. If empty, show "Configure Anthropic API key in Settings"
- Image bytes should be resized before sending to API (max 1568px on long edge) to control token cost
- `isProcessing` guard prevents double-tap of shutter sending two API calls
- Error messages shown to user must be generic — raw API errors logged via Timber only
- CameraX lifecycle must be properly bound to the composable's lifecycle

**Notes:**
- `CameraX` setup: `ProcessCameraProvider` → bind `Preview` + `ImageCapture` use cases
- `ImageCapture.takePicture()` with `OutputFileOptions` for temp file, then read bytes
- Navigation event pattern: `MutableSharedFlow<T>(replay = 0, extraBufferCapacity = 1)` with `tryEmit()` — one-shot navigation events

---

### Task 10: Blood pressure confirmation screen
**Linear Issue:** [HEA-107](https://linear.app/lw-claude/issue/HEA-107)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/BpConfirmationViewModel.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/BpConfirmationScreen.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/BpConfirmationViewModelTest.kt` (create)

**TDD Steps:**

1. **RED** — Write ViewModel tests:
   - Initial state populated from constructor args (systolic, diastolic)
   - `updateSystolic(value)` updates state, validates range 60-300
   - `updateDiastolic(value)` updates state, validates range 30-200
   - `isSaveEnabled` is false when systolic <= diastolic
   - `isSaveEnabled` is false when systolic out of range
   - `isSaveEnabled` is false when diastolic out of range
   - `isSaveEnabled` is true when all validations pass
   - `updateBodyPosition(position)` updates state
   - `updateMeasurementLocation(location)` updates state
   - `save()` calls `WriteBloodPressureReadingUseCase` with correct domain model
   - `save()` on success emits `navigateHome` event
   - `save()` on failure sets error message
   - `save()` while already saving is ignored (guard)
   - Run verifier (expect fail)

2. **GREEN** — Implement:
   - `BpConfirmationUiState` data class: `systolic: String`, `diastolic: String`, `bodyPosition: BodyPosition = UNKNOWN`, `measurementLocation: MeasurementLocation = UNKNOWN`, `timestamp: Instant = Instant.now()`, `isSaveEnabled: Boolean`, `isSaving: Boolean = false`, `error: String? = null`, `validationError: String? = null`
   - `BpConfirmationViewModel` with `@HiltViewModel @Inject constructor(savedStateHandle: SavedStateHandle, writeBloodPressureReadingUseCase: WriteBloodPressureReadingUseCase)`
   - Read systolic/diastolic from `savedStateHandle` (navigation arguments)
   - Validation: parse string to Int, check ranges, check systolic > diastolic — update `isSaveEnabled` and `validationError` reactively
   - `save()`: guard on not already saving, construct `BloodPressureReading`, call use case, emit `navigateHome` on success
   - Navigation event: `navigateHome: SharedFlow<String>` emitting snackbar message like "120/80 mmHg saved"
   - `BpConfirmationScreen` composable:
     - Scaffold with TopAppBar "Confirm Reading"
     - Systolic and Diastolic as `OutlinedTextField` with number keyboard type (`KeyboardType.Number`)
     - Body Position dropdown using `ExposedDropdownMenuBox`
     - Measurement Location dropdown using `ExposedDropdownMenuBox`
     - Timestamp display (default now, tappable to edit — use `DatePickerDialog` + `TimePickerDialog` or simplified display)
     - Validation error text when present
     - Row with "Cancel" (`OutlinedButton`) and "Save" (`Button`, enabled by `isSaveEnabled && !isSaving`)
     - Cancel navigates back to home
   - Run verifier (expect pass)

**Defensive Requirements:**
- String-to-Int parsing must handle non-numeric input gracefully — show validation error, not crash
- `save()` guard prevents double-tap writing duplicate records
- Error on save shows generic message ("Failed to save reading"), raw error logged via Timber
- Timestamp defaults to `Instant.now()` at the time the screen loads, not at save time

**Notes:**
- Use `SavedStateHandle` to receive navigation args — matches Compose Navigation pattern
- Dropdowns: `BodyPosition.entries` and `MeasurementLocation.entries` for enum values, format display name as `name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }`
- Reference: `SettingsScreen.kt` for `OutlinedTextField` patterns

---

### Task 11: Navigation integration and wiring
**Linear Issue:** [HEA-108](https://linear.app/lw-claude/issue/HEA-108)

**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)

**TDD Steps:**

1. **RED** — Write tests:
   - ViewModel: `refreshLastBpReading()` is callable (used when returning from BP flow)
   - Run verifier (expect fail — if any wiring issues)

2. **GREEN** — Implement:
   - Add routes to `AppNavigation.kt`:
     - `composable("camera-bp") { CameraCaptureScreen(onNavigateToConfirmation = { sys, dia -> navController.navigate("bp-confirm/$sys/$dia") }, onNavigateBack = { navController.popBackStack() }) }`
     - `composable("bp-confirm/{systolic}/{diastolic}", arguments = listOf(navArgument("systolic") { type = NavType.IntType }, navArgument("diastolic") { type = NavType.IntType })) { BpConfirmationScreen(onNavigateHome = { snackbarMsg -> navController.navigate("sync") { popUpTo("sync") { inclusive = true } }; /* pass snackbar message */ }, onCancel = { navController.navigate("sync") { popUpTo("sync") { inclusive = true } } }) }`
   - Wire `SyncScreen` "Log Blood Pressure" button to `onNavigateToCamera` callback → `navController.navigate("camera-bp")`
   - Add `onNavigateToCamera: () -> Unit` parameter to `SyncScreen`
   - Handle snackbar message: use `SnackbarHostState` in `SyncScreen`'s Scaffold, pass result via savedStateHandle or navigation result
   - After returning from BP flow, call `viewModel.refreshLastBpReading()` to update the last reading display
   - Run verifier (expect pass)

**Defensive Requirements:**
- Navigation `popUpTo("sync") { inclusive = true }` ensures we don't stack multiple home screens
- Snackbar message must survive navigation — use Scaffold's `SnackbarHostState` with `LaunchedEffect` on a navigation result
- Back press from camera screen goes to home, not back through confirmation

**Notes:**
- Reference: `AppNavigation.kt` for existing route pattern
- Navigation arguments `{systolic}/{diastolic}` are typed as `NavType.IntType`
- Consider adding `SnackbarHost` to the Scaffold in SyncScreen if not already present

---

### Task 12: Integration & Verification
**Linear Issue:** [HEA-109](https://linear.app/lw-claude/issue/HEA-109)

**Files:** All modified files from Tasks 1–11

**Steps:**

1. Run full test suite: `./gradlew test`
2. Build check: `./gradlew assembleDebug`
3. Run `bug-hunter` agent — review all changes for bugs
4. Run `verifier` agent — verify all tests pass and zero warnings

**Manual verification checklist:**
- [ ] App launch requests all permissions at once (HC nutrition, HC BP read/write, camera)
- [ ] Home screen has two fixed sections: Nutrition Sync and Blood Pressure
- [ ] Blood Pressure section shows "No readings yet" initially
- [ ] "Log Blood Pressure" button navigates to camera
- [ ] Camera viewfinder displays, shutter button captures photo
- [ ] Photo sent to Haiku, loading indicator shown
- [ ] On parse success, confirmation screen shows systolic/diastolic
- [ ] Confirmation screen dropdowns work (body position, measurement location)
- [ ] Save writes to Health Connect, returns to home with snackbar "120/80 mmHg saved"
- [ ] Home screen now shows last BP reading with relative time
- [ ] Cancel from confirmation returns to home without saving
- [ ] Parse error shows message with retake option
- [ ] Retake returns to camera viewfinder
- [ ] Anthropic API key configurable in Settings
- [ ] App works without camera permission (nutrition sync unaffected, BP button disabled)

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Add Blood Pressure Scanner — photograph a BP monitor, extract systolic/diastolic via Claude Haiku, write BloodPressureRecord to Health Connect

**Request:** Build the BP Scanner feature from the roadmap: camera capture → Haiku image analysis → confirmation screen → Health Connect write. All permissions upfront, structured home screen with fixed sections, last reading displayed on home.

**Linear Issues:** HEA-98, HEA-99, HEA-100, HEA-101, HEA-102, HEA-103, HEA-104, HEA-105, HEA-106, HEA-107, HEA-108, HEA-109

**Approach:** Layer-by-layer implementation following existing Clean Architecture patterns. Start with domain models and dependencies, build up through repository/Health Connect integration, add Anthropic API client for Haiku image analysis, create use cases, then build the three new screens (restructured home, camera capture, BP confirmation). CameraX Compose-native integration for camera, Ktor for Haiku API calls, existing encrypted prefs pattern for API key storage.

**Scope:**
- Tasks: 12
- Files affected: ~25 (15 new, 10 modified)
- New tests: yes (8 new test files)

**Key Decisions:**
- BP Scanner built first (before Glucose Scanner) — shared camera/Haiku infrastructure will be reusable
- Anthropic API key stored alongside Food Scanner API key in EncryptedSharedPreferences
- CameraX Compose-native API (`camera-compose`) for camera preview
- Navigation arguments pass systolic/diastolic between camera and confirmation screens
- SyncViewModel extended with BP reading data (not a separate ViewModel) to keep the home screen simple
- All permissions (HC nutrition, HC BP read/write, camera) requested upfront on first launch

**Risks/Considerations:**
- CameraX has a known bug on API 28/29 ("Camera is closed") — needs testing on those API levels
- Image quality (~18% rejection rate in research) means good error UX is critical
- Anthropic API key needs to be obtained by user separately — clear guidance in settings UI
- CameraX `camera-compose` API may vary between versions — verify against actual stable release
