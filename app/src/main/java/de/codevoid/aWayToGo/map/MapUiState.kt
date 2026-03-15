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
 * | Field               | What it controls                                             |
 * |---------------------|--------------------------------------------------------------|
 * | [mode]              | Which overlay set is visible (Explore / Navigate / Edit).   |
 * | [isInPanningMode]   | Crosshair visible, GPS tracking suspended.                  |
 * | [isFollowModeActive]| Camera locked to GPS puck; tracks position each frame.      |
 * | [isMenuOpen]        | Hamburger panel expanded.                                   |
 * | [isSearchOpen]      | Search overlay visible (Explore mode only).                 |
 * | [isSatelliteEnabled]| Map style switched to hybrid satellite+labels.              |
 * | [isDarkMode]        | Map style switched to dark variant.                         |
 * | [isCourseUpEnabled]         | Map rotates so current GPS course points up (Course Up).   |
 *                               When false, north always points up (North Up).              |
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
    val isInPanningMode: Boolean = false,
    val isFollowModeActive: Boolean = false,
    val isMenuOpen: Boolean = false,
    val isSearchOpen: Boolean = false,
    val isSatelliteEnabled: Boolean = false,
    val isDarkMode: Boolean = false,
    val isInSettingsMenu: Boolean = false,
    val isInDebugMenu: Boolean = false,
    val isDebugMode: Boolean = false,
    val isCourseUpEnabled: Boolean = false,
    val isFrequentUpdatesEnabled: Boolean = false,
    val isMapLockMenuOpen: Boolean = false,
    val isFuelStationsEnabled: Boolean = false,
    val isInOfflineMapsMenu: Boolean = false,
    val isInTileSelectMode: Boolean = false,
    val isOfflineMode: Boolean = false,
    val isInMapStyleMenu: Boolean = false,
    val isInMapStyleMode: Boolean = false,
    val downloadState: DownloadState = DownloadState.Idle,
)
