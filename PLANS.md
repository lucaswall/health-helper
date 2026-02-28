# Fix Plan: BP Scanner Crash on Network Errors + System Camera UI

**Date:** 2026-02-28
**Status:** Planning
**Linear Issues:** [HEA-142](https://linear.app/lw-claude/issue/HEA-142), [HEA-143](https://linear.app/lw-claude/issue/HEA-143)
**Branch:** fix/HEA-142-bp-scanner-crash-and-camera-ui

## Investigation

### Bug Report
Blood pressure scanner photo feature "fails constantly." Device crash logs (dropbox) show two `SecurityException` crashes on 2026-02-27 when the Anthropic API call attempts DNS resolution. Additionally, the custom CameraX viewfinder lacks the standard camera experience — no photo preview/confirm, no gallery picker.

### Classification
- **Type:** Runtime Crash + UI Improvement
- **Severity:** High (crash), Medium (UX)
- **Affected Area:** `AnthropicApiClient`, `CameraCaptureScreen`, `CameraCaptureViewModel`, `AppModule`

### Root Cause Analysis

#### Bug 1: OkHttp AsyncCall re-throws non-IOException, crashing the app

The app uses Ktor 3.1.1 with OkHttp 4.12.0 engine (`AppModule.kt:86`). Ktor's OkHttp engine dispatches HTTP requests via OkHttp's `enqueue()` (async), running on OkHttp's internal dispatcher thread pool.

When DNS resolution fails with `EPERM` (network unavailable, restricted mode, etc.), Java wraps it as `SecurityException` — a `RuntimeException`, **not** an `IOException`. OkHttp 4.12.0's `AsyncCall.run()` catches non-IOExceptions in its generic `catch(t: Throwable)` block, calls `responseCallback.onFailure(...)` to notify Ktor, then **re-throws `t`** on the OkHttp dispatcher thread. This causes an uncaught exception, triggering Android's `KillApplicationHandler` — killing the app instantly.

The try-catch in `CameraCaptureViewModel.kt:90` and `AnthropicApiClient.kt:120` are on the coroutine thread and cannot catch exceptions thrown on OkHttp's dispatcher thread.

The `INTERNET` permission IS declared (`AndroidManifest.xml:6`) and granted. The "missing INTERNET permission?" message is a misleading Android error string for `EPERM` on `android_getaddrinfo`.

#### Evidence
- **Device crash log (dropbox):** Two crashes at 2026-02-27 20:28:48 and 20:28:54 (UID 10623, v1, 6 seconds apart — second was auto-restart)
- **Stack trace:** `SecurityException` → `GaiException(EAI_NODATA)` → `ErrnoException(EPERM)` propagating through `RealCall$AsyncCall.run(RealCall.kt:517)` → `ThreadPoolExecutor` → `Thread.run`
- **Current install:** UID 10625 (v1.0.0), `INTERNET: granted=true` — no crashes from this UID, but the code vulnerability remains
- **Dependency chain:** `ktor-client-okhttp:3.1.1` → `okhttp:4.12.0`

#### Bug 2: Custom CameraX UI lacks standard photo experience

`CameraCaptureScreen.kt` uses CameraX `PreviewView` with a custom capture button. This has no photo preview/confirm after capture (sends image directly to API), no gallery option, and adds 5 CameraX dependencies.

#### Bug 3: Image format fallback sends raw non-JPEG bytes with JPEG media type

`CameraCaptureViewModel.kt:139-141` — if `BitmapFactory` fails to decode an image (corrupt file, unsupported format), the catch block falls back to sending the **original raw bytes** to the API with `mediaType: "image/jpeg"` hardcoded in `AnthropicApiClient.kt:70`. For an HEIC or WEBP file from the gallery (or via share), this sends non-JPEG bytes labeled as JPEG, causing the API to reject the image silently.

The happy path works: `BitmapFactory` decodes HEIC/WEBP/PNG successfully, and `compress(JPEG, 85%)` converts to JPEG. But the error fallback is wrong — it should fail with a user-facing error rather than sending garbage to the API.

#### Related Code
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt:86` — `HttpClient(OkHttp)` engine configuration
- `gradle/libs.versions.toml:18,52` — `ktor = "3.1.1"`, `ktor-client-okhttp` dependency
- `gradle/libs.versions.toml:25,63-67` — `camerax = "1.5.1"`, all 5 camera library entries
- `app/build.gradle.kts:85-90` — 5 CameraX implementation dependencies
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` — full 200-line CameraX implementation
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt:54-96` — photo capture handling
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt:108-143` — `resizeImageIfNeeded` with raw-bytes fallback
- `app/src/main/kotlin/com/healthhelper/app/data/api/AnthropicApiClient.kt:70,83-88` — hardcoded `image/jpeg` media type, Ktor HTTP POST
- `app/src/main/kotlin/com/healthhelper/app/MainActivity.kt` — single-activity entry point, no intent handling

### Impact
- App crashes when network is unavailable/transitioning during any HTTP call (BP scan or food sync)
- Same OkHttp vulnerability affects `FoodScannerApiClient`
- Poor camera UX: no preview, no retake, no gallery option
- Undecodable images silently sent as wrong format, causing confusing API errors
- 5 unnecessary CameraX dependencies add APK size and complexity

## Fix Plan (TDD Approach)

### Step 1: Switch Ktor engine from OkHttp to CIO
**File:** `gradle/libs.versions.toml` (modify)
**File:** `app/build.gradle.kts` (modify)
**File:** `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` (modify)
**Test:** Existing tests (verify pass — engine swap is transparent)

**Behavior:**
- Replace `ktor-client-okhttp` with `ktor-client-cio` in version catalog and build script
- In `AppModule.kt:86`, change `HttpClient(OkHttp)` to `HttpClient(CIO)` with matching import (`io.ktor.client.engine.cio.CIO`)
- CIO engine runs entirely on Kotlin coroutines — no OkHttp dispatcher thread pool, no re-throw vulnerability
- All network exceptions propagate through the coroutine and are caught by existing try-catch blocks
- Remove OkHttp-related entries from version catalog (`ktor-client-okhttp`, `okhttp-sse` if present)

**Tests:**
1. All existing `AnthropicApiClientTest` tests pass (uses Ktor mock engine, unaffected by engine swap)
2. All existing `FoodScannerApiClientTest` tests pass (uses Ktor mock engine)
3. All existing `CameraCaptureViewModelTest` tests pass (mocks API client)

### Step 2: Fix image decode fallback — fail instead of sending raw bytes
**File:** `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt` (modify)
**Test:** `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModelTest.kt` (modify)

**Behavior:**
- Rename `resizeImageIfNeeded` to `prepareImageForApi` to reflect its broader responsibility
- Change the catch block: instead of returning the original raw bytes on decode failure, return `null`
- In `onPhotoCaptured`, check for `null` from `prepareImageForApi`. If null, set error state: "Could not process image. Please try a different photo."
- This ensures only valid JPEG bytes are ever sent to the API, regardless of input format (HEIC, WEBP, PNG, or corrupt data)

**Tests:**
1. New test: `prepareImageForApi` returns null for undecodable bytes → ViewModel sets error message "Could not process image. Please try a different photo." and clears `isProcessing`
2. Existing tests: all pass (they use `testImageBytes = byteArrayOf(1, 2, 3)` which won't decode, but the mock bypasses `prepareImageForApi` since `anthropicApiClient.parseBloodPressureImage` is mocked). Verify this is still the case — if the test calls the real resize path, the mock needs adjustment.

### Step 3: Replace CameraCaptureScreen with system camera only
**File:** `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` (rewrite)
**Pattern:** Follow `SyncScreen.kt:65-72` for `rememberLauncherForActivityResult` usage

**Behavior:**

Rewrite the screen to use the system camera intent instead of CameraX viewfinder:

- **Initial state:** Screen with title "Scan Blood Pressure" and a single "Take Photo" button centered on screen. No camera viewfinder.
- **"Take Photo"** uses `ActivityResultContracts.TakePicture()` — launches the system camera app with a `FileProvider` temp URI. The system camera handles preview, retake, and confirm natively. On success, reads image bytes from the URI via `context.contentResolver.openInputStream()` and calls `viewModel.onPhotoCaptured(bytes)`.
- **Processing state:** Shows `CircularProgressIndicator` and "Analyzing..." text while `isProcessing == true`.
- **Error state:** Shows error message and "Try Again" button (same behavior as current).
- **Navigation:** On success, navigates to `BpConfirmationScreen` via existing `navigateToConfirmation` SharedFlow.
- **Uri-to-bytes conversion** happens in the Composable layer (using `LocalContext.current`), keeping the ViewModel clean with no `Context` dependency. The existing `onPhotoCaptured(imageBytes: ByteArray)` signature is unchanged.
- Remove the `capturePhoto()` private helper function and all CameraX imports.

**Tests:**
1. All existing `CameraCaptureViewModelTest` tests pass unchanged (they call `onPhotoCaptured(bytes)` directly)

### Step 4: Add share receiver for image sharing into BP flow
**File:** `app/src/main/AndroidManifest.xml` (modify)
**File:** `app/src/main/kotlin/com/healthhelper/app/MainActivity.kt` (modify)
**File:** `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` (modify)

**Behavior:**

Add an `ACTION_SEND` intent filter so users can share photos from any app (Gallery, Google Photos, WhatsApp, file managers) to HealthHelper for BP scanning.

**Manifest changes:**
- Add a second `<intent-filter>` on `MainActivity` for `android.intent.action.SEND` with `<data android:mimeType="image/*" />`
- Add `android:label="Scan Blood Pressure"` on the intent filter — this is what appears in the share sheet. Designed for future extensibility (can add "Scan Glucose" later as a separate filter).

**MainActivity changes:**
- In `onCreate`, after `enableEdgeToEdge()`, check if `intent.action == Intent.ACTION_SEND` and `intent.type?.startsWith("image/") == true`
- If it's a share intent, extract the image URI from `intent.getParcelableExtra(Intent.EXTRA_STREAM)` (use the compat version for API 33+)
- Pass the URI as a string to `AppNavigation` so it can route directly to the processing flow

**AppNavigation changes:**
- Accept an optional `sharedImageUri: String?` parameter
- If `sharedImageUri` is non-null, set it as the start destination argument or use a `LaunchedEffect` to navigate to the camera-bp route with the pre-loaded image URI
- The `CameraCaptureScreen` accepts an optional `sharedImageUri` parameter. When present, it skips the "Take Photo" button and immediately reads bytes from the URI and calls `viewModel.onPhotoCaptured(bytes)` — going straight to processing → confirmation.

**Tests:**
1. No unit tests for intent handling — verified by manual test
2. Existing navigation tests (if any) pass

### Step 5: Add FileProvider for camera temp files
**File:** `app/src/main/AndroidManifest.xml` (modify)
**File:** `app/src/main/res/xml/file_paths.xml` (create)

**Behavior:**
- `TakePicture()` requires a `Uri` for the system camera to write the photo to
- Register a `FileProvider` in the manifest under `<application>` with authority `${applicationId}.fileprovider`
- Create `file_paths.xml` with a `<cache-path name="bp_images" path="bp_images/" />` entry
- The screen creates a temp file in `cacheDir/bp_images/`, gets a FileProvider URI, passes it to `TakePicture()`
- Temp files are cleaned up after processing

**Tests:**
1. No unit tests needed — manifest/XML configuration verified by build + manual test

### Step 6: Integrate Sentry for crash reporting, performance monitoring, and Compose instrumentation
**File:** `gradle/libs.versions.toml` (modify)
**File:** `app/build.gradle.kts` (modify)
**File:** `app/src/main/AndroidManifest.xml` (modify)
**File:** `app/src/main/kotlin/com/healthhelper/app/HealthHelperApp.kt` (modify)
**File:** `sentry.properties` (create)
**File:** `.gitignore` (modify)

**Manual setup required (before implementation):**
1. Create a Sentry account at https://sentry.io (free tier: 5,000 errors/month) — DONE
2. Create a new project: Platform = Android, name = `health-helper` — DONE
3. DSN obtained: `https://2ce7da0da96c55aa5a06a5be24b837df@o4510966037086208.ingest.us.sentry.io/4510966055108608`
4. Org Auth Token generated and stored in `sentry.properties` (gitignored) — DONE
5. Org slug: `lucas-wall`, Project slug: `health-helper` — DONE

**Secrets handling:**
- **DSN** is NOT a secret — it only allows sending events, not reading them. Safe to commit in `AndroidManifest.xml`. Sentry's official stance: [DSNs are safe to keep public](https://sentry.zendesk.com/hc/en-us/articles/26741783759899).
- **Auth Token** IS a secret — it has write access to releases/mappings. Store it in `sentry.properties` (gitignored) or as env var `SENTRY_AUTH_TOKEN`. The Sentry Gradle plugin reads from `sentry.properties` automatically.
- **`sentry.properties`** must be added to `.gitignore` (alongside `local.properties`)

**Behavior:**

*Version catalog additions (`libs.versions.toml`):*
- Add version: `sentry = "8.27.1"`, `sentryGradlePlugin = "5.12.2"`
- Add libraries: `sentry-android` (core SDK + all auto-integrations), `sentry-compose-android` (Compose instrumentation)
- Add plugin: `sentry` with id `io.sentry.android.gradle`

*Build script (`app/build.gradle.kts`):*
- Apply `alias(libs.plugins.sentry)` in plugins block
- Add `implementation(libs.sentry.android)` and `implementation(libs.sentry.compose.android)`
- Configure the Sentry Gradle plugin:
  - `autoUploadProguardMapping.set(true)` — uploads R8 mapping files on release builds for deobfuscated stack traces
  - `autoInstallation.enabled.set(false)` — we manage dependencies explicitly via version catalog
  - `tracingInstrumentation.enabled.set(true)` — auto-instruments HTTP calls and DB queries
  - `autoUploadSourceContext.set(true)` — uploads source context for richer error display

*Manifest (`AndroidManifest.xml`):*
- Add `<meta-data android:name="io.sentry.dsn" android:value="https://2ce7da0da96c55aa5a06a5be24b837df@o4510966037086208.ingest.us.sentry.io/4510966055108608" />`
- Add `<meta-data android:name="io.sentry.traces-sample-rate" android:value="1.0" />` — capture all transactions (fine for personal app volume)
- Add `<meta-data android:name="io.sentry.anr.enable" android:value="true" />` — ANR detection
- Add `<meta-data android:name="io.sentry.session-tracking.enable" android:value="true" />` — release health / crash-free rate

*Application class (`HealthHelperApp.kt`):*
- Add a `SentryTimberTree` (from `sentry-android`) to Timber — this forwards all `Timber.w()` and `Timber.e()` calls as Sentry breadcrumbs, and `Timber.e()` with exceptions as Sentry events
- Plant it unconditionally (both debug and release) alongside the existing `DebugTree` (which stays debug-only)
- Set the `environment` tag: `Sentry.configureScope { it.setTag("environment", if (BuildConfig.DEBUG) "debug" else "release") }` — allows filtering debug vs release crashes in the dashboard

*`sentry.properties` (create, gitignored):*
```
defaults.org=your-org-slug
defaults.project=health-helper
auth.token=sntrys_YOUR_AUTH_TOKEN_HERE
```

*`.gitignore` additions:*
- Add `sentry.properties` entry

**What Sentry captures with this setup:**
- Unhandled exceptions (crashes) with full deobfuscated stack traces
- ANRs (Application Not Responding)
- Timber warnings/errors as breadcrumbs (trail of events before a crash)
- Timber errors with exceptions as Sentry error events
- HTTP transaction performance (Ktor calls)
- Compose render performance (via `sentry-compose-android`)
- Session/release health (crash-free sessions percentage)
- Device info, OS version, app version automatically attached
- Works in both debug and release builds, filterable by `environment` tag

**Tests:**
1. `./gradlew assembleDebug` succeeds — Sentry SDK initializes without a valid DSN (sends no events, no crash)
2. All existing tests pass — Sentry auto-initializes via manifest, no test setup needed (it no-ops without a real DSN)

**Sentry MCP setup (in this step, not optional):**

The [Sentry remote MCP server](https://github.com/getsentry/sentry-mcp) uses OAuth — no auth token needed. You authenticate via browser when first connecting.

Add to `.claude/settings.json` in the top-level object:
```json
"mcpServers": {
  "sentry": {
    "type": "http",
    "url": "https://mcp.sentry.dev/mcp"
  }
}
```

Or run: `claude mcp add --transport http sentry https://mcp.sentry.dev/mcp`

On first use, Claude Code will prompt OAuth login via browser to authorize the Sentry connection. If the token expires, run `/mcp` → select Sentry → "Clear authentication" → "Authenticate" to re-trigger.

Add permissions for Sentry MCP tools to the `permissions.allow` array in `.claude/settings.json`:
```
"mcp__sentry__list_projects",
"mcp__sentry__list_project_issues",
"mcp__sentry__list_issue_events",
"mcp__sentry__get_sentry_issue",
"mcp__sentry__get_sentry_event",
"mcp__sentry__resolve_short_id",
"mcp__sentry__list_error_events_in_project",
"mcp__sentry__create_project",
"mcp__sentry__list_organization_replays"
```

### Step 7: Remove CameraX dependencies
**File:** `gradle/libs.versions.toml` (modify)
**File:** `app/build.gradle.kts` (modify)

**Behavior:**
- Remove `camerax` version entry from version catalog
- Remove all 5 camera library entries: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose`, `camera-view`
- Remove corresponding 5 `implementation` lines from `app/build.gradle.kts:85-90`
- Verify no remaining imports reference `androidx.camera.*`

**Tests:**
1. `./gradlew assembleDebug` succeeds with no CameraX references
2. No import errors in remaining source files

### Step 8: Verify
- [ ] All new tests pass
- [ ] All existing tests pass (`./gradlew test`)
- [ ] Kotlin compiles without errors (`./gradlew assembleDebug`)
- [ ] Lint passes
- [ ] Build succeeds
- [ ] Manual test: Take photo via system camera → processes → shows confirmation
- [ ] Manual test: Share photo from gallery → app opens → processes → shows confirmation
- [ ] Manual test: Share HEIC from gallery → decoded and processed correctly
- [ ] Manual test: Airplane mode → take photo → shows error message (NOT crash)
- [ ] Manual test: Share sheet shows "Scan Blood Pressure" label for HealthHelper
- [ ] Manual test: After adding DSN, verify Sentry receives a test event (force a crash or call `Sentry.captureMessage("test")`)
- [ ] Manual test: Check Sentry dashboard shows debug/release environment tag
- [ ] Verify `sentry.properties` is NOT committed to git

## Notes
- The CIO engine uses Java's SSLEngine instead of OkHttp's TLS stack. For standard HTTPS to `api.anthropic.com`, this is transparent. `network_security_config.xml` already has `cleartextTrafficPermitted="false"`.
- The CAMERA permission remains needed — system camera apps on some devices check caller permissions.
- Existing `FoodScannerApiClient` also benefits from the CIO engine switch — same crash vulnerability existed there.
- The `onCaptureError` callback and `capturePhoto()` helper function are removed since the system camera handles capture errors internally.
- The share intent filter label "Scan Blood Pressure" is designed for future extensibility — a glucose scanner could add a second filter labeled "Scan Glucose" on the same activity.
- `IntentCompat.getParcelableExtra()` should be used for API 33+ compatibility when extracting the shared image URI.

---

## Iteration 1

**Implemented:** 2026-02-28
**Method:** Single-agent (user requested solo mode)

### Tasks Completed This Iteration
- Step 1: Switch Ktor engine from OkHttp to CIO (HEA-142)
- Step 2: Fix image decode fallback — fail instead of sending raw bytes (HEA-143)
- Step 3: Replace CameraCaptureScreen with system camera (HEA-143)
- Step 4: Add share receiver for image sharing into BP flow (HEA-143)
- Step 5: Add FileProvider for camera temp files (HEA-143)
- Step 6: Integrate Sentry for crash reporting (HEA-142/HEA-143)
- Step 7: Remove CameraX dependencies (HEA-143)

### Files Modified
- `gradle/libs.versions.toml` — Replaced ktor-client-okhttp with ktor-client-cio, removed CameraX entries, added Sentry SDK + plugin
- `app/build.gradle.kts` — Swapped ktor-client-cio, removed 5 CameraX deps, added Sentry deps + plugin config
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt` — HttpClient(OkHttp) → HttpClient(CIO)
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt` — Renamed resizeImageIfNeeded to prepareImageForApi, returns null on decode failure
- `app/src/test/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModelTest.kt` — Added BitmapFactory mockkStatic, new test for undecodable bytes error
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` — Full rewrite: system camera via TakePicture + share image support
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt` — Added sharedImageUri parameter, URL-encoded routing to camera-bp
- `app/src/main/kotlin/com/healthhelper/app/MainActivity.kt` — Extract shared image URI from ACTION_SEND intent
- `app/src/main/AndroidManifest.xml` — Share intent filter, FileProvider, Sentry meta-data
- `app/src/main/res/xml/file_paths.xml` — Created FileProvider config for bp_images cache dir
- `app/src/main/kotlin/com/healthhelper/app/HealthHelperApp.kt` — SentryTimberTree + environment tag
- `.mcp.json` — Added Sentry MCP server
- `.claude/settings.json` — Added Sentry MCP tool permissions

### Linear Updates
- HEA-142: Todo → In Progress → Review
- HEA-143: Todo → In Progress → Review

### Pre-commit Verification
- bug-hunter: Found 4 issues — 2 real bugs fixed (temp file leak on camera cancel, unfaithful BitmapFactory mock), 2 false positives skipped (DSN is public per Sentry docs, trace rate intentional for personal app)
- verifier: All tests pass, zero warnings

### Review Findings

Summary: 5 issue(s) found (Team: security, reliability, quality reviewers)
- FIX: 5 issue(s) — Linear issues created
- DISCARDED: 14 finding(s) — false positives / not applicable

**Issues requiring fix:**
- [MEDIUM] RESOURCE: Temp file leak — `uri?.path` on content:// URI doesn't resolve to filesystem path, temp files never deleted (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt:78`)
- [MEDIUM] SECURITY: No URI scheme validation for shared images — `file://` URIs accepted without validation (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt:86`)
- [MEDIUM] RESOURCE: IO reads on Main dispatcher — `readBytes()` on Main thread in camera callback and shared URI handler, ANR risk (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt:66,87`)
- [MEDIUM] EDGE CASE: No size limit on image `readBytes()` — unbounded stream could cause OOM (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt:66,87`)
- [LOW] EDGE CASE: Share intent re-navigation on activity recreation — `LaunchedEffect(sharedImageUri)` fires again after config change/process death (`app/src/main/kotlin/com/healthhelper/app/presentation/ui/AppNavigation.kt:18-24`)

**Discarded findings (not bugs):**
- [DISCARDED] Sentry DSN hardcoded in manifest — Intentional per plan. Sentry DSNs are public ingest endpoints per Sentry's official documentation. Not a secret; only allows event submission, not reading.
- [DISCARDED] Timber.w logs API error message — Data minimization concern only. The `result.message` field contains parse-level descriptions, not user data or tokens.
- [DISCARDED] ACTION_SEND no permission guard — Standard Android share receiver behavior. Expected design.
- [DISCARDED] SharedFlow collection pattern — Reviewer confirmed no actual bug. replay=0 prevents stale events.
- [DISCARDED] processingJob?.cancel() unreachable code — Guard on line 55 makes cancel on line 57 unreachable when processing. Harmless dead code.
- [DISCARDED] Force unwrap `uiState.error!!` — Safe in Compose snapshot system. Delegated property value stable within a composition pass. Standard Compose pattern.
- [DISCARDED] URLEncoder/URLDecoder inline style — Style only, zero correctness impact.
- [DISCARDED] Timber string interpolation format — Style preference not enforced by CLAUDE.md.
- [DISCARDED] Timber missing image context in logs — Nice-to-have diagnostic info, not a bug.
- [DISCARDED] AppModule internal overload layering — Reviewer confirmed no bug.
- [DISCARDED] No test for processingJob race — Edge case unreachable in practice due to guard + finally block ordering.
- [DISCARDED] No test for onRetake() idempotency — Null-safe `?.cancel()` on null is safe by design.
- [DISCARDED] Weak test assertion on captured bytes — Test is functional and asserts non-null. Stronger assertion is nice-to-have.
- [DISCARDED] setUp mocks unused SettingsRepository flows — Copy-paste noise from another test class. Not a bug.

### Linear Updates
- HEA-142: Review → Merge (original task completed)
- HEA-143: Review → Merge (original task completed)
- HEA-144: Created in Todo (Fix: temp file leak)
- HEA-145: Created in Todo (Fix: URI scheme validation)
- HEA-146: Created in Todo (Fix: IO on Main dispatcher)
- HEA-147: Created in Todo (Fix: image size limit)
- HEA-148: Created in Todo (Fix: share intent re-navigation)

<!-- REVIEW COMPLETE -->

### Continuation Status
All tasks completed.

---

## Fix Plan

**Source:** Review findings from Iteration 1
**Linear Issues:** [HEA-144](https://linear.app/lw-claude/issue/HEA-144), [HEA-145](https://linear.app/lw-claude/issue/HEA-145), [HEA-146](https://linear.app/lw-claude/issue/HEA-146), [HEA-147](https://linear.app/lw-claude/issue/HEA-147), [HEA-148](https://linear.app/lw-claude/issue/HEA-148)

### Fix 1: Temp file leak — store File reference for cleanup (HEA-144)
**Linear Issue:** [HEA-144](https://linear.app/lw-claude/issue/HEA-144)

1. Add `var tempFile by remember { mutableStateOf<File?>(null) }` to hold the temp file reference
2. In the "Take Photo" onClick, assign `tempFile = File.createTempFile(...)` before creating the URI
3. In `takePictureLauncher` callback `finally` block, replace `uri?.path?.let { File(it).delete() }` with `tempFile?.delete(); tempFile = null`
4. Verify existing tests pass

### Fix 2: URI scheme validation for shared images (HEA-145)
**Linear Issue:** [HEA-145](https://linear.app/lw-claude/issue/HEA-145)

1. In the `LaunchedEffect(sharedImageUri)` block, after `Uri.parse(sharedImageUri)`, add: `if (uri.scheme != "content") { viewModel.onCaptureError("Unsupported image source."); return@LaunchedEffect }`
2. Verify existing tests pass

### Fix 3: Move IO reads off Main dispatcher (HEA-146)
**Linear Issue:** [HEA-146](https://linear.app/lw-claude/issue/HEA-146)

1. In the `LaunchedEffect(sharedImageUri)` block, wrap `contentResolver.openInputStream(uri)?.use { it.readBytes() }` in `withContext(Dispatchers.IO) { ... }`
2. In the `takePictureLauncher` callback, extract the byte-reading into a `rememberCoroutineScope()` launch with `Dispatchers.IO`, then call `viewModel.onPhotoCaptured(bytes)` from within
3. Add `import kotlinx.coroutines.Dispatchers` and `import kotlinx.coroutines.withContext`
4. Verify existing tests pass

### Fix 4: Add size limit on image reads (HEA-147)
**Linear Issue:** [HEA-147](https://linear.app/lw-claude/issue/HEA-147)

1. Add a `private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024` (20MB) constant at file level
2. Create a `readBytesLimited(inputStream: InputStream, maxSize: Int): ByteArray` helper that reads in chunks and throws `IllegalArgumentException` if size exceeded
3. Replace both `readBytes()` calls with `readBytesLimited(it, MAX_IMAGE_BYTES)`
4. In catch blocks, handle the size exceeded case with user-friendly error: "Image is too large. Please choose a smaller photo."
5. Verify existing tests pass

### Fix 5: Consume share intent to prevent re-navigation (HEA-148)
**Linear Issue:** [HEA-148](https://linear.app/lw-claude/issue/HEA-148)

1. In `MainActivity.kt`, after extracting `sharedImageUri` from the intent, call `intent.removeExtra(Intent.EXTRA_STREAM)` to consume the share data
2. This prevents the same URI from being re-extracted on activity recreation (config change / process death + restore)
3. Verify existing tests pass
