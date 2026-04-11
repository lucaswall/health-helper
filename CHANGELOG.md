# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.7.0] - 2026-04-10

### Added

- Hydration readings from Health Connect now sync to Food Scanner alongside glucose and blood pressure

### Fixed

- Fixed fractional hydration volumes being silently dropped instead of rounded

## [1.6.0] - 2026-03-30

### Added

- Cancel button to stop a health readings sync in progress

### Changed

- Sync status now shows last run time and number of readings pushed per batch
- Actual error messages shown on sync failure instead of generic text

### Fixed

- Fixed duplicate health readings being re-pushed on every sync
- Manual and background sync no longer run at the same time, preventing conflicts

## [1.5.2] - 2026-03-30

### Fixed

- Fixed health readings backfill failing to read historical records from Health Connect

## [1.5.1] - 2026-03-30

### Fixed

- Sync Now button now also syncs health readings to Food Scanner, not just nutrition data

## [1.5.0] - 2026-03-30

### Added

- Smart health readings backfill syncs your full glucose and blood pressure history to Food Scanner incrementally, resuming where it left off
- Sync status on home screen shows pushed count, caught-up state, and last sync time for each reading type
- Food log entries now use the correct timezone from when you logged the meal

### Changed

- Health sync tracks glucose and blood pressure progress independently so one type doesn't block the other
- Sync automatically retries on temporary server errors with exponential backoff

### Fixed

- Fixed glucose display showing mg/dL as primary unit on home screen

## [1.4.0] - 2026-03-29

### Added

- Glucose and blood pressure readings now sync to the Food Scanner API alongside Health Connect
- Glucose values displayed in mg/dL as the primary unit, with mmol/L shown as secondary

### Changed

- Blood pressure confirmation now defaults to sitting position and left upper arm

### Fixed

- Fixed crash when reading out-of-range glucose values from Health Connect
- Fixed coroutine cancellation handling during permission checks and blood pressure loading

## [1.3.1] - 2026-03-10

### Fixed

- Reduced unnecessary error alerts from temporary network interruptions

## [1.3.0] - 2026-03-01

### Added

- Glucose confirmation screen now pre-fills meal context based on your last synced meal — after meal with meal type if you ate recently, fasting if it's been 8+ hours

## [1.2.1] - 2026-03-01

### Fixed

- Nutrition sync now stops retrying when the server is temporarily unavailable instead of repeating failed requests

## [1.2.0] - 2026-03-01

### Added

- Blood glucose scanner — photograph your glucometer and AI extracts the reading with dual-unit display (mmol/L and mg/dL)
- Glucose confirmation screen with meal context, specimen source, and editable value
- Last glucose reading displayed on home screen
- Share glucose meter photos from your gallery to scan readings

### Fixed

- Fixed crash when sharing photos to HealthHelper from other apps

## [1.1.0] - 2026-02-28

### Added

- Share photos from your gallery to scan blood pressure readings

### Changed

- Blood pressure camera now uses the system camera app for a familiar photo experience with preview and confirmation

### Fixed

- Fixed crash when scanning blood pressure without a network connection
- Improved error messages when blood pressure scanning fails

## [1.0.0] - 2026-02-28

### Added

- Nutrition sync from Food Scanner API to Health Connect with full macro tracking (calories, protein, carbs, fat, fiber, sodium, and more)
- Automatic background sync on a configurable interval (15–120 minutes)
- Smart sync with ETag caching to skip Health Connect writes when food data hasn't changed
- Blood pressure logging via camera — photograph your monitor and AI extracts the reading
- Editable blood pressure confirmation screen with body position and measurement location
- Blood pressure readings saved to Health Connect
- Home screen showing last 3 synced meals, last blood pressure reading, and next sync time
- Real-time sync progress with day counter and records synced
- Settings screen for API configuration and sync interval
- Adaptive launcher icon with health cross design

[Unreleased]: https://github.com/lucaswall/health-helper/compare/v1.7.0...HEAD
[1.7.0]: https://github.com/lucaswall/health-helper/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/lucaswall/health-helper/compare/v1.5.2...v1.6.0
[1.5.2]: https://github.com/lucaswall/health-helper/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/lucaswall/health-helper/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/lucaswall/health-helper/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/lucaswall/health-helper/compare/v1.3.1...v1.4.0
[1.3.1]: https://github.com/lucaswall/health-helper/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/lucaswall/health-helper/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/lucaswall/health-helper/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/lucaswall/health-helper/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lucaswall/health-helper/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lucaswall/health-helper/releases/tag/v1.0.0
