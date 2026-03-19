package de.codevoid.aWayToGo.map

import de.codevoid.aWayToGo.update.DownloadProgress

sealed class DownloadState {
    object Idle        : DownloadState()
    object Checking    : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()
    object UpToDate    : DownloadState()
    object Ready       : DownloadState()
    object Error       : DownloadState()
    object Installing  : DownloadState()
}

/**
 * Immutable snapshot of all UI state that drives the map screen.
 *
 * Held in a [MapViewModel] and observed by [MapActivity] via a [kotlinx.coroutines.flow.StateFlow].
 * Every field change produces a new instance; equality is structural (data class).
 *
 * ## Camera lock model
 *
 * Camera lock is active when `mode == NAVIGATE || isCameraLocked`:
 *   - [AppMode.NAVIGATE]: camera is locked to the GPS puck.  Each GPS fix calls
 *     `moveCamera` (instant, no animation) so puck and camera centre are always
 *     coincident.  [isCourseUpEnabled] selects Course-Up vs North-Up bearing.
 *   - [isCameraLocked] = true: same TRACKING_GPS + course-up lock, but without
 *     entering NAVIGATE mode (no tilt, no mode change).  Toggled by the
 *     myLocationButton in EXPLORE mode.
 *   - [AppMode.EXPLORE] / [AppMode.EDIT] with [isCameraLocked] = false: camera
 *     is free.  Puck moves on screen as GPS fixes arrive; camera is independent.
 *
 * [isInPanningMode] temporarily suspends the lock (user is panning the map).
 * After the user stops, [lockResumeDelayS] seconds elapse before lock re-engages
 * (NAVIGATE only — in EXPLORE the lock is cancelled permanently on pan).
 *
 * | Field               | What it controls                                             |
 * |---------------------|--------------------------------------------------------------|
 * | [mode]              | App mode; NAVIGATE implies camera lock.                     |
 * | [isCameraLocked]    | Explicit camera lock: TRACKING_GPS + course-up, without     |
 *                         entering NAVIGATE (no tilt, no mode change).               |
 * | [isInPanningMode]   | Crosshair visible, camera lock temporarily suspended.       |
 * | [isMenuOpen]        | Hamburger panel expanded.                                   |
 * | [isSearchOpen]      | Search overlay visible (Explore mode only).                 |
 * | [isSatelliteEnabled]| Map style switched to hybrid satellite+labels.              |
 * | [isDarkMode]        | Map style switched to dark variant.                         |
 * | [isCourseUpEnabled] | In Navigate/lock: bearing tracks GPS course (Course Up).    |
 *                         When false, north always points up (North Up).              |
 * | [lockResumeDelayS]  | Seconds to wait after panning stops before re-locking.      |
 * | [isFrequentUpdatesEnabled]  | Debug: check for updates every 5 min while screen is on.  |
 * | [isMapLockMenuOpen]         | Map-lock context menu open (long-press on crosshair).      |
 * | [isFuelStationsEnabled]     | Fuel station POI symbols visible on the map.               |
 * | [isInOfflineMapsMenu]       | Offline Maps submenu layer is open.                         |
 * | [isOfflineMode]             | All tile requests served from cache only; no network.        |
 * | [isInMapStyleMenu]          | Map Style submenu layer is open (preset list visible).      |
 * | [isInMapStyleMode]          | Map style mode active: panel expanded, chrome off screen.   |
 * | [downloadState]             | Current APK self-update state (Idle/Checking/Downloading/…). |
 */
data class MapUiState(
    val mode: AppMode = AppMode.EXPLORE,
    val isCameraLocked: Boolean = false,
    val isInPanningMode: Boolean = false,
    val isMenuOpen: Boolean = false,
    val isSearchOpen: Boolean = false,
    val isSatelliteEnabled: Boolean = false,
    val isDarkMode: Boolean = false,
    val isInSettingsMenu: Boolean = false,
    val isInDebugMenu: Boolean = false,
    val isDebugMode: Boolean = false,
    val isCourseUpEnabled: Boolean = false,
    val lockResumeDelayS: Int = 5,
    val isFrequentUpdatesEnabled: Boolean = false,
    val isMapLockMenuOpen: Boolean = false,
    val isFuelStationsEnabled: Boolean = false,
    val isInOfflineMapsMenu: Boolean = false,
    val isInTileSelectMode: Boolean = false,
    val isOfflineMode: Boolean = false,
    val isInMapStyleMenu: Boolean = false,
    val isInMapStyleMode: Boolean = false,
    val isAppsMenuOpen: Boolean = false,
    val downloadState: DownloadState = DownloadState.Idle,
)
