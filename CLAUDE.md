# HealthHelper

Android app that interfaces with Health Connect to push and read health data.

## Build & Test

```bash
./gradlew test              # Run unit tests (JUnit 5 + MockK)
./gradlew assembleDebug     # Build debug APK → app/build/outputs/apk/debug/
./gradlew installDebug      # Build + install on connected device
```

Environment variables required (add to ~/.bashrc):
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

## Architecture

MVVM + Clean Architecture with three layers:

```
app/src/main/kotlin/com/healthhelper/app/
├── di/             # Hilt dependency injection modules
├── domain/         # Pure Kotlin — no Android deps, easily testable
│   ├── model/      # Data classes (HealthRecord)
│   └── usecase/    # Business logic (validation + orchestration)
├── data/           # Android/SDK deps — repository implementations
│   └── repository/ # Interface + Health Connect implementation
└── presentation/   # Compose UI + ViewModels
    ├── viewmodel/  # StateFlow-based MVVM ViewModels
    └── ui/         # Composable screens and theme
```

## Tech Stack

| Component | Version | Notes |
|---|---|---|
| AGP | 9.0.1 | Built-in Kotlin 2.2.10 — do NOT add `kotlin-android` plugin |
| Gradle | 9.1.0 | Via wrapper |
| Compose | BOM 2025.12.00 | Material 3, dynamic colors |
| Health Connect | 1.1.0 | `Metadata.manualEntry()` factory — constructor is internal |
| Hilt | 2.59.2 | Must be 2.59+ for AGP 9 compat (BaseExtension removed) |
| KSP | 2.2.10-2.0.2 | Matching AGP's bundled Kotlin version |
| JUnit 5 | 5.11.4 | Needs `junit-platform-launcher` runtime dep for Gradle 9 |
| MockK | 1.14.2 | Kotlin-native mocking |

All versions managed in `gradle/libs.versions.toml`.

## Testing Approach

TDD with domain-first testing:

- **Domain layer** (use cases, models): Pure JUnit 5 + MockK on JVM — fast, no Android deps
- **ViewModels**: JUnit 5 + Turbine for Flow testing + MockK
- **Repository/Health Connect**: Robolectric if needed
- **UI**: Compose testing rules on device/emulator

Tests live under `app/src/test/` (unit) and `app/src/androidTest/` (instrumented).

Run tests before every commit: `./gradlew test`

## Conventions

- **Kotlin style**: Official (`kotlin.code.style=official` in gradle.properties)
- **Trailing commas**: Used on all multi-line parameter lists
- **Naming**: UseCase suffix, Repository suffix, Screen suffix, ViewModel suffix
- **Error handling**: Try-catch at data layer boundaries; domain layer returns Boolean/Result
- **DI**: Constructor injection via `@Inject`; Hilt modules in `di/` package
- **State**: `StateFlow` in ViewModels, collected in Compose via `collectAsState()`
- **Compose**: `hiltViewModel()` for ViewModel injection in screens

## Health Connect Notes

- Permissions declared in `AndroidManifest.xml` (READ/WRITE for Steps and HeartRate)
- `ViewPermissionUsageActivity` alias required for privacy policy display
- `Metadata.manualEntry()` for records entered by the user
- `Metadata.autoRecorded()` for sensor-recorded data
- Respect rate limits: use changelog sync, don't delete-and-rewrite
- Min SDK 28 required for Health Connect app compatibility

## Key Files

| File | Purpose |
|---|---|
| `gradle/libs.versions.toml` | Single source of truth for all dependency versions |
| `app/build.gradle.kts` | App module plugins, SDK config, dependencies |
| `gradle.properties` | `android.disallowKotlinSourceSets=false` needed for KSP + AGP 9 |
| `local.properties` | `sdk.dir` pointing to Android SDK (not committed to VCS) |
