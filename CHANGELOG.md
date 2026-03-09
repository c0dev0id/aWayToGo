# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- MapLibre Native Android SDK for map rendering
- `MAPTILER_KEY` injected at build time via `BuildConfig` from environment variable
- `MapScreen` composable with full lifecycle handling (`onStart`, `onResume`, `onPause`, `onStop`, `onDestroy`)
- INTERNET permission in `AndroidManifest.xml`
- `MAPTILER_KEY` env var passed to build steps in CI workflows (build and release)
- Development journal at `.github/development-journal.md`
- `androidx.lifecycle:lifecycle-runtime-compose` dependency for `LocalLifecycleOwner`

### Changed
- App `build.gradle.kts`: enabled `buildConfig`, removed `org.jetbrains.kotlin.android` plugin (not required since AGP 9.0)
- `MainActivity`: replaced placeholder `Greeting` composable with full-screen `MapScreen`
- `themes.xml`: replaced `Theme.MaterialComponents.DayNight.NoActionBar` with `android:Theme.DeviceDefault.Light.NoActionBar` (no external dependency required for a pure Compose app)
- `LocalLifecycleOwner` import updated to `androidx.lifecycle.compose` (old location deprecated)

### Removed
- `scripts/` directory (leftover from project template)
