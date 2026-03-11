package de.codevoid.aWayToGo.map

/**
 * Immutable snapshot of all UI state that drives the map screen.
 *
 * Held in a [MapViewModel] and observed by [MapActivity] via a [kotlinx.coroutines.flow.StateFlow].
 * Every field change produces a new instance; equality is structural (data class).
 *
 * | Field            | What it controls                                             |
 * |------------------|--------------------------------------------------------------|
 * | [mode]           | Which overlay set is visible (Explore / Navigate / Edit).   |
 * | [isInPanningMode]| Crosshair visible, GPS tracking suspended.                  |
 * | [isMenuOpen]     | Hamburger panel expanded.                                   |
 */
data class MapUiState(
    val mode: AppMode = AppMode.EXPLORE,
    val isInPanningMode: Boolean = false,
    val isMenuOpen: Boolean = false,
)
