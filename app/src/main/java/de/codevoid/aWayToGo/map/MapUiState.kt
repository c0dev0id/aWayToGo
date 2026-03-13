package de.codevoid.aWayToGo.map

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
 * | [isCourseUpEnabled] | Map rotates so current GPS course points up (Course Up).   |
 *                         When false, north always points up (North Up).              |
 * | [dragLineStyle]     | Which animation style the drag line uses (Whip / Lasso).  |
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
    val dragLineStyle: DragLineAnimator.Style = DragLineAnimator.Style.WHIP,
)
