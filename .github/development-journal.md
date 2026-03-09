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

### Remote control as a first-class input method
The app must be 100% operable via a DMD wired remote controller (Bluetooth/wired, sends Android broadcasts). The remote has 8 keys: directional pad (4 keys), confirm, back, zoom in, zoom out. This is the primary input method — touch is secondary.

The remote broadcasts `com.thorkracing.wireddevices.keypress` intents with `key_press` and `key_release` extras, giving full press/release cycle visibility. This enables long press detection without polling: record the `key_press` timestamp, compare to `key_release`, emit `ShortPress` or `LongPress` accordingly.

`RemoteControlManager` owns the `BroadcastReceiver` and is the only class aware of the broadcast protocol. It exposes a `SharedFlow<RemoteEvent>` — the rest of the app reacts to typed events with no knowledge of intents. Long press is detected for `CONFIRM` (66) and `BACK` (111) only, with a 500ms threshold. All other keys always emit `ShortPress`.

Key mapping at the map screen level:
- Directional pad → pan map
- Zoom in/out (136/137) → zoom one level
- CONFIRM short → re-centre on user and resume tracking
- CONFIRM long → toggle tracking on/off
- BACK short → reset bearing to north
- BACK long → toggle 3D tilt (0° ↔ 60°)

As UI panels are added, each ViewModel will consume `remoteEvents` and handle directional navigation, confirm, and back in its own context. The key mapping is context-dependent — the same key does different things depending on which panel has focus.

## Architecture

### Layer Structure

```
┌─────────────────────────────────────────┐
│              UI Layer                    │
│  Compose + MVI ViewModels               │
│  Map, Overlays, Panels, Bottom Sheets   │
├─────────────────────────────────────────┤
│            Domain Layer                  │
│  UseCases — orchestrate business logic  │
├─────────────────────────────────────────┤
│           Data Layer                     │
│  Repositories — single source of truth  │
├─────────────────────────────────────────┤
│         Infrastructure Layer             │
│  BRouter │ MapLibre │ MBTiles │ Room    │
│  Maptiler │ GPS │ TTS │ WorkManager     │
└─────────────────────────────────────────┘
```

### Domain Modules

Each domain has its own Repository and UseCases. The rest of the app never talks to infrastructure directly — always through the Repository.

**MapDomain** — tile source, MBTiles management, camera state, offline area tracking.

**RoutingDomain** — BRouter integration and profile management. BRouter runs as a bounded service inside the app process; a Kotlin wrapper isolates it so no other module touches BRouter directly.

**NavigationDomain** — active navigation session, GPS tracking, off-route detection, TTS instructions. Runs as a Foreground Service to continue in the background. Publishes state as a `StateFlow` that the UI layer observes.

**GpxDomain** — GPX import, export, storage, and display. Triggers tile download suggestions via bounding box computation.

**SettingsDomain** — user preferences, backed by DataStore.

### Cross-Domain Workflows

Domains do not depend on each other directly. Cross-domain workflows are orchestrated by UseCases that sit above the repositories:

- **StartNavigationUseCase** — takes a `Route` from RoutingDomain, hands it to NavigationDomain, starts the foreground service.
- **ImportGpxUseCase** — parses GPX via GpxDomain, computes bounding box, triggers MapDomain tile download suggestion.
- **RerouteUseCase** — triggered by NavigationDomain off-route event, calls RoutingDomain with current GPS position, updates NavigationDomain with new route.

### UI State Management

Each domain panel (routing panel, navigation bar, GPX panel, download panel) has its own ViewModel with its own MVI state. There is no single monolithic ViewModel for the whole screen. This keeps recomposition scope tight — a download progress update does not trigger recomposition of the navigation bar.

GPS is a single source of truth in NavigationRepository. Both NavigationDomain and MapDomain observe it; neither owns it independently.

### Background & Threading

- BRouter route calculation is CPU-heavy and runs on `Dispatchers.Default`. Never awaited on the main thread. Progress is exposed as `StateFlow`.
- Tile downloads are owned by WorkManager — runs off main thread, survives app death, reports progress via observable work state.
- All Room queries use suspend functions on `Dispatchers.IO`.
- Navigation (GPS, TTS, off-route detection) runs in a Foreground Service.
- MapLibre renders on its own GL thread, independent of Compose.

### UI Performance

- MVI + StateFlow ensures the UI only reacts to state emissions, never polling or blocking the main thread.
- Compose recomposition scope is kept tight via isolated per-domain ViewModels and `derivedStateOf` for computed state.
- The map is never destroyed during normal app use (single-screen architecture), avoiding MapLibre reinitialisation cost.

### Persistence

Room owns all structured app data — GPX files, route history, downloaded area metadata. MBTiles files are stored as raw SQLite files on disk; Room tracks their metadata only.

## Core Features (Planned)

- Offline map display with MBTiles tile cache
- GPX track import, display, and export
- Offline tile download — GPX bounding box mode and manual tile picker mode
- Offline routing via BRouter with configurable profiles
- Turn-by-turn navigation with TTS via foreground service
- Off-route detection and automatic re-routing
- Background navigation (continues when app is in background)
