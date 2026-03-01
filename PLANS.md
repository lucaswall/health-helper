# Implementation Plan: Glucose Scanner

**Status:** COMPLETE
**Branch:** feat/HEA-150-glucose-scanner
**Issues:** HEA-150, HEA-151, HEA-152, HEA-153, HEA-154
**Created:** 2026-02-28
**Last Updated:** 2026-02-28

## Summary

Implement the Blood Glucose Scanner feature end-to-end. Users photograph their glucometer screen, the AI extracts the reading (value + unit), and the result is saved to Health Connect as a `BloodGlucoseRecord` with optional meal context metadata. The feature mirrors the existing Blood Pressure Scanner flow: camera capture -> AI parsing -> confirmation screen -> Health Connect write.

## Issues

### HEA-150: GlucoseReading domain model with dual-unit support and conversion

**Priority:** Medium
**Labels:** Feature
**Description:** Create the domain model for blood glucose readings with dual-unit support (mmol/L and mg/dL), deterministic conversion logic, validation, and supporting enums.

**Acceptance Criteria:**
- [ ] `GlucoseReading` data class with value in mmol/L and optional metadata
- [ ] `GlucoseUnit` enum (MMOL_L, MG_DL)
- [ ] `GlucoseParseResult` sealed class (Success with value + detectedUnit, Error with message)
- [ ] Deterministic conversion: mg/dL / 18.018 = mmol/L
- [ ] Validation range: 1.0-40.0 mmol/L
- [ ] Enums: RelationToMeal, MealType (glucose-specific), SpecimenSource
- [ ] `displayInMgDl()` helper for dual-unit display

### HEA-151: Haiku glucose image parsing with value + unit detection

**Priority:** Medium
**Labels:** Feature
**Description:** Extend `AnthropicApiClient` with a `parseGlucoseImage()` method that uses Haiku tool_use to extract the glucose value and detected unit from a glucometer display photo. The app handles conversion — Haiku returns raw value + unit.

**Acceptance Criteria:**
- [ ] `parseGlucoseImage(apiKey, imageBytes): GlucoseParseResult` method
- [ ] Tool_use schema returns `value` (numeric) and `unit` ("mmol/L" or "mg/dL")
- [ ] System prompt instructs Haiku to identify the unit from the display
- [ ] Reuses existing `prepareImageForApi()` pattern
- [ ] Error handling: blurry photo, no number, not a glucometer

### HEA-152: Health Connect BloodGlucoseRecord repository and mapper

**Priority:** Medium
**Labels:** Feature
**Description:** Create repository interface, Health Connect implementation, mapper, use cases, and manifest permissions for blood glucose records.

**Acceptance Criteria:**
- [ ] `BloodGlucoseRepository` interface in domain layer
- [ ] `HealthConnectBloodGlucoseRepository` implementation with 10s timeout
- [ ] `BloodGlucoseRecordMapper` with bidirectional mapping
- [ ] `WriteGlucoseReadingUseCase` and `GetLastGlucoseReadingUseCase`
- [ ] `WRITE_BLOOD_GLUCOSE` and `READ_BLOOD_GLUCOSE` permissions in manifest
- [ ] Hilt bindings in `AppModule`

### HEA-153: Glucose confirmation screen with dual-unit display

**Priority:** Medium
**Labels:** Feature
**Description:** Confirmation screen for glucose readings with dual-unit display, editable value, meal context dropdowns, and Health Connect save.

**Acceptance Criteria:**
- [ ] Glucose value displayed in both mmol/L and mg/dL
- [ ] Editable value with real-time dual-unit conversion
- [ ] Relation to Meal dropdown
- [ ] Meal Type dropdown (conditional on Before/After Meal)
- [ ] Specimen Source dropdown
- [ ] Timestamp display
- [ ] Save button (disabled when validation fails)
- [ ] Retake button (navigates back to camera)
- [ ] On save success: navigate home with snackbar

### HEA-154: Glucose capture flow, navigation, and home screen integration

**Priority:** Medium
**Labels:** Feature
**Description:** Add glucose capture screen, navigation routes, and home screen entry point ("Log Glucose" button + last glucose reading display).

**Acceptance Criteria:**
- [ ] Glucose camera capture screen (reuse or clone BP camera pattern)
- [ ] Navigation routes: `camera-glucose` -> `glucose-confirm/{value}/{unit}/{detectedUnit}`
- [ ] "Log Glucose" button on home screen
- [ ] Last glucose reading display on home screen
- [ ] Health Connect permission request for glucose permissions
- [ ] Processing overlay during Haiku API call
- [ ] Error handling with retake option

## Prerequisites

- [ ] All current tests pass (`./gradlew test`)
- [ ] Build compiles (`./gradlew assembleDebug`)
- [ ] `gradle/libs.versions.toml` — no new dependencies needed; Health Connect 1.1.0 already includes `BloodGlucoseRecord`

## Implementation Tasks

### Task 1: GlucoseUnit enum

**Issue:** HEA-150
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseUnit.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/GlucoseUnitTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests for `GlucoseUnit`:
   - Test that `GlucoseUnit.MMOL_L` and `GlucoseUnit.MG_DL` exist as enum values
   - Test `entries` returns exactly 2 values
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.GlucoseUnitTest"`

2. **GREEN** — Create `GlucoseUnit` enum with `MMOL_L` and `MG_DL` values.

3. **REFACTOR** — Verify naming follows project convention (see `BodyPosition.kt`, `MeasurementLocation.kt`).

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/model/BodyPosition.kt`

---

### Task 2: Glucose metadata enums (RelationToMeal, GlucoseMealType, SpecimenSource)

**Issue:** HEA-150
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/RelationToMeal.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseMealType.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SpecimenSource.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/RelationToMealTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/GlucoseMealTypeTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/SpecimenSourceTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests for each enum:
   - `RelationToMeal`: GENERAL, FASTING, BEFORE_MEAL, AFTER_MEAL, UNKNOWN (5 values)
   - `GlucoseMealType`: BREAKFAST, LUNCH, DINNER, SNACK, UNKNOWN (5 values)
   - `SpecimenSource`: CAPILLARY_BLOOD, INTERSTITIAL_FLUID, PLASMA, SERUM, TEARS, WHOLE_BLOOD, UNKNOWN (7 values)
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.RelationToMealTest" --tests "com.healthhelper.app.domain.model.GlucoseMealTypeTest" --tests "com.healthhelper.app.domain.model.SpecimenSourceTest"`

2. **GREEN** — Create the three enum classes.

3. **REFACTOR** — Ensure naming consistency.

**Notes:**
- Named `GlucoseMealType` to avoid clash with existing `MealType` enum (used for nutrition sync)
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/model/BodyPosition.kt` for enum pattern

---

### Task 3: GlucoseReading data class with validation and dual-unit conversion

**Issue:** HEA-150
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseReading.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/GlucoseReadingTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - Valid reading with default metadata creates successfully
   - `require` throws for value below 1.0 mmol/L
   - `require` throws for value above 40.0 mmol/L
   - `displayInMgDl()` returns correct conversion (5.6 mmol/L -> 101 mg/dL, using `value * 18.018` rounded to Int)
   - Boundary values: 1.0 and 40.0 pass validation
   - Default values: `relationToMeal = UNKNOWN`, `glucoseMealType = UNKNOWN`, `specimenSource = UNKNOWN`
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.GlucoseReadingTest"`

2. **GREEN** — Create `GlucoseReading`:
   - `valueMmolL: Double` — glucose value in mmol/L (primary unit, matches Health Connect)
   - `relationToMeal: RelationToMeal = RelationToMeal.UNKNOWN`
   - `glucoseMealType: GlucoseMealType = GlucoseMealType.UNKNOWN`
   - `specimenSource: SpecimenSource = SpecimenSource.UNKNOWN`
   - `timestamp: Instant = Instant.now()`
   - `init { require(valueMmolL in 1.0..40.0) }`
   - `fun displayInMgDl(): Int = (valueMmolL * 18.018).roundToInt()`

3. **REFACTOR** — Ensure pattern matches `BloodPressureReading` structure.

**Defensive Requirements:**
- Validation in `init` block mirrors `BloodPressureReading` pattern
- Conversion uses deterministic math (multiply by 18.018), NOT LLM output

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/model/BloodPressureReading.kt`

---

### Task 4: GlucoseParseResult sealed class

**Issue:** HEA-150
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseParseResult.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/model/GlucoseParseResultTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - `GlucoseParseResult.Success` holds `value: Double` and `detectedUnit: GlucoseUnit`
   - `GlucoseParseResult.Error` holds `message: String`
   - Verify `is` checks work for sealed class hierarchy
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.model.GlucoseParseResultTest"`

2. **GREEN** — Create sealed class:
   - `Success(val value: Double, val detectedUnit: GlucoseUnit)`
   - `Error(val message: String)`

3. **REFACTOR** — Verify consistency with `BloodPressureParseResult`.

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/model/BloodPressureParseResult.kt`
- `value` is the raw value in the detected unit (not yet converted to mmol/L)

---

### Task 5: Haiku glucose image parsing in AnthropicApiClient

**Issue:** HEA-151
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/AnthropicApiClient.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/AnthropicApiClientTest.kt` (modify)

**TDD Steps:**

1. **RED** — Add tests to `AnthropicApiClientTest`:
   - Successful glucose parse returns `GlucoseParseResult.Success` with value and detected unit
   - Tool_use response with `error` field returns `GlucoseParseResult.Error`
   - Missing `value` or `unit` in tool response returns Error
   - HTTP 401 returns `Error("Authentication failed")`
   - HTTP 429 returns `Error("Rate limited")`
   - Network exception returns `Error("Failed to analyze image")`
   - Value of 0 or negative returns error (implausible reading)
   - Invalid unit string returns error
   - Run: `./gradlew test --tests "com.healthhelper.app.data.api.AnthropicApiClientTest"`

2. **GREEN** — Add `parseGlucoseImage()` method:
   - Define `GLUCOSE_TOOL_NAME = "glucose_reading"` constant
   - Define `GLUCOSE_TOOL` with `inputSchema` having `value` (number), `unit` (string enum: "mmol/L", "mg/dL"), and `error` (string) fields
   - Define `GLUCOSE_TOOL_CHOICE` forcing tool use
   - System prompt: "Extract the glucose reading from this glucometer display. Return the numeric value shown and the unit displayed (mmol/L or mg/dL). If the unit is not visible, infer from the numeric range: values above 30 are likely mg/dL, below 30 are likely mmol/L."
   - Parse tool_use response extracting `value` (Double) and `unit` (String)
   - Map unit string to `GlucoseUnit` enum
   - Return `GlucoseParseResult.Success(value, detectedUnit)` or `GlucoseParseResult.Error(message)`
   - Follow exact same error handling pattern as `parseBloodPressureImage()` (CancellationException rethrow, SecurityException, SerializationException, generic Exception)

3. **REFACTOR** — Extract shared HTTP/error handling logic between BP and glucose parsing if duplication is excessive. Otherwise, keep separate methods for clarity.

**Defensive Requirements:**
- CancellationException must be rethrown (coroutine contract)
- SecurityException caught separately with specific message
- HTTP error codes produce sanitized user messages (not raw error bodies)
- Ktor HttpTimeout (30s configured in AppModule) covers API call timeout
- Validate that parsed `value` is positive before returning Success

**Notes:**
- Reference: `AnthropicApiClient.kt:79-165` for `parseBloodPressureImage()` pattern
- Reference: `AnthropicApiClientTest.kt` for Ktor mock engine test pattern
- The `value` in `GlucoseParseResult.Success` is the raw value in whatever unit was detected; conversion to mmol/L happens in the ViewModel/UseCase layer

---

### Task 6: BloodGlucoseRepository interface

**Issue:** HEA-152
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodGlucoseRepository.kt` (create)

**TDD Steps:**

1. **RED** — No test needed for an interface; compilation is the test.

2. **GREEN** — Create interface:
   - `suspend fun writeBloodGlucoseRecord(reading: GlucoseReading): Boolean`
   - `suspend fun getLastReading(): GlucoseReading?`

3. **REFACTOR** — Verify naming matches `BloodPressureRepository` pattern.

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/repository/BloodPressureRepository.kt`

---

### Task 7: BloodGlucoseRecordMapper

**Issue:** HEA-152
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/BloodGlucoseRecordMapper.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/BloodGlucoseRecordMapperTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - `mapToBloodGlucoseRecord()`: GlucoseReading -> BloodGlucoseRecord
     - Value mapped via `BloodGlucose.millimolesPerLiter()`
     - `relationToMeal` enum maps to `RELATION_TO_MEAL_*` constants
     - `glucoseMealType` enum maps to `MEAL_TYPE_*` constants
     - `specimenSource` enum maps to `SPECIMEN_SOURCE_*` constants
     - `metadata` uses `Metadata.manualEntry()` with clientRecordId = "bloodglucose-{epochMilli}"
     - Zone offset derived from system default
   - `mapToGlucoseReading()`: BloodGlucoseRecord -> GlucoseReading
     - Value extracted from `level.inMillimolesPerLiter`
     - All enum fields reverse-mapped correctly
     - Unknown/unrecognized HC values map to UNKNOWN
   - All RelationToMeal values round-trip correctly
   - All GlucoseMealType values round-trip correctly
   - All SpecimenSource values round-trip correctly
   - Run: `./gradlew test --tests "com.healthhelper.app.data.repository.BloodGlucoseRecordMapperTest"`

2. **GREEN** — Create mapper with top-level functions (same pattern as `BloodPressureRecordMapper.kt`):
   - `fun mapToBloodGlucoseRecord(reading: GlucoseReading): BloodGlucoseRecord`
   - `fun mapToGlucoseReading(record: BloodGlucoseRecord): GlucoseReading`
   - Map each enum with `when` statements

3. **REFACTOR** — Ensure exhaustive `when` (no `else` branches for domain enums to catch missing cases at compile time).

**Defensive Requirements:**
- HC record `level` uses `BloodGlucose.millimolesPerLiter()` — value must already be in mmol/L
- Reverse mapping from HC uses `else -> UNKNOWN` for HC int constants (forward-compatible)
- `Metadata.manualEntry()` factory — NOT the constructor (CLAUDE.md gotcha)

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/data/repository/BloodPressureRecordMapper.kt`

---

### Task 8: HealthConnectBloodGlucoseRepository

**Issue:** HEA-152
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodGlucoseRepository.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodGlucoseRepositoryTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - `writeBloodGlucoseRecord()`:
     - Success: calls `insertRecords`, returns `true`
     - HC client null: returns `false`, logs warning
     - TimeoutCancellationException: returns `false`, logs error
     - SecurityException (permission denied): returns `false`, logs error
     - Generic exception: returns `false`, logs error
     - CancellationException: rethrown (not caught)
   - `getLastReading()`:
     - Returns most recent record from last 30 days
     - Returns `null` when no records found
     - HC client null: returns `null`
     - TimeoutCancellationException: returns `null`
     - SecurityException: returns `null`
     - Generic exception: returns `null`
     - CancellationException: rethrown
   - Run: `./gradlew test --tests "com.healthhelper.app.data.repository.HealthConnectBloodGlucoseRepositoryTest"`

2. **GREEN** — Create implementation following `HealthConnectBloodPressureRepository` exactly:
   - Constructor: `@Inject constructor(private val healthConnectClient: HealthConnectClient?)`
   - 10-second timeout via `withTimeout(10_000L)` on all HC calls
   - Use `BloodGlucoseRecord::class` for `ReadRecordsRequest`
   - Use mapper functions for domain/HC conversion
   - Timber logging at same granularity as BP repository

3. **REFACTOR** — Ensure consistent logging messages.

**Defensive Requirements:**
- 10-second timeout on all Health Connect operations
- Null check on `healthConnectClient` before any operation
- CancellationException rethrown (coroutine contract)
- SecurityException caught separately (permission denial)
- TimeoutCancellationException caught separately (timeout)

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepository.kt`
- Reference: `app/src/test/kotlin/com/healthhelper/app/data/repository/HealthConnectBloodPressureRepositoryTest.kt`

---

### Task 9: WriteGlucoseReadingUseCase and GetLastGlucoseReadingUseCase

**Issue:** HEA-152
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCase.kt` (create)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/GetLastGlucoseReadingUseCase.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/WriteGlucoseReadingUseCaseTest.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/GetLastGlucoseReadingUseCaseTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - `WriteGlucoseReadingUseCase`:
     - Delegates to `BloodGlucoseRepository.writeBloodGlucoseRecord()`
     - Returns `true` on success
     - Returns `false` on failure
   - `GetLastGlucoseReadingUseCase`:
     - Delegates to `BloodGlucoseRepository.getLastReading()`
     - Returns reading on success
     - Returns `null` on exception (catches and logs)
     - CancellationException rethrown
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.WriteGlucoseReadingUseCaseTest" --tests "com.healthhelper.app.domain.usecase.GetLastGlucoseReadingUseCaseTest"`

2. **GREEN** — Create use cases:
   - `WriteGlucoseReadingUseCase`: simple delegation, `@Inject constructor`
   - `GetLastGlucoseReadingUseCase`: try-catch with Timber logging, `@Inject constructor`

3. **REFACTOR** — Verify pattern matches BP use cases.

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/usecase/WriteBloodPressureReadingUseCase.kt`
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/usecase/GetLastBloodPressureReadingUseCase.kt`
- Domain layer use cases are pure Kotlin — no Android imports except Timber (which is already used in `GetLastBloodPressureReadingUseCase`)

---

### Task 10: AndroidManifest.xml permissions and Hilt bindings

**Issue:** HEA-152
**Files:**
- `app/src/main/AndroidManifest.xml` (modify)
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)

**TDD Steps:**

1. **RED** — No unit test for manifest; verified by build. For Hilt, add test:
   - Verify `provideBloodGlucoseRepository()` returns `HealthConnectBloodGlucoseRepository` instance
   - Run: `./gradlew test --tests "com.healthhelper.app.di.AppModuleTest"`

2. **GREEN** —
   - Add to `AndroidManifest.xml`:
     - `<uses-permission android:name="android.permission.health.WRITE_BLOOD_GLUCOSE" />`
     - `<uses-permission android:name="android.permission.health.READ_BLOOD_GLUCOSE" />`
   - Add to `AppModule.kt`:
     - `provideBloodGlucoseRepository(healthConnectClient: HealthConnectClient?): BloodGlucoseRepository` returning `HealthConnectBloodGlucoseRepository(healthConnectClient)`

3. **REFACTOR** — Keep permissions grouped with existing Health Connect permissions in manifest.

**Notes:**
- Reference: `app/src/main/AndroidManifest.xml` — existing WRITE_BLOOD_PRESSURE and READ_BLOOD_PRESSURE permissions
- Reference: `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt:80-82` for `provideBloodPressureRepository` pattern

---

### Task 11: GlucoseConfirmationViewModel

**Issue:** HEA-153
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModel.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModelTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - **Initial state**: ViewModel initializes with `valueMmolL` and `detectedUnit` from SavedStateHandle; if detectedUnit is MG_DL, converts value to mmol/L for display; shows both units
   - **Dual-unit display**: When value is 5.6 mmol/L, `displayMgDl` shows "101"
   - **Value editing**: `updateValue("6.0")` updates both mmol/L and mg/dL displays; invalid input (empty, non-numeric) disables save
   - **Validation**: Value outside 1.0-40.0 mmol/L disables save with validation error message
   - **Dropdown updates**: `updateRelationToMeal()`, `updateGlucoseMealType()`, `updateSpecimenSource()` update state
   - **Conditional meal type**: `mealTypeVisible` is `true` only when relationToMeal is BEFORE_MEAL or AFTER_MEAL
   - **Save success**: Calls `WriteGlucoseReadingUseCase`, on success emits navigation event with snackbar message "5.6 mmol/L saved"
   - **Save failure**: On use case returning `false`, shows error in UI state
   - **Save guard**: Second `save()` call while first is in-flight is ignored (isSaving check)
   - **Cancellation**: Previous save job cancelled before new one starts
   - **Error path**: Exception during save caught, shows generic error, logs via Timber
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.GlucoseConfirmationViewModelTest"`

2. **GREEN** — Create ViewModel:
   - `GlucoseConfirmationUiState` data class: `valueMmolL: String`, `displayMgDl: String`, `detectedUnit: GlucoseUnit`, `originalValue: String` (for "Converted from X mg/dL" note), `relationToMeal`, `glucoseMealType`, `specimenSource`, `mealTypeVisible: Boolean`, `isSaveEnabled: Boolean`, `isSaving: Boolean`, `error: String?`, `validationError: String?`
   - SavedStateHandle args: `value` (Float), `unit` (String), `detectedUnit` (String)
   - Init: parse value from SavedStateHandle; if unit is "mg/dL", convert to mmol/L using `/ 18.018`; compute displayMgDl
   - Methods: `updateValue()`, `updateRelationToMeal()`, `updateGlucoseMealType()`, `updateSpecimenSource()`, `save()`
   - `navigateHome: SharedFlow<String>` for navigation event (same pattern as BpConfirmationViewModel)
   - Private `validate()` companion function

3. **REFACTOR** — Extract validation constants.

**Defensive Requirements:**
- `savingJob?.cancel()` before launching new save (cancellation of in-flight work)
- CancellationException rethrown in save coroutine
- Save button disabled during save (isSaving guard + isSaveEnabled)
- Generic error message shown to user; raw exception logged via Timber only

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/BpConfirmationViewModel.kt`
- Reference: `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/BpConfirmationViewModelTest.kt`

---

### Task 12: GlucoseConfirmationScreen composable

**Issue:** HEA-153
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/GlucoseConfirmationScreen.kt` (create)

**TDD Steps:**

1. **RED** — No compose test (UI tests are instrumented); verify by build and manual testing.

2. **GREEN** — Create composable following `BpConfirmationScreen` pattern:
   - `@Composable fun GlucoseConfirmationScreen(onNavigateHome: (String) -> Unit, onCancel: () -> Unit)`
   - `hiltViewModel<GlucoseConfirmationViewModel>()`
   - Display glucose value prominently in both units (e.g., "5.6 mmol/L (101 mg/dL)")
   - If detected unit was mg/dL, show conversion note "Converted from 101 mg/dL"
   - Editable text field for mmol/L value (mg/dL updates reactively)
   - `ExposedDropdownMenuBox` for Relation to Meal
   - `ExposedDropdownMenuBox` for Meal Type (conditionally visible)
   - `ExposedDropdownMenuBox` for Specimen Source
   - Timestamp display (non-editable, shows current time)
   - Save button (enabled when `isSaveEnabled`, shows loading when `isSaving`)
   - Retake button (calls `onCancel`)
   - Error text when `error` or `validationError` is non-null
   - `LaunchedEffect` collecting `navigateHome` SharedFlow to trigger navigation

3. **REFACTOR** — Extract dropdown composable if code is repetitive.

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/ui/BpConfirmationScreen.kt`
- Use Material 3 components consistent with existing screens

---

### Task 13: GlucoseCaptureViewModel

**Issue:** HEA-154
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseCaptureViewModel.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseCaptureViewModelTest.kt` (create)

**TDD Steps:**

1. **RED** — Write tests:
   - **Happy path**: `onPhotoCaptured()` calls `parseGlucoseImage()`, on Success emits `navigateToConfirmation` with value, unit, and detectedUnit
   - **Parse error**: On `GlucoseParseResult.Error`, emits `navigateBackWithError` with "Could not read glucose from image. Please retake."
   - **Missing API key**: Empty anthropic key emits error "Configure Anthropic API key in Settings"
   - **Image preparation failure**: `prepareImageForApi` returns null -> error
   - **Processing guard**: Second call while processing is ignored
   - **Cancellation**: Previous processing job cancelled before new one starts
   - **Exception handling**: Generic exception caught, logged, emits error
   - **CancellationException**: Rethrown, not caught
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.GlucoseCaptureViewModelTest"`

2. **GREEN** — Create ViewModel cloning `CameraCaptureViewModel` pattern:
   - Same `CameraCaptureUiState` (or a `GlucoseCaptureUiState` if cleaner)
   - Inject: `AnthropicApiClient`, `SettingsRepository`, `SavedStateHandle`, `@DefaultDispatcher`
   - `onPhotoCaptured(imageBytes)`: check not processing, cancel previous job, get API key, prepare image, call `parseGlucoseImage()`, navigate on result
   - `onCaptureError(message)`: emit error
   - `navigateToConfirmation: SharedFlow<Triple<Double, String, String>>` (value, unit, detectedUnit)
   - `navigateBackWithError: SharedFlow<String>`
   - Reuse `prepareImageForApi()` — either call via shared utility or duplicate the private method

3. **REFACTOR** — Consider extracting `prepareImageForApi()` to a shared utility class if the duplication is too high. Otherwise, keep it in each ViewModel for simplicity.

**Defensive Requirements:**
- `processingJob?.cancel()` before launching new processing
- CancellationException rethrown
- API key check before making the API call
- Image preparation failure handled gracefully
- Generic error messages to user; raw exceptions logged via Timber

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt`
- Reference: `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModelTest.kt`

---

### Task 14: GlucoseCaptureScreen composable

**Issue:** HEA-154
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/GlucoseCaptureScreen.kt` (create)

**TDD Steps:**

1. **RED** — No compose test; verify by build and manual testing.

2. **GREEN** — Create composable cloning `CameraCaptureScreen` pattern:
   - `@Composable fun GlucoseCaptureScreen(onNavigateToConfirmation: (Double, String, String) -> Unit, onNavigateBack: () -> Unit, onNavigateBackWithError: (String) -> Unit)`
   - `hiltViewModel<GlucoseCaptureViewModel>()`
   - System camera via `ActivityResultContracts.TakePicture()` with FileProvider temp file
   - Processing overlay (loading indicator) during API call
   - Collect `navigateToConfirmation` and `navigateBackWithError` SharedFlows
   - Auto-launch camera on screen entry (same as BP camera flow)

3. **REFACTOR** — Ensure consistent with `CameraCaptureScreen` UX.

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt`

---

### Task 15: Navigation routes and SyncScreen integration

**Issue:** HEA-154
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` (modify)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModelTest.kt` (modify)

**TDD Steps:**

1. **RED** — Write/modify tests:
   - `SyncViewModelTest`: Test that `lastGlucoseReading`, `lastGlucoseReadingDisplay`, and `lastGlucoseReadingTime` are populated from `GetLastGlucoseReadingUseCase`
   - `SyncViewModelTest`: Test `refreshLastGlucoseReading()` updates state
   - `SyncViewModelTest`: Test that `REQUIRED_HC_PERMISSIONS` now includes `WRITE_BLOOD_GLUCOSE` and `READ_BLOOD_GLUCOSE`
   - Run: `./gradlew test --tests "com.healthhelper.app.presentation.viewmodel.SyncViewModelTest"`

2. **GREEN** —
   - **SyncViewModel**:
     - Add `GetLastGlucoseReadingUseCase` injection
     - Add to `SyncUiState`: `lastGlucoseReading: GlucoseReading?`, `lastGlucoseReadingDisplay: String`, `lastGlucoseReadingTime: String`
     - Add `loadLastGlucoseReading()` private method (same pattern as `loadLastBpReading()`)
     - Call in `init` block
     - Add `refreshLastGlucoseReading()` public method
     - Add `BloodGlucoseRecord` permissions to `REQUIRED_HC_PERMISSIONS`
     - Refresh glucose reading time in the periodic time refresh coroutine
   - **AppNavigation**:
     - Add `camera-glucose` route -> `GlucoseCaptureScreen`
     - Add `glucose-confirm/{value}/{unit}/{detectedUnit}` route -> `GlucoseConfirmationScreen`
     - Add `onNavigateToGlucoseCamera` callback from SyncScreen
     - Add `glucose_scan_error` savedStateHandle pattern (same as `bp_scan_error`)
   - **SyncScreen**:
     - Add `onNavigateToGlucoseCamera` parameter
     - Add "Log Glucose" button (same pattern as "Log Blood Pressure")
     - Add last glucose reading display section (value in mmol/L + relative time)
     - Add `glucoseScanError` parameter + handling (same pattern as `bpScanError`)

3. **REFACTOR** — Keep SyncScreen layout clean. Group BP and glucose reading displays logically.

**Defensive Requirements:**
- Navigation argument encoding: value is Float, unit and detectedUnit are Strings — URL-encode if needed
- `refreshLastGlucoseReading()` called when returning from glucose confirmation (same as BP flow)
- Health Connect permission set expanded to include glucose — must update the permission request flow

**Notes:**
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` for route pattern
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/ui/SyncScreen.kt` for home screen layout
- Reference: `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/SyncViewModel.kt:196-216` for `loadLastBpReading()` pattern

---

### Task 16: Integration and verification

**Issue:** HEA-150, HEA-151, HEA-152, HEA-153, HEA-154
**Files:**
- All files from previous tasks

**Steps:**

1. Run full test suite: `./gradlew test`
2. Build check: `./gradlew assembleDebug`
3. Manual verification:
   - [ ] "Log Glucose" button appears on home screen
   - [ ] Tapping "Log Glucose" launches system camera
   - [ ] Photographing a glucometer screen extracts the reading
   - [ ] Confirmation screen shows dual-unit display (mmol/L and mg/dL)
   - [ ] Editing the value updates both units in real time
   - [ ] Meal Type dropdown only shows when Relation to Meal is Before/After Meal
   - [ ] Save writes to Health Connect successfully
   - [ ] Snackbar shows "X.X mmol/L saved" after save
   - [ ] Last glucose reading displays on home screen with relative time
   - [ ] Error cases: blurry photo shows error, retake option works
   - [ ] Health Connect permissions requested if not granted

## MCP Usage During Implementation

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `save_issue` | Move issues to "In Progress" when starting, "Done" when complete |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| Blurry/unreadable photo | GlucoseParseResult.Error -> "Could not read glucose from image" | Unit test (Task 5) |
| Glucose value out of range | GlucoseReading init throws IllegalArgumentException | Unit test (Task 3) |
| Health Connect unavailable | Repository returns false/null | Unit test (Task 8) |
| Health Connect timeout | Returns false/null after 10s | Unit test (Task 8) |
| Permission denied | SecurityException caught, returns false/null | Unit test (Task 8) |
| Missing API key | Error message before API call | Unit test (Task 13) |
| HTTP 401 (auth failure) | "Authentication failed" error | Unit test (Task 5) |
| HTTP 429 (rate limit) | "Rate limited" error | Unit test (Task 5) |
| Network failure | "Failed to analyze image" error | Unit test (Task 5) |
| Invalid value on confirmation | Save disabled with validation message | Unit test (Task 11) |

## Risks & Open Questions

- [ ] Risk: `prepareImageForApi()` is duplicated between `CameraCaptureViewModel` and `GlucoseCaptureViewModel`. Consider extracting to a shared utility after both are working, but don't block on this.
- [ ] Risk: Health Connect 1.1.0 API surface for `BloodGlucoseRecord` — verify `SPECIMEN_SOURCE_*`, `RELATION_TO_MEAL_*`, and `MEAL_TYPE_*` constants are available. If not, fall back to available constants only.

## Scope Boundaries

**In Scope:**
- GlucoseReading domain model with dual-unit support
- Haiku glucose image parsing via tool_use API
- Health Connect BloodGlucoseRecord write/read
- Glucose confirmation screen with all metadata fields
- Glucose capture flow with camera integration
- Navigation routes and home screen integration
- Health Connect permissions for glucose

**Out of Scope:**
- Glucose history/trends screen
- Glucose reading editing after save
- Share intent support for glucose (only BP has this)
- Measurement Reminders (separate roadmap item)
- Timestamp editing on confirmation screen (displays current time, not editable — can be added later)

---

## Iteration 1

**Implemented:** 2026-02-28
**Method:** Single-agent (fly solo captain)

### Tasks Completed This Iteration
- Task 1: Domain models — GlucoseUnit, RelationToMeal, GlucoseMealType, SpecimenSource enums
- Task 2: GlucoseReading data class with validation and dual-unit conversion
- Task 3: GlucoseParseResult sealed class
- Task 4: Domain model tests
- Task 5: Anthropic API client glucose image parsing (parseGlucoseImage + tool definitions)
- Task 6: BloodGlucoseRepository interface
- Task 7: BloodGlucoseRecordMapper with Health Connect enum mappings
- Task 8: HealthConnectBloodGlucoseRepository implementation
- Task 9: WriteGlucoseReadingUseCase and GetLastGlucoseReadingUseCase
- Task 10: AndroidManifest permissions and Hilt DI bindings
- Task 11: GlucoseConfirmationViewModel with dual-unit conversion, validation, meal type visibility
- Task 12: GlucoseConfirmationScreen composable with dropdown menus
- Task 13: GlucoseCaptureViewModel with image preparation and API integration
- Task 14: GlucoseCaptureScreen composable with camera launch
- Task 15: Navigation routes (camera-glucose, glucose-confirm) and SyncScreen integration (glucose card, last reading display)
- Task 16: Integration verification — all tests pass, lint clean, build successful

### Files Modified
- `app/src/main/AndroidManifest.xml` — Added WRITE/READ_BLOOD_GLUCOSE permissions
- `app/src/main/kotlin/.../data/api/AnthropicApiClient.kt` — Added parseGlucoseImage(), glucose tool definitions
- `app/src/main/kotlin/.../data/repository/BloodGlucoseRecordMapper.kt` — NEW: HC record ↔ domain model mapping
- `app/src/main/kotlin/.../data/repository/HealthConnectBloodGlucoseRepository.kt` — NEW: HC repository implementation
- `app/src/main/kotlin/.../di/AppModule.kt` — Added BloodGlucoseRepository binding
- `app/src/main/kotlin/.../domain/model/GlucoseUnit.kt` — NEW: MMOL_L, MG_DL enum
- `app/src/main/kotlin/.../domain/model/RelationToMeal.kt` — NEW: meal relation enum
- `app/src/main/kotlin/.../domain/model/GlucoseMealType.kt` — NEW: meal type enum
- `app/src/main/kotlin/.../domain/model/SpecimenSource.kt` — NEW: specimen source enum
- `app/src/main/kotlin/.../domain/model/GlucoseReading.kt` — NEW: data class with validation
- `app/src/main/kotlin/.../domain/model/GlucoseParseResult.kt` — NEW: Success/Error sealed class
- `app/src/main/kotlin/.../domain/repository/BloodGlucoseRepository.kt` — NEW: repository interface
- `app/src/main/kotlin/.../domain/usecase/WriteGlucoseReadingUseCase.kt` — NEW
- `app/src/main/kotlin/.../domain/usecase/GetLastGlucoseReadingUseCase.kt` — NEW
- `app/src/main/kotlin/.../presentation/viewmodel/GlucoseConfirmationViewModel.kt` — NEW
- `app/src/main/kotlin/.../presentation/viewmodel/GlucoseCaptureViewModel.kt` — NEW
- `app/src/main/kotlin/.../presentation/viewmodel/SyncViewModel.kt` — Added glucose state, permissions, refresh
- `app/src/main/kotlin/.../presentation/ui/GlucoseConfirmationScreen.kt` — NEW
- `app/src/main/kotlin/.../presentation/ui/GlucoseCaptureScreen.kt` — NEW
- `app/src/main/kotlin/.../presentation/ui/AppNavigation.kt` — Added glucose routes
- `app/src/main/kotlin/.../presentation/ui/SyncScreen.kt` — Added glucose card section
- All corresponding test files (15 test files created/modified)

### Linear Updates
- HEA-150: Todo → In Progress → Review
- HEA-151: Todo → In Progress → Review
- HEA-152: Todo → In Progress → Review
- HEA-153: Todo → In Progress → Review
- HEA-154: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 3 bugs (1 HIGH, 1 MEDIUM, 1 LOW), all fixed before commit
  - HIGH: CancellationException swallowed in loadLastGlucoseReading — added rethrow
  - MEDIUM: Out-of-range HC records crash getLastReading — added graceful fallback
  - LOW: Dead code in formatMmolL — simplified to single expression
- verifier: All tests pass, zero lint warnings, build successful

### Continuation Status
All tasks completed.
