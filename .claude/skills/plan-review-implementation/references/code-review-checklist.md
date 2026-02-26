# Comprehensive Code Review Checklist

Reference for plan-review-implementation skill.

## Priority Tiers

| Tier | Severity | Examples |
|------|----------|----------|
| **CRITICAL** | Immediate fix required | Security vulnerabilities, data corruption, crashes |
| **HIGH** | Fix before merge | Logic errors, race conditions, auth issues |
| **MEDIUM** | Should fix | Edge cases, type safety, error handling gaps |
| **LOW** | Nice to have | Code style, documentation, minor improvements |

## Security Checks (OWASP-Based — Mobile Focus)

### Input Validation
- [ ] All user inputs validated before processing
- [ ] Allowlist validation preferred over blocklist
- [ ] Intent injection prevention (validate incoming Intent extras/data)
- [ ] Path traversal prevention (`../` sequences blocked in file operations)
- [ ] SQL injection prevention (use Room parameterized queries, never raw SQL with user input)
- [ ] Content provider injection prevention (validate URI parameters)
- [ ] File upload/import validation (content type, size)
- [ ] Input length limits enforced
- [ ] Special characters handled appropriately
- [ ] Deep link validation (malicious URIs don't bypass navigation guards)

### Authentication & Permissions
- [ ] Health Connect permissions requested before data access
- [ ] Permission denial handled gracefully (explain why, offer retry)
- [ ] Runtime permissions checked before every sensitive operation (not just on first launch)
- [ ] Biometric/device credential checks where required
- [ ] Token/session validation for any backend communication

### Authorization & Data Access
- [ ] Health Connect data access scoped to declared permissions only
- [ ] No over-requesting Health Connect permission types
- [ ] Data filtering respects user preferences and time ranges
- [ ] Background data access follows Android restrictions

### Secrets & Credentials
- [ ] No hardcoded secrets, API keys, passwords
- [ ] Secrets loaded from BuildConfig, encrypted SharedPreferences, or secret manager
- [ ] No secrets in git history
- [ ] Sensitive data not logged (use `Log.d` only in debug builds, never log tokens/health data)
- [ ] Error messages don't leak internal info
- [ ] `local.properties` and `*.jks`/`*.keystore` files excluded from VCS

### Secure Storage
- [ ] Sensitive data stored in EncryptedSharedPreferences or Android Keystore
- [ ] Health data not cached in plaintext on disk
- [ ] WebView data (if any) cleared appropriately
- [ ] No sensitive data in app backups (`android:allowBackup="false"` or backup rules)

## Logic & Correctness

### Common Bug Patterns
- [ ] Off-by-one errors in loops/indices
- [ ] Null safety — nullable types handled with `?.`, `?:`, or explicit checks (no `!!` without justification)
- [ ] Empty collection edge cases
- [ ] Floating point comparison issues
- [ ] String encoding issues (UTF-8)
- [ ] Timezone handling in dates (Health Connect uses `Instant`/`ZonedDateTime`)
- [ ] Boolean logic errors (De Morgan's law violations)

### Boundary Conditions
- [ ] Empty inputs handled
- [ ] Single-element collections
- [ ] Maximum size inputs
- [ ] Negative numbers where unexpected
- [ ] Zero values
- [ ] Unicode edge cases (emojis, RTL, combining chars)
- [ ] Very long strings
- [ ] Deeply nested objects

### State Management
- [ ] Race conditions in shared state
- [ ] StateFlow emissions in wrong order
- [ ] Missing state cleanup on ViewModel clear
- [ ] Stale state from configuration changes (screen rotation)
- [ ] Concurrent modification issues in collections

## Coroutines & Concurrency

### Coroutine Handling
- [ ] All suspend functions called within appropriate CoroutineScope
- [ ] `viewModelScope` used for ViewModel coroutines (auto-cancelled on clear)
- [ ] `lifecycleScope` used for UI coroutines (auto-cancelled on destroy)
- [ ] Errors caught with try/catch or CoroutineExceptionHandler
- [ ] No fire-and-forget `launch` without error handling
- [ ] `withContext(Dispatchers.IO)` for disk/network operations
- [ ] Structured concurrency respected — no `GlobalScope` usage

### Race Conditions
- [ ] Shared mutable state protected (Mutex, atomic operations, or single-threaded dispatcher)
- [ ] Check-then-act patterns atomicized
- [ ] Concurrent writes to same resource guarded
- [ ] Flow collection not duplicated (multiple collectors on same SharedFlow)

### Deadlocks & Hangs
- [ ] External API calls have timeouts
- [ ] Health Connect operations have appropriate timeouts
- [ ] No blocking calls on Main dispatcher
- [ ] `withTimeout` used for operations that could hang

## Resource Management

### Memory Leaks
- [ ] No Activity/Fragment references held in long-lived objects
- [ ] Coroutine scopes cancelled appropriately (lifecycle-aware)
- [ ] Caches have eviction/size limits
- [ ] Bitmap/image resources recycled when done
- [ ] Collections don't grow unbounded
- [ ] No context leaks through lambda captures

### Resource Leaks
- [ ] Closeable resources use `.use {}` blocks
- [ ] Health Connect client sessions managed properly
- [ ] ContentResolver cursors closed
- [ ] InputStreams/OutputStreams closed in finally blocks

## Error Handling

### Error Propagation
- [ ] Errors not swallowed silently
- [ ] Empty catch blocks justified with comment
- [ ] Errors logged with context
- [ ] Original exception preserved when wrapping (`cause` parameter)
- [ ] Appropriate exception types used (domain exceptions, not generic Exception)

### Error Recovery
- [ ] Retry logic for transient failures (network, Health Connect unavailable)
- [ ] Backoff strategies prevent thundering herd
- [ ] Fallback behavior for non-critical features
- [ ] Partial failures handled gracefully

### Error Information
- [ ] Error messages are actionable
- [ ] No sensitive data in error messages
- [ ] Errors logged for debugging

## Type Safety

### Kotlin Type Checks
- [ ] No unsafe casts (`as` without `is` check or `as?`)
- [ ] Sealed classes/interfaces exhaustively matched in `when` expressions
- [ ] Nullable types handled explicitly (no `!!` without justification)
- [ ] External data validated/parsed (Health Connect records, API responses)
- [ ] Type assertions justified and correct
- [ ] Generic type parameters bounded appropriately

## Test Quality (When Tests Are Changed)

### Test Validity
- [ ] Tests have meaningful assertions
- [ ] Not just "doesn't throw" tests
- [ ] Assertions match test description
- [ ] MockK mocks don't hide real bugs (verify important interactions)
- [ ] Edge cases covered
- [ ] Error paths tested

### Test Independence
- [ ] Tests don't depend on execution order
- [ ] Shared state cleaned up (`@BeforeEach`/`@AfterEach`)
- [ ] No flaky timing dependencies (use `runTest`/`advanceUntilIdle` for coroutines)

### Test Data
- [ ] No real customer/user data
- [ ] No production credentials
- [ ] Test data clearly fictional

## Logging Quality

### Logger Usage
- [ ] Proper logger used (Timber or Android `Log` — not `println` in production code)
- [ ] Log levels appropriate (ERROR for failures, INFO for state changes, DEBUG for routine operations)
- [ ] Error catch blocks have logging with context
- [ ] Repository/data layer modules doing significant work have logging (no blind spots)

### Structured Logging
- [ ] Logs include operation context (action being performed, relevant IDs)
- [ ] External API/Health Connect calls log duration
- [ ] No sensitive data in logs (tokens, passwords, API keys, raw health data)

### Double-Logging Prevention
- [ ] Same error not logged at repository layer AND ViewModel
- [ ] Errors passed to error-handling utilities not also manually logged
- [ ] Catch-and-rethrow doesn't produce duplicate log entries

### Log Overflow
- [ ] No logging inside tight loops or collection iterations
- [ ] Large objects truncated or summarized, not logged in full

## Project-Specific (CLAUDE.md)

Always check CLAUDE.md for project-specific rules including:
- Import conventions
- Error handling patterns
- Testing requirements
- Naming conventions (UseCase suffix, Repository suffix, Screen suffix, ViewModel suffix)
- DI patterns (Hilt constructor injection)
- Any other project-specific standards

## Health Connect Integration (When Changed Files Touch Health Connect Code)

When reviewing changes to Health Connect integration code (repositories, data sources, or ViewModels accessing health data), verify:

### Permission Handling
- [ ] Permissions checked before every read/write operation
- [ ] Permission denial handled gracefully (not crash)
- [ ] `PermissionController` used correctly for checking granted permissions
- [ ] Privacy policy activity alias declared in manifest

### Record Operations
- [ ] `Metadata.manualEntry()` used for user-entered records (not constructor)
- [ ] `Metadata.autoRecorded()` used for sensor data
- [ ] Time ranges validated (start before end, not in future for manual entry)
- [ ] Record deduplication considered (changelog-based sync preferred)
- [ ] Rate limits respected (no delete-and-rewrite patterns)

### Data Integrity
- [ ] Health data types match expected units (steps as Long, heart rate as Double BPM)
- [ ] Time zones handled correctly (Health Connect uses `Instant` and `ZoneOffset`)
- [ ] Large data sets paginated appropriately
- [ ] Empty results handled (no health data available)

## AI-Generated Code Risks

All code in this project is AI-assisted. Apply extra scrutiny for these patterns:
- **Logic errors** (75% more common in AI code) — verify branching, loop bounds, comparisons
- **Security flaws** (~45% of AI code contains them) — treat all AI output as untrusted until reviewed
- **Code duplication** (frequent AI pattern) — similar code that should use shared abstractions
- **Hallucinated APIs** (non-existent methods/libraries) — verify imports resolve to real exports
- **Hallucinated packages** — non-existent dependencies. Verify every dependency references a real artifact in `gradle/libs.versions.toml`.
- **Outdated patterns** — AI may use deprecated Android APIs or old patterns from training data
- **Over-engineering** — unnecessary abstractions or error handling for impossible scenarios
- **Missing null safety** — AI sometimes generates Java-style null handling instead of idiomatic Kotlin
