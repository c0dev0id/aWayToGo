# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Drawer panel content: circular profile picture placeholder at the top, followed by a scrollable Navigation section (Account, My Locations, My Trips, My Recordings, POI Groups, Settings) and a Map section (Map Types, Map Styles, Overlays, Presets). Each entry has an icon and a label. No functionality is wired yet.
- Nine new vector drawables for the drawer menu icons: `ic_account`, `ic_pin`, `ic_route`, `ic_record`, `ic_star`, `ic_settings`, `ic_map_type`, `ic_palette`, `ic_tune`.

### Changed
- Hamburger and layers buttons replaced by an edge-attached drawer panel. The panel is 1/4 of the screen width; only a 52dp pad column (rounded on the right, square on the left) sticks out from the left edge when closed. Tapping a pad toggles the drawer open/closed with a 260ms animated slide. Dragging a pad progressively reveals the panel and snaps open or closed on release depending on travel distance. When the drawer opens, the MapLibre camera focal point and the explore bar both shift right so the map stays centred in the remaining 3/4 of the screen.

### Added
- Three-mode UI skeleton: Explore, Navigate, and Edit modes are now distinct UI states managed by `AppMode` and `setMode()`. Each mode shows its own chrome and hides the others. Buttons are wired for mode transitions; no business logic is attached yet.
- Explore mode: hamburger button (top-left), locate-me button (bottom-left), and a fused bottom-center action bar — Ride and Plan half-pill buttons tuck behind a larger circular Search button, creating one cohesive control. Button renamed Edit → Plan.
- Navigate mode (stub): green top banner and a STOP button at bottom-center.
- Edit mode (stub): blue top bar with DISCARD, trip title, and SAVE.
- `ic_menu` and `ic_search` vector drawables.

### Changed
- Locate-me button moved from top-left to bottom-left to make room for the hamburger.
- Crosshair visibility is now mode-aware: always visible in Edit mode, otherwise follows panning state as before.

### Added
- Version card: small overlay in the bottom-right corner of the map shows the installed build's short commit hash. Tapping checks GitHub for a newer pre-release; if one exists, it downloads the APK (with a progress overlay) and opens the system package installer.
- Drag line now appears as soon as the long-press threshold is reached (500 ms into the hold) — no longer waits for key release.

### Changed
- Drag line visual: width reduced by 25% (casing 7.5dp, fill 4.5dp) and opacity set to 60% for less visual clutter while navigating.
- Analog joystick now follows an exponential speed curve: magnitude 2 → 15 px/s, 3 → 30 px/s, 4 → 60 px/s, 5 → 120 px/s; adjacent levels ramp in 300 ms both up and down. Replaces the flat `JOY_SENSITIVITY` multiplier.

### Added
- Drag line: long-pressing CONFIRM while in panning mode draws a red line (6dp fill, 2dp darkred casing) from the current GPS position to the crosshair, with a distance label along the line.
- Analog joystick support: the remote's analog stick sends `joy` string broadcasts (e.g. `"U5L3"`); the map now pans continuously in the indicated direction with proportional speed, integrated via the existing Choreographer loop.
- `DiagnosticRemoteActivity`: intent sniffer that registers for all confirmed `com.thorkracing.wireddevices.*` actions and displays every received broadcast with its extras on screen. Launched via `make diag-remote`.
- `RemoteEvent.JoyInput`: new sealed-class event carrying normalised `(dx, dy)` in [−1, 1].
- `make log` now writes a `log/remote.log` filtered to `RemoteControl` tag entries.
- `make diag-remote` target to launch `DiagnosticRemoteActivity` from the host machine.

### Changed
- `RemoteControlManager`: logs every broadcast received (extras list, joy value, key codes with resolved `RemoteKey`), and logs on register/unregister. Prefers `joy` extra over `key_press` when both appear in the same broadcast.
- `RemoteControlManager.unregister()`: narrowed catch from `Exception` to `IllegalArgumentException` (the only exception the Android API can throw here).
- Web Mercator magic numbers extracted to named top-level constants (`MERCATOR_CIRCUMFERENCE`, `METERS_PER_DEGREE_LAT`).

### Fixed
- Analog joystick was silently ignored because the Choreographer gate (`panStartNs.isNotEmpty()`) excluded the `joyDx/joyDy` path. Condition now also passes when `joyDx != 0f || joyDy != 0f`.
- Build: `com.mapbox.geojson` does not exist in MapLibre 11; corrected to `org.maplibre.geojson` and added an explicit `android-sdk-geojson:6.0.0` compile dependency (MapLibre declares it as `implementation`, so it is not transitively available at compile time).
- Build: Kotlin supports nested block comments; a `/*` sequence inside the KDoc comment of `DiagnosticRemoteActivity` caused the outer comment to be unclosed, swallowing the rest of the file. Rewrote the affected line.

### Changed
- MapActivity: set `pixelRatio=3.0`, `maxFps=60`, `prefetchDelta=2` based on benchmark results. These settings yielded the best gl_fps on the target device (avg=28, min=18 at zoom 14) with no visible stutter during pan.

### Added
- Panning mode: entering panning with the D-pad or a touch gesture shows a crosshair at the screen centre and suspends GPS tracking. BACK or CONFIRM (remote) or the my-location button (touch) exits panning mode, re-enables tracking, and flies back to the current GPS position.
- Tile gate: network tile fetches are suspended during any camera movement and released when the camera becomes idle, so the GL thread is not interrupted by upload work during animation. A 50 MB OkHttp disk cache is also configured so previously-fetched tiles are served from disk (bypassing the gate entirely) on subsequent visits.

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
