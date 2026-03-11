# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `MapViewModel` and `MapUiState`: mode, panning, and menu state extracted from `MapActivity` into a `ViewModel` backed by a `StateFlow`. `MapActivity` now observes the flow and delegates all view rendering to `renderUiState(new, old)`, which diffs the two states to minimise work and drive the correct animations.
- View builders extracted to `map/ui/` package: `buildExploreBottomBar`, `buildNavigateOverlay`, `buildEditTopBar`, `buildMenuPanel`, `makeCircleButton`, and `makePillButton` are now package-level functions. They receive a `Context` and lambda callbacks; no Activity reference is needed. `MapActivity` lost ~400 lines.
- `RoutingDomain` skeleton: `RoutingRepository` interface, `Route`, `RoutePoint`, `RoutingProfile`, `RoutingResult` domain types, and a `BRouterEngine` stub that wires to the interface. No BRouter library yet — the stub returns an error; this establishes the correct layer boundary before integration.

### Changed
- Self-update logic extracted from `MapActivity` into `update/AppUpdater`. `MapActivity` now only manages the download progress overlay UI; all network, file I/O, and package installer concerns live in `AppUpdater`.
- Profile avatar placeholder removed from the hamburger menu.

### Fixed
- Screen rotation visual disruption eliminated. Diagnostic confirmed that `configChanges` was already preventing Activity recreation (same window token across all rotations, no `DESTROY`/`RECREATE` events). The visible "redraw" was the OS freeze-and-rotate animation (`ROTATION_ANIMATION_ROTATE`), which captures a screenshot and cross-fades to the new layout regardless of `configChanges`. Fixed by setting `rotationAnimation = ROTATION_ANIMATION_SEAMLESS` on the window in `onCreate`, which instructs the compositor to skip the animation and resize the window in place.

### Changed
- Menu header bar removed. The hamburger button and profile avatar are now independent overlays inside the panel's `FrameLayout`, so the panel has a single uniform dark surface with no contrasting header band. The hamburger button has no fill background (the panel's dark surface shows through uniformly) and uses a circular ripple mask. The profile avatar is pinned to the top-right of the header area and slides in naturally as the panel expands to full width, since `clipToOutline` hides it until the panel is wide enough.
- Navigate mode camera: tilt increased to 45° and the GPS focal point now sits 40 % below screen centre (90 % from top), giving a deeper perspective view with more of the road ahead visible.
- Tapping Ride or Plan now first flies the camera to the current GPS position (at the current zoom level) and only triggers the mode transition once that animation completes. This ensures the incoming mode's tilt/offset animation always starts from a known, centred position.
- Locate-me (the my-location button and remote CONFIRM) now preserves the current zoom level when flying to the GPS position, instead of always snapping to zoom 14.
- The hamburger button and menu panel are now a single unified view. The panel starts at 64×64dp (identical appearance to the previous circular button) and expands to full size when opened, using a `ValueAnimator` on the layout params width and height. This eliminates two visual artifacts from the old scale-based approach: the top-left corner radius was visually wrong at small scales, and the separate button showed through the semi-transparent panel.
- The hamburger icon rotates 90° as the menu opens and reverses on close, driven by the same animator as the size expansion.
- Profile image placeholder moved to the right side of the profile row; the "Profile" label is removed.
- Menu now closes instantly (without its own animation) when a mode switch is triggered, to avoid competing with the mode transition animation.

### Added
- Animated mode transitions: switching between Explore, Navigate (Ride), and Edit (Plan) now plays a two-phase slide animation. Outgoing Explore elements (hamburger button, locate-me button) slide off to the left, and the Ride/Search/Plan bar slides off to the bottom (200ms, accelerate). Incoming elements slide in from their respective edge (250ms, decelerate). Navigate banner arrives from the top and STOP from the bottom; Edit top bar arrives from the top.
- Hamburger button opens a popup menu that folds out to the right and downward from the button's top-left corner, using a 220ms decelerate scale animation originating from `pivotX=0, pivotY=0`. Tapping anywhere outside the menu collapses it with a 180ms accelerate animation. The menu closes automatically when switching to Navigate or Edit mode.
- Menu panel: circular profile image placeholder at the top, followed by six entries (My Locations, My Trips, My Recordings, My POI Groups, Offline Maps, Settings), each with a dedicated icon and 20sp label. Panel background uses 32dp corner radius on all corners, matching the circular hamburger button exactly.
- Six new vector drawables: `ic_menu_locations`, `ic_menu_trips`, `ic_menu_recordings`, `ic_menu_poi_groups`, `ic_menu_offline_maps`, `ic_menu_settings`.

### Three-mode UI skeleton: Explore, Navigate, and Edit modes are now distinct UI states managed by `AppMode` and `setMode()`. Each mode shows its own chrome and hides the others. Buttons are wired for mode transitions; no business logic is attached yet.
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
