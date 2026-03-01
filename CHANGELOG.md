# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/lucaswall/health-helper/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/lucaswall/health-helper/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lucaswall/health-helper/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lucaswall/health-helper/releases/tag/v1.0.0
