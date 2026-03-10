# Implementation Plan

**Created:** 2026-03-10
**Source:** Bug report: Transient network exceptions (IOException, UnresolvedAddressException, ConnectTimeoutException) logged at Timber.e flood Sentry with non-actionable errors
**Sentry Issues:** [HEALTH-HELPER-6](https://lucas-wall.sentry.io/issues/HEALTH-HELPER-6), [HEALTH-HELPER-7](https://lucas-wall.sentry.io/issues/HEALTH-HELPER-7), [HEALTH-HELPER-8](https://lucas-wall.sentry.io/issues/HEALTH-HELPER-8)
**Linear Issues:** [HEA-164](https://linear.app/lw-claude/issue/HEA-164/bug-network-exceptions-logged-at-timbere-flood-sentry)
**Branch:** fix/HEA-164-network-exceptions-log-level

## Context Gathered

### Codebase Analysis
- **Related files:**
  - `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — catch-all at line 95-98 uses `Timber.e` for all non-serialization, non-cancellation exceptions
  - `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` — existing `networkFailure()` test at line 177 verifies IOException returns failure result
- **Existing patterns:**
  - HTTP status errors already differentiate log levels: 401/429/5xx use `Timber.w` (lines 53-56), others use `Timber.e` (line 55)
  - This pattern was established in HEA-157 (released) which fixed 5xx flooding but didn't address network-level exceptions
- **Test conventions:**
  - MockEngine-based tests, `runTest`, no Timber tree planting for log level verification

### MCP Context
- **MCPs used:** Sentry, Linear
- **Findings:**
  - 3 unresolved Sentry issues (HEALTH-HELPER-6/7/8) — all transient network errors from `getFoodLog()`, same device/user, server was intermittently unreachable Mar 7-9
  - All Seer actionability: `super_low`
  - HEA-157 (Released) previously fixed 5xx HTTP log level but not network-level exceptions

### Investigation

**Bug report:** Transient network errors (DNS failure, connection timeout, connection reset) from food-scanner API are logged at `Timber.e`, creating Sentry error events for non-actionable server outages.
**Classification:** Integration / Low / Data layer (API client)
**Root cause:** The catch-all block in `FoodScannerApiClient.getFoodLog()` (line 95-98) uses `Timber.e` for ALL exceptions. Network failures (`IOException`, `UnresolvedAddressException`) are expected transient conditions that should use `Timber.w` to avoid Sentry noise. `SerializationException` is already handled separately at line 92-94 with `Timber.e` (correct — parse errors indicate a real bug).
**Evidence:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt:95-98` — catch-all uses `Timber.e` for network exceptions
- HEALTH-HELPER-6: `UnresolvedAddressException` (7 events) — `java.nio.channels.UnresolvedAddressException` extends `IllegalStateException`
- HEALTH-HELPER-7: `ConnectTimeoutException` (10 events) — Ktor timeout, wraps network failure
- HEALTH-HELPER-8: `IOException: Connection reset by peer` (1 event) — server dropped connection
**Impact:** Sentry dashboard polluted with 18 non-actionable events from a single server outage window. Obscures real bugs.

## Tasks

### Task 1: Downgrade transient network exceptions from Timber.e to Timber.w
**Linear Issue:** [HEA-164](https://linear.app/lw-claude/issue/HEA-164/bug-network-exceptions-logged-at-timbere-flood-sentry)
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` (modify)
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` (modify)

**Steps:**
1. Write test in `FoodScannerApiClientTest.kt` for `UnresolvedAddressException` — verify it returns `Result.failure` (behavioral parity with existing `networkFailure` test, but covers the specific exception type from Sentry)
2. Run verifier (expect pass — behavior already works, test just documents it)
3. Modify the catch block in `FoodScannerApiClient.kt` (line 95-98):
   - Check if exception is `java.io.IOException` or `java.nio.channels.UnresolvedAddressException`
   - Use `Timber.w` for these known transient network exceptions
   - Keep `Timber.e` for all other unexpected exceptions
   - No behavioral change — still returns `Result.failure(e)` in all cases
4. Run verifier (expect pass — no behavioral change)

**Notes:**
- `IOException` covers: connection reset, connection refused, socket timeout, and Ktor's `ConnectTimeoutException`
- `UnresolvedAddressException` extends `IllegalStateException` (not `IOException`), so needs explicit check
- Follow the existing pattern from lines 53-56 where `Timber.w` is used for expected error conditions

## Post-Implementation Checklist
1. Run `bug-hunter` agent — Review changes for bugs
2. Run `verifier` agent — Verify all tests pass and zero warnings

---

## Plan Summary

**Objective:** Stop transient network exceptions from flooding Sentry by downgrading them from Timber.e to Timber.w

**Approach:** Add exception type checks in the catch-all block of `FoodScannerApiClient.getFoodLog()` to use `Timber.w` for `IOException` and `UnresolvedAddressException` (known transient network conditions), keeping `Timber.e` for truly unexpected exceptions. This follows the precedent set by HEA-157 which already downgraded 5xx HTTP responses.

**Scope:**
- Tasks: 1
- Files affected: 2 (1 modified source, 1 modified test)
- New tests: 1

**Key Decisions:**
- `IOException` covers most network failures including Ktor's `ConnectTimeoutException`
- `UnresolvedAddressException` needs separate check since it extends `IllegalStateException`
- No behavioral change — only log level changes

**Risks/Considerations:**
- Truly unexpected `IOException` subtypes will now be logged at warning level — acceptable tradeoff since all exceptions in this catch block are from network calls

---

## Iteration 1

**Implemented:** 2026-03-10
**Method:** Single-agent (effort score 1, single work unit)

### Tasks Completed This Iteration
- Task 1: Downgrade transient network exceptions from Timber.e to Timber.w

### Files Modified
- `app/src/main/kotlin/com/healthhelper/app/data/api/FoodScannerApiClient.kt` — Added IOException/UnresolvedAddressException check to use Timber.w instead of Timber.e
- `app/src/test/kotlin/com/healthhelper/app/data/api/FoodScannerApiClientTest.kt` — Added UnresolvedAddressException test
- `app/src/test/kotlin/com/healthhelper/app/data/repository/ETagStorageTest.kt` — Fixed pre-existing bug: hardcoded dates replaced with relative dates to prevent 7-day prune cutoff failures

### Linear Updates
- HEA-164: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Passed, no bugs found
- verifier: All tests pass, zero warnings, build successful

### Continuation Status
All tasks completed.
