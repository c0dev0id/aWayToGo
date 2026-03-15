# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

`aWayToGo` is an Android motorcycle navigation app designed to run as the device launcher. It uses MapLibre for map rendering, Nominatim for geocoding, and BRouter for offline routing. The UI is entirely programmatic (no XML layouts, no Jetpack Compose).

## Build

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires MAPTILER_KEY env var)
MAPTILER_KEY=your_key ./gradlew assembleRelease
```

`BuildConfig.MAPTILER_KEY`, `BuildConfig.GIT_COMMIT`, and `BuildConfig.BUILD_TIME` are injected at build time from `app/build.gradle.kts`.

There are no automated tests in this project.

## Device workflow (requires ADB)

```bash
make install        # download latest CI APK from GitHub and install via adb
make log-clear      # clear logcat buffer before reproducing a bug
make log            # dump logcat into log/ (full.log, app.log, crash.log, bench.log, remote.log)
make diag           # launch FPS benchmark activity
make diag-remote    # launch DMD remote broadcast sniffer
make diag-rotate    # launch rotation lifecycle diagnostic
make launcher       # set app as the Android launcher
make restore        # restore the default launcher
```

## Architecture

### Single-activity, programmatic views

There is exactly one real Activity: `MapActivity`. All UI — buttons, overlays, panels — is built in Kotlin code using `addView`. UI panels live in `map/ui/` as top-level builder functions (e.g. `buildSearchOverlay(...)`) that return a handle object holding the root view and state-mutation callbacks. `MapActivity` holds these handles and calls their methods from `renderUiState()`.

`MapActivity` handles `configChanges` (orientation, screenSize, etc.) itself — the Activity is **never recreated** on rotation. `onConfigurationChanged` is the hook for layout adjustments.

### State management (MVVM with StateFlow)

`MapViewModel` owns an immutable `MapUiState` data class exposed as a `StateFlow`. All state mutations go through `MapViewModel` methods (`setMode`, `enterPanningMode`, `openSearch`, `toggleSatellite`, …). `MapActivity.renderUiState()` is the single place that translates `MapUiState` into view visibility, alpha, and camera behavior. `MutableStateFlow` only emits on structural change, so all mutators are idempotent.

### App modes

`AppMode` (EXPLORE / NAVIGATE / EDIT) is the top-level mode. `setMode()` atomically updates the mode, closes the menu, closes search (unless staying in EXPLORE), and clears panning mode when entering NAVIGATE or EDIT.

### Remote control

`RemoteControlManager` registers a `BroadcastReceiver` for `com.thorkracing.wireddevices.keypress` (DMD device broadcasts). It decodes `key_press`/`key_release`/`joy` extras and emits typed `RemoteEvent` values (`KeyDown`, `KeyUp`, `ShortPress`, `LongPress`, `JoyInput`) on a `SharedFlow`. Long-press detection for CONFIRM/BACK is done by timing the gap between press and release. `MapActivity` collects the flow and dispatches to `PanController` or handles mode transitions directly.

`RemoteControlManager.register()` / `unregister()` belong in `onResume` / `onPause`.

### Camera panning (PanController)

`PanController` owns D-pad and joystick state and drives per-frame camera updates via Android's `Choreographer`. Each vsync frame it computes a bearing-aware pan delta (so UP always moves map content down regardless of map rotation), then calls `map.animateCamera(…, PAN_LOOK_AHEAD_MS)` with a 32 ms look-ahead so the GL thread interpolates smoothly. The joystick ramps speed over ~300 ms per magnitude level to avoid jarring acceleration. `PanController` never touches `MapViewModel` directly — it calls the `onEnterPanningMode` callback injected at construction.

### Tile performance (TileCache + TileGateInterceptor)

`TileCache` (singleton object) wraps MapLibre's OkHttp client with a 50 MB disk cache and a `TileGateInterceptor`. The gate pauses **network** tile fetches while the camera is moving (disk-cached tiles still serve immediately). When the camera stops, the gate opens and MapLibre fills in missing tiles while the map is static, eliminating GL-thread upload frame drops during motion. Initialize with `TileCache.init(context)` after `MapLibre.getInstance()` but before any `MapView` is created.

### Search

`GeocodingRepository` queries Nominatim (`/search?format=json`). `RecentSearches` persists recently searched terms and visited locations. The search overlay (`map/ui/SearchOverlay.kt`) is a bottom-anchored panel built by `buildSearchOverlay(…)` that returns a `SearchOverlayResult` handle for pushing state (results, loading, error, clear).

### Routing

`RoutingRepository` is the domain interface. `BRouterEngine` is the sole implementation, wrapping the BRouter library. No other class imports BRouter directly.

### Self-update

`AppUpdater` checks the GitHub Releases API for the newest pre-release APK. Update detection compares the APK filename (which the CI pipeline embeds the short git hash into, e.g. `aWayToGo-abc1234.apk`) against `BuildConfig.GIT_COMMIT`. If they match, the build is current. Downloads go to `externalCacheDir/update.apk` and are handed to the system package installer via `FileProvider`.

## Key identifiers

- Package: `de.codevoid.aWayToGo`
- minSdk: 34 / targetSdk: 36 / ABI filters: arm64-v8a, armeabi-v7a
- Remote broadcast action: `com.thorkracing.wireddevices.keypress`
- GitHub repo: `c0dev0id/aWayToGo`
