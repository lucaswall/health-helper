# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.8.6] - 2026-04-23

### Fixed

- Hydration now syncs in the background on its own schedule instead of only when you tap "Sync Now". The app will prompt for a new Health Connect "read data in background" permission on launch — grant it to enable automatic hydration sync
- Tapping "Sync Now" no longer resets the 15-minute background sync timer, which previously starved the periodic sync for users who synced manually often

## [1.8.5] - 2026-04-17

### Fixed

- Hydration readings pushed from Health Connect now preserve the original record's timezone offset instead of substituting the current device offset
- Hydration sync no longer marks history as caught up when a first-page read times out before any records are returned
- Hydration card now shows a permission prompt when Health Connect read access is denied instead of displaying "No readings today"
- Today's hydration total no longer races itself on the 30-second refresh loop, preventing stale values from overwriting fresh reads
- Hydration card "last run" timestamp now refreshes once caught up, so the relative time stops freezing
- Background-sync rescheduling failures and push-retry attempts are now logged for production debugging

### Changed

- "No readings today" wording on the hydration card clarified to distinguish between an empty day and a fresh install

## [1.8.4] - 2026-04-12

### Fixed

- Sync no longer posts a false "permission missing" notification when Health Connect's internal permission ledger disagrees with the OS-level grant; missing permissions are now detected only from actual read failures

## [1.8.3] - 2026-04-12

### Fixed

- Missing Health Connect read grants (e.g. hydration granted at the Android level but not confirmed inside Health Connect) are now detected during sync and surfaced in the app instead of silently skipping data

## [1.8.2] - 2026-04-12

### Fixed

- Missing Health Connect permissions are now surfaced in the app instead of silently marking sync as up to date
- Background sync now posts a notification when it can't proceed due to missing permissions
- Permission status re-checks every time the app is opened, with an "Open Health Connect settings" fallback when grants are locked out

## [1.8.1] - 2026-04-10

### Fixed

- Fixed sync incorrectly marking history as caught up when Health Connect returned truncated results
- Fixed hydration watermark not resetting properly for full history re-sync

## [1.8.0] - 2026-04-10

### Added

- Hydration card on home screen showing daily total, sync status, and history backfill progress

### Fixed

- Fixed settings fields losing pasted text due to repository flow overwriting unsaved input

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

[Unreleased]: https://github.com/lucaswall/health-helper/compare/v1.8.6...HEAD
[1.8.6]: https://github.com/lucaswall/health-helper/compare/v1.8.5...v1.8.6
[1.8.5]: https://github.com/lucaswall/health-helper/compare/v1.8.4...v1.8.5
[1.8.4]: https://github.com/lucaswall/health-helper/compare/v1.8.3...v1.8.4
[1.8.3]: https://github.com/lucaswall/health-helper/compare/v1.8.2...v1.8.3
[1.8.2]: https://github.com/lucaswall/health-helper/compare/v1.8.1...v1.8.2
[1.8.1]: https://github.com/lucaswall/health-helper/compare/v1.8.0...v1.8.1
[1.8.0]: https://github.com/lucaswall/health-helper/compare/v1.7.0...v1.8.0
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
