# Development Journal — aWayToGo

## Software Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Architecture | MVI |
| Map Rendering | MapLibre Native (Android SDK) |
| Offline Map Storage | MBTiles (SQLite) |
| Tile Source | Maptiler (cloud now, self-hostable via tileserver-gl later) |
| Routing Engine | BRouter |
| Local Database | Room |
| Preferences | DataStore |
| Background Work | WorkManager |

## Key Decisions

### Single-screen / map-centric UI
The map is always present at the root of the Compose hierarchy. Panels, bottom sheets, and overlays compose on top of it. The map is never destroyed during normal navigation. If full-screen flows are needed in future (e.g. GPX file manager), they use separate Activities, not Compose destinations, to avoid MapLibre reinitialisation cost.

### BRouter over Valhalla
Valhalla was evaluated but requires NDK/JNI cross-compilation with no official Android library. BRouter is Java-native, proven in production by OsmAnd and Locus Map, and integrates directly without NDK. This is the correct trade-off for a solo project.

### Slider-based routing preferences deferred
Valhalla's runtime costing JSON was the ideal fit for user-facing routing sliders. BRouter uses profile scripts instead. The slider UI concept is deferred — BRouter profile parameters can be exposed later if needed.

### XYZ tiles + MBTiles over PMTiles
PMTiles is optimised for static hosting via HTTP range requests. The app requires selective tile download (GPX-based bounding box, user-defined tile picker). XYZ + local MBTiles SQLite cache is a more natural fit for this use case. MapLibre supports MBTiles offline natively on Android.

### Maptiler as tile provider
Chosen for its cloud/self-hosting compatibility: start with Maptiler Cloud, migrate to self-hosted tileserver-gl later with no app code changes. API surface is identical in both cases.

### MAPTILER_KEY via BuildConfig
The Maptiler API key is injected at build time from the `MAPTILER_KEY` environment variable (GitHub secret). It is available in code as `BuildConfig.MAPTILER_KEY`. Never hardcoded or committed.

### MVI architecture
Navigation apps have complex, overlapping state (routing, map camera, download progress, GPS, active navigation). MVI provides unidirectional data flow which handles this better than MVVM's two-way binding. Each domain panel (navigation bar, routing panel, GPX panel) has its own ViewModel with its own MVI state — no single monolithic ViewModel.

## Core Features (Planned)

- Offline map display with MBTiles tile cache
- GPX track import, display, and export
- Offline tile download — GPX bounding box mode and manual tile picker mode
- Offline routing via BRouter with configurable profiles
- Turn-by-turn navigation with TTS via foreground service
- Off-route detection and automatic re-routing
- Background navigation (continues when app is in background)
