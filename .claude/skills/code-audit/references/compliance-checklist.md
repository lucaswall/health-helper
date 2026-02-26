# Code Audit Compliance Checklist

Detailed checks for code audit reviewers. Read CLAUDE.md for project-specific rules and accepted patterns.

## Security (OWASP Mobile + Web)

### Permissions & Platform Usage (OWASP Mobile M1)
- Health Connect permissions declared in AndroidManifest.xml
- Runtime permission requests use ActivityResultContracts
- Permission rationale shown before requesting (shouldShowRequestPermissionRationale)
- Graceful degradation when permissions denied
- No unnecessary permissions requested
- Permission groups scoped correctly (read vs write, specific health data types)

### Intent Security
- Explicit intents used for internal navigation (no implicit intents for sensitive operations)
- Intent filters validated — exported activities/receivers/services have proper protection
- PendingIntents use FLAG_IMMUTABLE (or FLAG_MUTABLE only when required)
- Deep links validated — no arbitrary URI handling without validation
- Intent extras validated before use (getStringExtra etc. checked for null/malformed)

### Data Storage Security (OWASP Mobile M2)
- No sensitive data in plain SharedPreferences — use EncryptedSharedPreferences for tokens, keys
- Room database not stored on external storage
- Health data encrypted at rest if cached locally
- No sensitive data in app backups (android:allowBackup="false" or backup rules exclude sensitive data)
- No sensitive data written to logs (health metrics, tokens, PII)
- Temporary files cleaned up after use
- WebView cache/cookies don't persist sensitive data (if WebViews used)

### Network Security (OWASP Mobile M3)
- Network security config enforces HTTPS (no cleartext traffic)
- Certificate pinning configured for API endpoints (if applicable)
- No custom TrustManagers that bypass SSL validation
- API responses validated before use
- OkHttp/Retrofit interceptors don't log sensitive headers/bodies in release builds

### Authentication (OWASP Mobile M4)
- Auth tokens stored in EncryptedSharedPreferences or Android Keystore
- Token refresh handled transparently (no expired token errors shown to users)
- Biometric authentication uses BiometricPrompt correctly (if applicable)
- Session timeout implemented for sensitive health data access
- Logout clears all cached sensitive data

### Secrets & Credentials (OWASP A02:2021)
- No hardcoded secrets, API keys, or passwords in Kotlin source
- Secrets loaded from BuildConfig fields (injected at build time) or secure storage
- No secrets in git history (check for accidental commits)
- Sensitive data not logged (passwords, tokens, health data PII)
- Debug/verbose modes don't expose secrets in release builds
- Error messages don't leak internal paths or stack traces

### Input Validation (OWASP A03:2021)
- User input sanitized before database operations (Room parameterized queries)
- No raw SQL queries with string concatenation (@RawQuery with user input)
- Deep link parameters validated and sanitized
- Content Provider URI validation (if Content Providers used)
- File path validation when accessing external files
- Health Connect data validated after reading (don't trust external data blindly)

### ProGuard/R8 Security (OWASP Mobile M8/M9)
- ProGuard/R8 enabled for release builds (minifyEnabled = true)
- Sensitive classes not excluded from obfuscation unnecessarily
- No debug-only code reachable in release builds (BuildConfig.DEBUG checks)
- APK/AAB signed with release key for production

## Type Safety

### Unsafe Casts
- No unsafe `as` casts without preceding `is` check — use `as?` for safe casts
- Platform types from Java interop explicitly annotated with nullability
- Generic type parameters properly constrained

### Type Guards
- Sealed class/interface `when` expressions are exhaustive (no `else` catch-all that hides missing branches)
- Nullable types handled with safe calls (`?.`), elvis (`?:`) or explicit null checks
- External data validated before use (API responses, Health Connect readings, Intent extras)
- Parsed data matches expected types (dates, numbers, enums)

### Runtime Validation
- Data class construction validated (init blocks or factory methods for invariants)
- Gradle build config values validated at startup
- Type mismatches detected early (fail fast with meaningful error messages)

## Logic & Correctness

### Common Bug Patterns
- Off-by-one errors in loops/indices
- Empty collection edge cases not handled (firstOrNull vs first, etc.)
- Integer overflow (Int vs Long for timestamps, large health data aggregations)
- Floating point comparison issues (use epsilon for health metrics comparison)
- Assignment vs comparison (`=` vs `==`)

### Boundary Conditions
- Empty inputs handled (null, empty strings, empty lists)
- Single-element collections work correctly
- Maximum size inputs don't break logic
- Negative numbers where only positive expected (health metrics)
- Zero values handled appropriately (0 steps, 0 heart rate readings)
- Unicode edge cases (user names, health data labels)

### State Management
- Race conditions in shared mutable state (MutableStateFlow, mutableStateOf)
- State mutations happen on correct thread/dispatcher
- Missing state cleanup after operations
- Stale state in Compose recomposition (remember with wrong keys)
- ViewModel state survives configuration changes correctly

## Memory Leaks

### Context & Activity Leaks
- No Activity/Context references stored in ViewModels or singletons
- No static references to Views, Activities, or Fragments
- Application context used for long-lived operations (not Activity context)
- Hilt scoping correct (@Singleton vs @ActivityScoped vs @ViewModelScoped)

### Coroutine Leaks
- Coroutines launched in viewModelScope or lifecycleScope (not GlobalScope)
- Long-running coroutines cancelled when ViewModel cleared or lifecycle destroyed
- Flow collection uses repeatOnLifecycle (not collect in onCreate)
- No coroutine references held beyond their intended scope

### Compose-Specific Leaks
- remember {} blocks don't capture Activity/Context references
- LaunchedEffect keys trigger relaunch correctly (not missing key changes)
- DisposableEffect onDispose cleans up resources
- derivedStateOf used appropriately (not recalculating on every recomposition)

### Collections & Caches
- In-memory caches have eviction policy or size limits
- Lists/Maps that grow without bounds monitored
- Bitmap/image caches properly sized and cleared

## Resource Leaks

### Database
- Room database accessed through repository pattern (not direct DAO access from UI)
- Cursor operations properly closed (Room handles this, but check raw queries)
- Database transactions properly committed/rolled back

### Streams & IO
- InputStreams/OutputStreams closed in finally blocks or use `.use {}` extension
- Health Connect client sessions properly managed
- File handles closed after use

### System Resources
- BroadcastReceivers unregistered when no longer needed
- LocationManager listeners removed when done
- Sensor listeners unregistered (if used for health monitoring)

## Coroutine Error Handling

### Unhandled Exceptions
- CoroutineExceptionHandler installed on supervisorScope or root coroutines
- launch {} blocks have try/catch for expected failures
- async {} results checked with await() in try/catch

### Structured Concurrency
- No GlobalScope usage — use viewModelScope, lifecycleScope, or custom supervised scopes
- SupervisorJob used when child failures should not cancel siblings
- withContext used for dispatcher switching (not launch(Dispatchers.IO))

### Error Propagation
- Exceptions in child coroutines propagated correctly to parent
- CancellationException not caught (or rethrown after cleanup)
- Flow exceptions handled with catch operator or in collector

## Lifecycle Awareness

### Flow Collection
- StateFlow/SharedFlow collected with repeatOnLifecycle in Activities/Fragments
- Compose collectAsStateWithLifecycle used (not collectAsState without lifecycle)
- No flow collection that continues when app is in background

### Component Lifecycle
- onResume work has corresponding cleanup in onPause
- onStart work has corresponding cleanup in onStop
- ViewModel.onCleared cancels all ongoing work
- Compose DisposableEffect used for resources that need cleanup

## Dependency Vulnerabilities

### Gradle Dependencies
- Run `./gradlew dependencyUpdates` or check gradle/libs.versions.toml
- Check for critical/high severity issues in known vulnerability databases
- Dependencies from trusted sources (Maven Central, Google Maven)

### Supply Chain
- No typosquatting package names in dependencies
- Version catalog (libs.versions.toml) used for centralized version management
- Dependencies pinned to specific versions (no dynamic versions like `+`)
- Gradle wrapper checksums verified (gradle-wrapper.properties)

## Logging

### Log Level Correctness

Verify each log statement uses the appropriate level:

| Level | Correct Usage | Anti-patterns |
|-------|---------------|---------------|
| **ERROR** | Operations that fail and need attention (API failures after retries, unrecoverable exceptions) | Logging expected exceptions, errors with auto-recovery |
| **WARN** | Unexpected but recoverable conditions (permission denied, slow API, deprecated API usage) | Normal operational events |
| **INFO** | Significant events (Health Connect sync complete, user actions, app lifecycle) | Excessive details, sensitive data |
| **DEBUG** | Implementation details for troubleshooting (API calls, data transformations, timing) | Enabled in release builds |
| **VERBOSE** | Very detailed tracing (only for development) | Present in release builds |

### Log Coverage

- **Error paths**: All catch blocks log the error with context
- **API boundaries**: Network requests and responses logged at DEBUG
- **State transitions**: Key business state changes logged at INFO
- **Health Connect operations**: Data sync, permission changes logged at INFO
- **App lifecycle**: Activity/Fragment lifecycle events logged at DEBUG

### Log Security

- **No health data PII**: Heart rate, steps, weight not logged with user identifiers
- **No tokens/keys**: API keys, auth tokens never logged
- **No sensitive data at INFO/WARN**: Only DEBUG/VERBOSE for detailed data (stripped in release)
- **Timber or project logger used**: No `println`, `System.out`, or `Log.d` with hardcoded tags
- **Release builds**: Timber tree planted only for debug builds (DebugTree)

### Search Patterns for Logging Issues

Use Grep tool to find potential logging issues:

**Wrong logger usage:**
- `println|System\.out|System\.err` — should use Timber or project logger
- `Log\.(d|v|i|w|e)\(` — should use Timber (no hardcoded TAG needed)
- `Timber\.(d|v)` in production-critical paths — may be stripped, verify tree setup

**Security issues:**
- `Timber\..*(password|secret|token|key|auth)` — potential secrets in logs
- `Timber\..*heartRate|Timber\..*steps|Timber\..*weight` — health data PII in logs

**Log overflow risks:**
- `for.*\{[^}]*Timber\.|while.*\{[^}]*Timber\.` — logging inside loops
- `\.forEach\s*\{[^}]*Timber\.` — logging in collection iterations

## Rate Limiting

### External API Quotas
- Health Connect API rate limits respected (batch reads, throttle sync)
- Third-party API rate limiting with backoff/retry
- Token/request budgeting for AI APIs (if applicable)

## Test Quality (if tests exist)

### Test Coverage
- Critical paths have test coverage (repositories, ViewModels, use cases)
- Edge cases tested (empty data, error states, permission denied)
- Error paths tested

### Test Validity
- Tests have meaningful assertions (not just "doesn't throw")
- No tests that always pass
- No duplicate tests
- MockK mocks don't hide real bugs (verify interactions where needed)

### Test Data
- No real patient/user health data in tests
- No production credentials in test files
- Test data clearly fictional
- Health Connect test data uses realistic but fake values

## Search Patterns

Use Grep tool (not bash grep) to find potential issues:

**Security:**
- `password|secret|api.?key|token` (case insensitive) — potential hardcoded secrets
- `Intent\(` — check for implicit intents
- `PendingIntent` — verify FLAG_IMMUTABLE
- `getSharedPreferences` — verify encrypted for sensitive data
- `MODE_WORLD_READABLE|MODE_WORLD_WRITABLE` — insecure file modes
- `exported\s*=\s*true` — exported component audit
- `@RawQuery` — potential SQL injection

**Type Safety:**
- `as [A-Z]` — unsafe type cast (without `is` check)
- `!!` — force unwrap (potential NullPointerException)
- `@Suppress` — suppressed warnings (verify justified)

**Memory/Resource:**
- `GlobalScope` — unstructured concurrency
- `static.*Context|companion.*Context` — potential context leak
- `remember\s*\{` — check for stale captures
- `mutableStateOf|MutableStateFlow` — shared mutable state

**Coroutines:**
- `launch\s*\{|async\s*\{` — verify structured scope
- `collect\s*\{` — verify lifecycle-aware collection
- `Dispatchers\.` — verify correct dispatcher usage
- `withContext\(` — verify dispatcher switching

**Logging:**
- `println|System\.out|System\.err` — should use proper logger
- `Log\.(d|v|i|w|e)\(` — should use Timber

## AI-Generated Code Risks

All code in this project is AI-assisted. Apply extra scrutiny for patterns AI models commonly introduce:

### Common AI Code Vulnerabilities
- **Logic errors** (75% more common) — verify branching, loop bounds, comparisons
- **Missing input validation** — AI often skips validation of external data
- **Hardcoded secrets** — AI trains on public repos full of exposed credentials
- **Code duplication** — AI frequently generates similar code instead of reusing existing abstractions
- **~45% of AI code contains security flaws** — treat all AI output as untrusted until reviewed

### AI-Specific Anti-patterns
- **Hallucinated APIs** — methods, functions, or library features that don't exist. Verify imports resolve to real exports.
- **Hallucinated dependencies** — non-existent Gradle dependencies. Verify every dependency in libs.versions.toml references a real, trusted library.
- **Outdated patterns** — AI may use deprecated Android APIs or old patterns (e.g., AsyncTask, Loader)
- **Over-engineering** — unnecessary abstractions, extra error handling for impossible scenarios
- **Missing business context** — AI may not understand health domain constraints, leading to incorrect logic
- **Wrong Compose patterns** — AI may mix View-based and Compose patterns incorrectly

### Search Patterns for AI Code Issues
- Check `implementation` in build.gradle.kts for dependencies that don't exist in Maven Central
- Look for API method calls that don't match the library's actual interface
- Look for copied patterns (similar code blocks in multiple files that should be shared)
- Check for deprecated API usage (e.g., `onActivityResult`, `AsyncTask`, `Loader`)
