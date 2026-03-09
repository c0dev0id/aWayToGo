# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Panning mode: entering panning with the D-pad or a touch gesture shows a crosshair at the screen centre and suspends GPS tracking. BACK or CONFIRM (remote) or the my-location button (touch) exits panning mode, re-enables tracking, and flies back to the current GPS position.

### Changed
- Map screen rebuilt on raw Android Views + single `Choreographer.FrameCallback` — Jetpack Compose removed from the main screen. Measured on target device: 14 fps with Compose → 59 fps without Compose. `MapActivity` replaces `MainActivity` as the launcher activity.
- `app/build.gradle.kts`: removed Compose BOM, Compose plugin, and all Compose dependencies; replaced `lifecycle-runtime-compose` with `lifecycle-runtime-ktx`. Added explicit `core-ktx` and `activity-ktx` dependencies.

### Removed
- `MainActivity.kt` and all Jetpack Compose UI code from the main screen. All features (location, remote control, pan ramp-up, tilt toggle, tracking toggle) are preserved in `MapActivity`.

### Added
- DMD remote control support via `com.thorkracing.wireddevices.keypress` broadcast
- Long-press detection for CONFIRM and BACK buttons (500 ms threshold)
- Directional keys pan the map continuously while held; panning stops on key release
- ZOOM_IN / ZOOM_OUT keys zoom the map
- CONFIRM short-press re-centres on the user and resumes tracking; long-press toggles tracking on/off
- BACK short-press resets bearing to north; long-press toggles 3D tilt (0° ↔ 60°)
- Rotate, tilt, and compass gestures enabled on the map
- User location displayed and map camera tracks it on start (requires location permission)
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

### Changed (build pipeline)
- Build workflow now produces a release APK (`assembleRelease`) instead of a debug APK
- Release build has minification (`isMinifyEnabled = true`) and resource shrinking enabled
- ABI filters restrict native libs to `arm64-v8a` and `armeabi-v7a` — drops emulator ABIs (~30–40 MB)
- Added `proguard-rules.pro` with MapLibre keep rules

### Removed
- `scripts/` directory (leftover from project template)
