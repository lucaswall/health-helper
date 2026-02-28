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

#### Related Code
- `app/src/main/kotlin/com/healthhelper/app/di/AppModule.kt:86` — `HttpClient(OkHttp)` engine configuration
- `gradle/libs.versions.toml:18,52` — `ktor = "3.1.1"`, `ktor-client-okhttp` dependency
- `gradle/libs.versions.toml:25,63-67` — `camerax = "1.5.1"`, all 5 camera library entries
- `app/build.gradle.kts:85-90` — 5 CameraX implementation dependencies
- `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` — full 200-line CameraX implementation
- `app/src/main/kotlin/com/healthhelper/app/presentation/viewmodel/CameraCaptureViewModel.kt:54-96` — photo capture handling
- `app/src/main/kotlin/com/healthhelper/app/data/api/AnthropicApiClient.kt:83-88` — Ktor HTTP POST via OkHttp engine

### Impact
- App crashes when network is unavailable/transitioning during any HTTP call (BP scan or food sync)
- Same OkHttp vulnerability affects `FoodScannerApiClient`
- Poor camera UX: no preview, no retake, no gallery option
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

### Step 2: Replace CameraCaptureScreen with system camera + gallery picker
**File:** `app/src/main/kotlin/com/healthhelper/app/presentation/ui/CameraCaptureScreen.kt` (rewrite)
**Pattern:** Follow `SyncScreen.kt:65-72` for `rememberLauncherForActivityResult` usage

**Behavior:**

Rewrite the screen to use system intents instead of CameraX viewfinder:

- **Initial state:** Screen with title "Scan Blood Pressure", a "Take Photo" button, and a "Choose from Gallery" button. No camera viewfinder.
- **"Take Photo"** uses `ActivityResultContracts.TakePicture()` — launches the system camera app with a `FileProvider` temp URI. The system camera handles preview, retake, and confirm natively. On success, reads image bytes from the URI via `contentResolver.openInputStream()` and calls `viewModel.onPhotoCaptured(bytes)`.
- **"Choose from Gallery"** uses `ActivityResultContracts.PickVisualMedia(ImageOnly)` — launches the system photo picker. On image selected, reads bytes from the content URI and calls `viewModel.onPhotoCaptured(bytes)`.
- **Processing state:** Shows `CircularProgressIndicator` and "Analyzing..." text while `isProcessing == true`.
- **Error state:** Shows error message and "Try Again" button (same behavior as current).
- **Navigation:** On success, navigates to `BpConfirmationScreen` via existing `navigateToConfirmation` SharedFlow.
- **Uri-to-bytes conversion** happens in the Composable layer (using `LocalContext.current`), keeping the ViewModel clean with no `Context` dependency. The existing `onPhotoCaptured(imageBytes: ByteArray)` signature is unchanged.

**Tests:**
1. All existing `CameraCaptureViewModelTest` tests pass unchanged (they call `onPhotoCaptured(bytes)` directly)

### Step 3: Add FileProvider for camera temp files
**File:** `app/src/main/AndroidManifest.xml` (modify)
**File:** `app/src/main/res/xml/file_paths.xml` (create)

**Behavior:**
- `TakePicture()` requires a `Uri` for the system camera to write the photo to
- Register a `FileProvider` in the manifest with authority `${applicationId}.fileprovider`
- Create `file_paths.xml` with a `<cache-path name="bp_images" path="bp_images/" />` entry
- The screen creates a temp file in `cacheDir/bp_images/`, gets a FileProvider URI, passes it to `TakePicture()`

**Tests:**
1. No unit tests needed — manifest/XML configuration verified by build + manual test

### Step 4: Remove CameraX dependencies
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

### Step 5: Verify
- [ ] All existing tests pass (`./gradlew test`)
- [ ] Kotlin compiles without errors (`./gradlew assembleDebug`)
- [ ] Lint passes
- [ ] Build succeeds
- [ ] Manual test: Take photo via system camera → processes → shows confirmation
- [ ] Manual test: Pick from gallery → processes → shows confirmation
- [ ] Manual test: Airplane mode → take photo → shows error message (NOT crash)

## Notes
- The CIO engine uses Java's SSLEngine instead of OkHttp's TLS stack. For standard HTTPS to `api.anthropic.com`, this is transparent. `network_security_config.xml` already has `cleartextTrafficPermitted="false"`.
- The CAMERA permission remains needed — system camera apps on some devices check caller permissions.
- `PickVisualMedia` is available from `activity-compose` (already a transitive dependency via Compose).
- Existing `FoodScannerApiClient` also benefits from the CIO engine switch — same crash vulnerability existed there.
- The `onCaptureError` callback and `capturePhoto()` helper function are removed since the system camera handles capture errors internally.
