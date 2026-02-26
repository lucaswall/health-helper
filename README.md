# HealthHelper

An Android app that interfaces with [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) to read and write health data such as step counts and heart rate.

## Features

- Read step count data from Health Connect
- Manual health data entry
- Material 3 UI with dynamic colors
- Clean Architecture with full test coverage

## Tech Stack

- **Language**: Kotlin 2.2 (bundled with AGP 9.0)
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM + Clean Architecture
- **DI**: Hilt
- **Health Data**: Health Connect SDK 1.1.0
- **Testing**: JUnit 5 + MockK

## Architecture

```
app/src/main/kotlin/com/healthhelper/app/
├── di/             # Hilt dependency injection modules
├── domain/         # Pure Kotlin business logic
│   ├── model/      # Data classes
│   └── usecase/    # Business logic (validation + orchestration)
├── data/           # Repository implementations
│   └── repository/ # Health Connect integration
└── presentation/   # Compose UI + ViewModels
    ├── viewmodel/  # StateFlow-based ViewModels
    └── ui/         # Composable screens and theme
```

## Prerequisites

- JDK 17
- Android SDK (API 36)
- A device or emulator running Android 9+ (API 28) with [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) installed

## Build & Run

```bash
# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug
```

Set these environment variables (e.g. in `~/.bashrc`):

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

## License

MIT
