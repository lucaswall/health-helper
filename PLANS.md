# Implementation Plan

**Created:** 2026-03-01
**Source:** Inline request: Smart glucose confirmation defaults based on last meal timestamp
**Linear Issues:** [HEA-158](https://linear.app/lw-claude/issue/HEA-158/add-timestamp-to-syncedmealsummary-and-persist-during-sync), [HEA-159](https://linear.app/lw-claude/issue/HEA-159/create-inferglucosedefaultsusecase-from-last-meal-timestamp), [HEA-160](https://linear.app/lw-claude/issue/HEA-160/wire-glucose-confirmation-defaults-from-inferglucosedefaultsusecase)

## Context Gathered

### Codebase Analysis
- **Related files:**
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncedMealSummary.kt` — current model (foodName, mealType, calories)
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/FoodLogEntry.kt` — has `time: String?` field (HH:MM:SS format)
  - `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` — creates SyncedMealSummary from FoodLogEntry (lines 176-188)
  - `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` — serialization via `SyncedMealDto` (lines 39-43), deserialization (lines 139-163), write (lines 201-205)
  - `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModel.kt` — init state defaults all to UNKNOWN (lines 28-41, 61-69)
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/MealType.kt` — nutrition MealType enum
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseMealType.kt` — glucose GlucoseMealType enum (identical values)
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/RelationToMeal.kt` — GENERAL, FASTING, BEFORE_MEAL, AFTER_MEAL, UNKNOWN
  - `app/src/main/kotlin/com/healthhelper/app/domain/model/SpecimenSource.kt` — CAPILLARY_BLOOD etc.
  - `app/src/main/kotlin/com/healthhelper/app/domain/repository/SettingsRepository.kt` — interface with `lastSyncedMealsFlow`
- **Existing patterns:**
  - Use cases follow `@Inject constructor` + `suspend operator fun invoke()` pattern (see `GetLastGlucoseReadingUseCase`)
  - Domain models are pure Kotlin data classes with `init` validation
  - DataStore serialization uses `@Serializable` DTOs with `kotlinx.serialization.json.Json`
  - ViewModel tests use `StandardTestDispatcher` + Turbine + MockK
- **Test conventions:**
  - `SyncedMealSummaryTest.kt` — validates model constraints
  - `SyncNutritionUseCaseTest.kt` — extensive mock-based tests with `configureSettings()` helper
  - `GlucoseConfirmationViewModelTest.kt` — uses `createViewModel()` helper, `advanceTimeBy(1_000)` pattern

### MCP Context
- **MCPs used:** Linear
- **Findings:** No existing issues for smart defaults. Glucose confirmation (HEA-153) and domain model (HEA-150) both released. All meal context fields already mapped to Health Connect.

## Original Plan

### Task 1: Add timestamp to SyncedMealSummary and persist during sync
**Linear Issue:** [HEA-158](https://linear.app/lw-claude/issue/HEA-158/add-timestamp-to-syncedmealsummary-and-persist-during-sync)

**Scope:** Add `timestamp: Instant` to `SyncedMealSummary`, update serialization in DataStore, populate from `FoodLogEntry.time` + date during sync.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/domain/model/SyncedMealSummaryTest.kt`:
   - `SyncedMealSummary` holds timestamp field
   - Default timestamp is `Instant.EPOCH` (for backward compat)

2. Run verifier (expect fail)

3. Update `SyncedMealSummary` in `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncedMealSummary.kt`:
   - Add `timestamp: Instant = Instant.EPOCH` field

4. Run verifier (expect pass)

5. Write tests in `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt`:
   - Synced meal summaries include timestamp derived from entry date + time
   - Synced meal summaries with null time get timestamp at noon of the entry date
   - Existing `persistsLast3MealsSortedByDateThenTime` test updated to verify timestamps

6. Run verifier (expect fail)

7. Update `SyncNutritionUseCase` in `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt`:
   - In the `.map` block (lines 182-188), compute `Instant` from `dateStr` + `entry.time`:
     - Parse `dateStr` as `LocalDate`, parse `entry.time` as `LocalTime` (default `LocalTime.NOON` if null/unparseable)
     - Combine into `LocalDateTime`, convert to `Instant` via system `ZoneId`
   - Pass computed timestamp to `SyncedMealSummary` constructor

8. Run verifier (expect pass)

9. Update serialization in `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt`:
   - Add `timestamp: String? = null` field to `SyncedMealDto` (nullable for backward compat with existing stored JSON)
   - In `setLastSyncedMeals`: serialize `timestamp` as ISO-8601 string via `Instant.toString()`
   - In `lastSyncedMealsFlow`: deserialize `timestamp` — if null/missing, use `Instant.EPOCH`

**Defensive Requirements:**
- Old serialized data (without `timestamp` field) must deserialize without error — `SyncedMealDto.timestamp` defaults to `null`, mapped to `Instant.EPOCH`
- `FoodLogEntry.time` parsing failure (malformed string) must not crash — default to `LocalTime.NOON`

### Task 2: Create InferGlucoseDefaultsUseCase from last meal timestamp
**Linear Issue:** [HEA-159](https://linear.app/lw-claude/issue/HEA-159/create-inferglucosedefaultsusecase-from-last-meal-timestamp)

**Scope:** New pure-domain use case that reads the most recent synced meal and infers glucose confirmation defaults based on elapsed time.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/domain/usecase/InferGlucoseDefaultsUseCaseTest.kt`:
   - Last meal < 3 hours ago → returns AFTER_MEAL + mapped GlucoseMealType + CAPILLARY_BLOOD
   - Last meal 3-8 hours ago → returns UNKNOWN relation + UNKNOWN meal type + CAPILLARY_BLOOD
   - Last meal >= 8 hours ago → returns FASTING + UNKNOWN meal type + CAPILLARY_BLOOD
   - No synced meals (empty list) → returns all UNKNOWN + CAPILLARY_BLOOD
   - Meal with `Instant.EPOCH` timestamp (legacy data) → treated as no data, returns all UNKNOWN + CAPILLARY_BLOOD
   - MealType mapping: BREAKFAST→BREAKFAST, LUNCH→LUNCH, DINNER→DINNER, SNACK→SNACK, UNKNOWN→UNKNOWN
   - SpecimenSource is always CAPILLARY_BLOOD regardless of meal state
   - SettingsRepository read exception → returns all UNKNOWN + CAPILLARY_BLOOD (no crash)

2. Run verifier (expect fail)

3. Create result data class `GlucoseDefaults` in `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseDefaults.kt`:
   - Fields: `relationToMeal: RelationToMeal`, `glucoseMealType: GlucoseMealType`, `specimenSource: SpecimenSource`

4. Create `InferGlucoseDefaultsUseCase` in `app/src/main/kotlin/com/healthhelper/app/domain/usecase/InferGlucoseDefaultsUseCase.kt`:
   - `@Inject constructor(settingsRepository: SettingsRepository)`
   - `suspend operator fun invoke(now: Instant = Instant.now()): GlucoseDefaults`
   - Read `lastSyncedMealsFlow.first()` to get list, take first (most recent)
   - If empty or timestamp is `Instant.EPOCH` → return defaults with all UNKNOWN + CAPILLARY_BLOOD
   - Calculate `Duration.between(meal.timestamp, now)`
   - Apply threshold logic: < 3h → AFTER_MEAL, 3-8h → UNKNOWN, >= 8h → FASTING
   - Map `MealType` → `GlucoseMealType` for AFTER_MEAL case
   - Wrap `settingsRepository` read in try-catch (CancellationException rethrown)

5. Run verifier (expect pass)

**Defensive Requirements:**
- `now` parameter allows deterministic testing without mocking `Instant.now()`
- `Instant.EPOCH` sentinel value guards against legacy data without timestamps
- `CancellationException` propagated, all other exceptions caught and logged

### Task 3: Wire glucose confirmation defaults from InferGlucoseDefaultsUseCase
**Linear Issue:** [HEA-160](https://linear.app/lw-claude/issue/HEA-160/wire-glucose-confirmation-defaults-from-inferglucosedefaultsusecase)

**Scope:** Inject the use case into `GlucoseConfirmationViewModel` and use its output to set initial state.

1. Write tests in `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModelTest.kt`:
   - Initial state reflects inferred defaults (AFTER_MEAL + BREAKFAST + CAPILLARY_BLOOD when meal < 3h ago)
   - Initial state shows FASTING + CAPILLARY_BLOOD when meal >= 8h ago
   - Initial state shows UNKNOWN + CAPILLARY_BLOOD when no meals or 3-8h window
   - `mealTypeVisible` is true when inferred relation is AFTER_MEAL
   - `mealTypeVisible` is false when inferred relation is FASTING
   - User can override inferred defaults via `updateRelationToMeal` / `updateGlucoseMealType` / `updateSpecimenSource`
   - Existing tests updated to mock `InferGlucoseDefaultsUseCase` (return all-UNKNOWN defaults to preserve current behavior)

2. Run verifier (expect fail)

3. Update `GlucoseConfirmationViewModel` in `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModel.kt`:
   - Add `InferGlucoseDefaultsUseCase` to constructor injection
   - In `init` block, launch coroutine to call the use case
   - On result, update `_uiState` with inferred `relationToMeal`, `glucoseMealType`, `specimenSource`
   - Set `mealTypeVisible` based on inferred `relationToMeal` (BEFORE_MEAL or AFTER_MEAL)
   - Handle exceptions: log and keep current UNKNOWN defaults

4. Run verifier (expect pass)

**Defensive Requirements:**
- Use case call is in `viewModelScope.launch` with try-catch — failure keeps UNKNOWN defaults silently
- `CancellationException` propagated
- User overrides are not reverted — the use case only sets initial defaults

## Post-Implementation Checklist
1. Run `bug-hunter` agent - Review changes for bugs
2. Run `verifier` agent - Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Smart-default glucose confirmation fields based on last meal timestamp

**Request:** Use existing meal sync data to pre-fill fasting/after-meal defaults on the glucose confirmation screen. < 3h since last meal = AFTER_MEAL + meal type, 3-8h = no default, >= 8h = FASTING. SpecimenSource always CAPILLARY_BLOOD.

**Linear Issues:** HEA-158, HEA-159, HEA-160

**Approach:** Add timestamp to SyncedMealSummary (persisted via DataStore), create a pure domain use case that applies time-threshold logic to infer defaults, then wire it into the ViewModel's init. Three layers following Clean Architecture: model change → domain logic → presentation wiring.

**Scope:**
- Tasks: 3
- Files affected: ~8 (3 new, 5 modified)
- New tests: yes

**Key Decisions:**
- `Instant.EPOCH` sentinel for backward-compatible deserialization of old data without timestamps
- `now: Instant` parameter on use case for deterministic testing
- MealType → GlucoseMealType 1:1 mapping (enum names match)
- SpecimenSource hardcoded to CAPILLARY_BLOOD (glucometer is the primary flow)

**Risks/Considerations:**
- Existing DataStore JSON must deserialize gracefully — nullable `timestamp` in DTO handles this
- Meals synced with null time default to noon — acceptable approximation

---

## Iteration 1

**Implemented:** 2026-03-01
**Method:** Single-agent (effort score 6, sequential dependencies)

### Tasks Completed This Iteration
- Task 1: Add timestamp to SyncedMealSummary and persist during sync (HEA-158)
- Task 2: Create InferGlucoseDefaultsUseCase from last meal timestamp (HEA-159)
- Task 3: Wire glucose confirmation defaults from InferGlucoseDefaultsUseCase (HEA-160)

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/domain/model/SyncedMealSummary.kt` - Added `timestamp: Instant` field with `Instant.EPOCH` default
- `app/src/main/kotlin/com/healthhelper/app/domain/model/GlucoseDefaults.kt` - New result data class for inferred defaults
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/InferGlucoseDefaultsUseCase.kt` - New use case with time-threshold logic (<3h AFTER_MEAL, 3-8h UNKNOWN, >=8h FASTING)
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCase.kt` - Populate timestamp from FoodLogEntry.time + date during sync
- `app/src/main/kotlin/com/healthhelper/app/data/repository/DataStoreSettingsRepository.kt` - Added timestamp to SyncedMealDto serialization (backward-compatible)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModel.kt` - Inject InferGlucoseDefaultsUseCase, apply defaults on init
- `app/src/test/kotlin/com/healthhelper/app/domain/model/SyncedMealSummaryTest.kt` - Added timestamp tests
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SyncNutritionUseCaseTest.kt` - Added timestamp population tests
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/InferGlucoseDefaultsUseCaseTest.kt` - New test file (12 tests)
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/GlucoseConfirmationViewModelTest.kt` - Added smart defaults tests, updated existing tests to mock InferGlucoseDefaultsUseCase

### Linear Updates
- HEA-158: Todo → In Progress → Review
- HEA-159: Todo → In Progress → Review
- HEA-160: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 1 low (import ordering) — fixed. Also applied defensive `maxByOrNull` for most-recent meal lookup.
- verifier: All tests pass, zero warnings

### Continuation Status
All tasks completed.
