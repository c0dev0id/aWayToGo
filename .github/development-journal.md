# Development Journal — aWayToGo

## Software Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI Framework | Android Views (map screen); Jetpack Compose reserved for future secondary screens |
| Architecture | MVI |
| Map Rendering | MapLibre Native (Android SDK) |
| Offline Map Storage | MBTiles (SQLite) |
| Tile Source | Maptiler (cloud now, self-hostable via tileserver-gl later) |
| Routing Engine | BRouter |
| Local Database | Room |
| Preferences | DataStore |
| Background Work | WorkManager |

## Key Decisions

### Compose removed from the map screen

**Measurement:** On the target device, the `DiagnosticActivity` (raw `MapView` + `Choreographer`, no Compose) ran at **59 fps / 16 ms dt**. The `MainActivity` (same `MapView` wrapped in `AndroidView` inside Compose) ran at **14 fps / 50 ms dt**. Compose added ~34 ms of main-thread overhead per frame on this hardware, reducing perceived responsiveness from smooth to visibly choppy.

**Decision:** The map screen (`MapActivity`) is implemented as a raw `ComponentActivity` with:
- A `MapView` filling the screen in SurfaceView mode (GL thread is independent of the main thread).
- A single `Choreographer.FrameCallback` that drives both panning and the OSD at vsync rate.
- Plain Android `TextView` for the OSD overlay.

All features from the Compose version are preserved: location tracking, remote control, pan ramp-up, `animateCamera` look-ahead, gesture settings, tilt toggle, tracking toggle.

Compose is not removed from the project — it is kept for potential use in future secondary screens (settings, route management, GPX browser). The `buildFeatures { compose = true }` block and Compose BOM are removed from `app/build.gradle.kts` for now and can be re-added when needed.

**Implication for future development:** New map-adjacent UI elements (POI overlays, navigation bar, routing panel) should be implemented as Android Views or `SurfaceView` layers, not as Compose composables on top of the map. This keeps the main thread budget free for MapLibre callbacks and input handling.

### MapLibre rendering tuning — pixelRatio, maxFps, prefetchDelta

**Benchmark methodology:** A parameterised `DiagnosticMaxFpsActivity` was built to run 10s pan benchmarks with configurable zoom, maxFps cap, pixelRatio, prefetchDelta, and crossSourceCollisions. Each run records `gl_fps_avg`, `gl_fps_min`, and `gl_fps_max` from MapLibre's `OnDidFinishRenderingFrameListener`. These are the primary metrics — they reflect what the user actually sees.

**Findings:**

- **pixelRatio=3.0 is the most impactful setting.** Higher pixelRatio causes MapLibre to satisfy tile quality from a lower zoom tier → fewer, larger tiles per viewport → less tile-fetch congestion during pan. The effect is most visible in `gl_fps_min`: pxr=1.0 drops to min=4 at zoom 14/16 (visible freeze); pxr=3.0 holds min=18. Tested 1.0, 1.5, 2.0, 3.0, 4.0 — 3.0 was the sweet spot on this device (4.0 did not improve min further).

- **maxFps=60 over 30.** At 30fps, the render loop fires every 33ms, so a tile-load stall consumes a proportionally larger fraction of the frame budget. At 60fps, the scheduler has more recovery opportunities per second. Battery impact is acceptable; 120fps adds unnecessary GPU load with diminishing returns for a navigation use case.

- **prefetchDelta=2 over 4.** Less aggressive prefetching reduces concurrent in-flight tile requests competing with the current viewport, improving gl_fps during active pan. Counterintuitive but consistent across all 24 benchmark runs.

- **crossSourceCollisions**: no measurable effect on gl_fps. Left at default (true).

**Applied settings:** `pixelRatio=3.0`, `maxFps=60`, `prefetchDelta=2`

**Note:** `OnDidFinishRenderingFrameListener.frameRenderingTime` is always 0 in MapLibre Native Android 11.0.0 — the field is not populated. Do not rely on it.

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
│  Android Views + Choreographer (map)    │
│  MVI ViewModels for stateful panels     │
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

**MapDomain** — tile source, MBTiles management, camera state, offline area tracking. Observes `PoiGroup.isVisible` from LibraryDomain and maintains corresponding GeoJSON sources and SymbolLayers in MapLibre.

**RoutingDomain** — BRouter integration and profile management. BRouter runs as a bounded service inside the app process; a Kotlin wrapper isolates it so no other module touches BRouter directly.

**NavigationDomain** — active navigation session, GPS tracking, off-route detection, TTS instructions. Runs as a Foreground Service to continue in the background. Publishes state as a `StateFlow` that the UI layer observes. GPS is the single source of truth here — both LibraryDomain (RecorderDomain path) and MapDomain observe it.

**LibraryDomain** — the central data hub for all geographic data at rest. Owns four entity types (see below). All other domains that need geographic input (RoutingDomain, NavigationDomain, MapDomain overlays) read from the Library. All domains that produce geographic data (RecorderDomain, GpxImporter) write to it. The editor and RemoteDomain do both.

**RecorderDomain** — ride recording session. Samples GPS + sensor data at a configurable rate, stores `RideMetricSample` rows in Room, and on session end creates a `RecordedRide` (a `Trip` with `type=RECORDED`) in LibraryDomain. Runs as part of the NavigationDomain Foreground Service (or a separate Foreground Service if recording without active navigation).

**RemoteDomain** — backend API client, authentication, sync queue, and live position upload. All network backend concerns are isolated here. Other domains call `RemoteRepository` interfaces; they have no knowledge of the backend protocol.

**SettingsDomain** — user preferences, backed by DataStore.

### LibraryDomain — Entity Model

GPX is a serialisation format, not the domain model. `GpxParser` and `GpxSerializer` are infrastructure classes that convert between GPX files and Library entities. The Library's internal representation is richer than GPX (typed Kotlin data, not freeform XML), and it is GPX-format-agnostic at the domain level.

**`Trip`** — a named, stored journey. Fields: `id`, `name`, `description`, `type: TripType (PLANNED | RECORDED)`, `createdAt`, `updatedAt`, `distanceM`, `durationMs`, `elevationGainM`. Contains track geometry (list of `TrackPoint`) and optional waypoints. A `PLANNED` trip is user-created or imported. A `RECORDED` trip is produced by RecorderDomain and also carries a list of `RideMetricSample` rows in a joined Room table.

**`RideMetricSample`** — one timestamped sensor snapshot belonging to a `RECORDED` Trip. Fields: `tripId` (FK), `timestamp`, `lat`, `lng`, `altM`, `speedKmh`, `leanAngleDeg`, `signalStrength`, and reserved columns for future sensors. Serialises to a GPX `<trkpt>` with `<extensions>` on export (custom namespace for lean angle and signal; Garmin TrackPointExtension for speed and altitude).

**`Location`** — a hand-picked, curated place. Richer than a GPX waypoint — metadata that doesn't fit in GPX is stored natively. Fields: `id`, `name`, `description`, `address`, `lat`, `lng`, `category: LocationCategory`, `tags` (separate join table), `personalNotes`, `isFavourite`, `visitCount`, `lastVisitedAt`, `createdAt`, `updatedAt`, `syncId`. Optional future fields: phone, website, opening hours. The user maintains a small, curated list — not bulk data.

**`PoiGroup`** — a named collection of bulk geographic points, used as a toggleable map layer. Fields: `id`, `name`, `description`, `iconId`, `sourceUrl` (optional — the URL the group was populated from, for manual re-fetch), `lastUpdatedAt`, `pointCount` (cached), `isVisible`, `alertMode: PoiAlertMode`, `syncId`. Points within a group are stored as `PoiPoint` rows.

`PoiAlertMode` is an enum: `NONE` (display only — point appears on the map, no alert), `INFORM` (soft notification when a point is nearby), `WARN` (prominent alert when a point is nearby — intended for safety-critical groups such as speed cameras). The proximity detection logic lives in NavigationDomain, which already has access to GPS position and heading. For `WARN` groups, alerts should be bearing-aware — fire when the user is *approaching* a point, not after passing it. `alertMode` is a group-level setting; individual `PoiPoint` rows carry no alert state.

**`PoiPoint`** — a single point belonging to a `PoiGroup`. Fields: `id`, `groupId` (FK), `name`, `lat`, `lng`. Intentionally minimal — these are bulk data, not individually managed entities. Queried spatially via `WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?` for viewport-based map rendering.

**Semantics that differ between `Location` and `PoiPoint`:**
- A `Location` is individually created, edited, and deleted by the user.
- `PoiPoint` rows are always managed as a group: the whole group is imported at once and replaced wholesale on re-fetch. Individual points are never edited.
- A `PoiPoint` can be promoted to a `Location` via `PromotePoiToLocationUseCase` — copies coordinates and name, opens the Location editor pre-filled, creates a new `Location`. The `PoiPoint` is unchanged.

**POI Group population:** The user can populate a group by (a) pasting text into an edit control, or (b) triggering a fetch from `sourceUrl`. The canonical line format is `lat,lng,name` (name optional; comma or tab separated; blank lines and `#`-prefixed lines ignored). The URL fetcher supports at minimum GPX `<wpt>` elements and the same CSV-like format. The UI for editing large groups (virtualized list vs. capped text area) is deferred — the data model is the same either way. Raw text is never stored; it is parsed to `PoiPoint` rows on save and re-serialised to text on open.

**`isVisible` on `PoiGroup` is persisted, not transient UI state.** The map layer activation state survives app restarts. MapDomain observes `PoiGroup.isVisible` and maintains the corresponding GeoJSON source and SymbolLayer.

### Cross-Domain Workflows

Domains do not depend on each other directly. Cross-domain workflows are orchestrated by UseCases that sit above the repositories:

- **StartNavigationUseCase** — takes a `Route` from RoutingDomain, hands it to NavigationDomain, starts the foreground service.
- **ImportGpxUseCase** — calls `GpxParser` (infrastructure), routes `<trk>` data to LibraryDomain as a `Trip(PLANNED)`, routes `<wpt>` data to LibraryDomain as `Location` or `PoiGroup` depending on count and user intent, computes bounding box and triggers MapDomain tile download suggestion.
- **RerouteUseCase** — triggered by NavigationDomain off-route event, calls RoutingDomain with current GPS position, updates NavigationDomain with new route.
- **PromotePoiToLocationUseCase** — copies a `PoiPoint`'s coordinates and name into a new `Location`, opens the Location editor pre-filled.
- **SyncLibraryUseCase** — pushes/pulls Trips, Locations, and PoiGroups between LibraryDomain and RemoteDomain. Offline-first: works fully without a connection, syncs opportunistically when online.
- **LiveTrackingUseCase** — wires NavigationDomain's GPS stream to `RemoteDomain.TrackingRepository.reportPosition()`. The repository decides whether to send immediately, batch, or queue for later. NavigationDomain has no knowledge of the backend.

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

- The map screen uses a single `Choreographer.FrameCallback` instead of Compose to keep the main thread budget for MapLibre input callbacks. This yielded a measured improvement from 14 fps to 59 fps on the target device.
- New map-adjacent UI elements must be Android Views, not Compose composables, to avoid reintroducing the main-thread overhead.
- MVI + StateFlow will be used for stateful panels (routing, navigation bar) when they are added.
- The map is never destroyed during normal app use (single-screen architecture), avoiding MapLibre reinitialisation cost.

### Persistence

Room owns all structured app data. Key tables: `trips`, `ride_metric_samples`, `locations`, `location_tags`, `poi_groups`, `poi_points`, `downloaded_areas`. MBTiles files are stored as raw SQLite files on disk; Room tracks their metadata only. `PoiPoint` rows use plain lat/lng columns; viewport queries use `BETWEEN` range predicates, which are adequate for datasets up to tens of thousands of points per group.

### Canonical UI Sizes (MapActivity)

These values were arrived at through visual iteration on the target device and are the baseline for all on-map UI going forward. No UI element should deviate from these without a documented reason.

| Element | Value |
|---|---|
| Icon buttons (hamburger, locate-me) | 64 × 64 dp |
| Half-pill buttons (Ride, Plan) | 172 × 64 dp |
| Search circle | 144 dp diameter, transparent background |
| Explore bar bottom margin | 16 dp |
| Icon button margins (left / top or bottom) | 16 dp |
| Primary button text (Ride, Plan) | 26 sp bold |
| Minimum text size (enforced app-wide) | 20 sp |

**Minimum text size rationale:** The app is used on a motorcycle mount, often with gloves. 20 sp was validated as the smallest text that remains readable at arm's length while riding. All text in the app — including secondary labels, overlays, and debug info — must be ≥ 20 sp.

**Search circle transparency:** The search circle has a transparent background so it does not occlude the half-pill buttons behind it. The ripple mask is still a circle, so the tap feedback is correctly contained within the circle boundary.

## Core Features (Planned)

- Offline map display with MBTiles tile cache
- GPX track import, display, and export (`GpxParser`/`GpxSerializer` in infrastructure layer)
- Offline tile download — GPX bounding box mode and manual tile picker mode
- Offline routing via BRouter with configurable profiles
- Turn-by-turn navigation with TTS via foreground service
- Off-route detection and automatic re-routing
- Background navigation (continues when app is in background)
- Ride recorder — samples GPS + sensors (speed, altitude, lean angle, signal) into `RideMetricSample` rows; saves as `Trip(RECORDED)`; exports to GPX with `<extensions>`
- Library — user-managed Trips (planned + recorded), Locations (rich curated places), POI Groups (bulk toggleable map layers)
- POI Group editor — populate from URL fetch or paste; `lat,lng,name` line format; stored as Room rows, not raw text
- Location manager — individual curated places with rich metadata
- Remote backend sync — offline-first; syncs Trips, Locations, POI Groups when online
- Live position reporting to backend during a ride
