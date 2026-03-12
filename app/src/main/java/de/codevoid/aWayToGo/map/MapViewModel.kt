package de.codevoid.aWayToGo.map

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the UI state for the map screen ([MapUiState]).
 *
 * [MapActivity] calls the mutating functions here and observes [uiState] to
 * drive its view updates reactively.  All view interactions (camera moves,
 * MapLibre calls, animations) remain in the Activity — this ViewModel only
 * manages what *state* the UI is in, not how it looks.
 *
 * ### State transitions
 * - [setMode] is the primary driver: atomically updates [MapUiState.mode],
 *   clears the open menu, and clears panning mode when entering NAVIGATE or EDIT.
 * - [enterPanningMode] / [exitPanningMode] toggle [MapUiState.isInPanningMode].
 *   The Activity's [MapActivity.renderUiState] reacts to that change to move the
 *   camera and play the crosshair animation.
 * - [openMenu] / [closeMenu] / [toggleMenu] drive [MapUiState.isMenuOpen]; the
 *   Activity reacts to trigger the panel expand/collapse animation.
 *
 * ### StateFlow distinctness
 * [MutableStateFlow] only emits when the value actually changes (structural
 * equality on the [MapUiState] data class), so callers like [enterPanningMode]
 * are idempotent by construction — repeated calls while already in panning mode
 * produce no extra emissions.
 */
class MapViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())

    /** Read-only view of the current UI state. */
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /**
     * Switch to [mode].
     *
     * Atomically:
     * - Sets [MapUiState.mode] to [mode].
     * - Clears [MapUiState.isMenuOpen] (menu always closes on mode change).
     * - Clears [MapUiState.isInPanningMode] when entering NAVIGATE or EDIT
     *   (NAVIGATE re-enables GPS tracking; EDIT pins the crosshair independently).
     */
    fun setMode(mode: AppMode) {
        _uiState.update { current ->
            current.copy(
                mode             = mode,
                isMenuOpen       = false,
                isInSettingsMenu = false,
                isInDebugMenu    = false,
                isSearchOpen     = if (mode == AppMode.EXPLORE) current.isSearchOpen else false,
                isInPanningMode  = when (mode) {
                    AppMode.NAVIGATE, AppMode.EDIT -> false
                    else                           -> current.isInPanningMode
                },
            )
        }
    }

    /**
     * Enter panning mode: crosshair becomes visible, GPS camera tracking suspends.
     *
     * Idempotent — calling when already in panning mode emits no state change.
     */
    fun enterPanningMode() {
        _uiState.update { it.copy(isInPanningMode = true) }
    }

    /**
     * Exit panning mode: crosshair hides, GPS tracking resumes.
     *
     * The Activity's [MapActivity.renderUiState] observes this transition and
     * calls [MapActivity.flyToLocation] to animate back to the user's position.
     */
    fun exitPanningMode() {
        _uiState.update { it.copy(isInPanningMode = false) }
    }

    /** Expand the hamburger panel. */
    fun openMenu() {
        _uiState.update { it.copy(isMenuOpen = true) }
    }

    /** Collapse the hamburger panel and exit any open submenu. */
    fun closeMenu() {
        _uiState.update { it.copy(isMenuOpen = false, isInSettingsMenu = false, isInDebugMenu = false) }
    }

    /** Toggle the hamburger panel open/closed. */
    fun toggleMenu() {
        _uiState.update { it.copy(isMenuOpen = !it.isMenuOpen) }
    }

    /** Show the search overlay (Explore mode only). */
    fun openSearch() {
        _uiState.update { it.copy(isSearchOpen = true) }
    }

    /** Hide the search overlay. */
    fun closeSearch() {
        _uiState.update { it.copy(isSearchOpen = false) }
    }

    /** Toggle satellite hybrid style on/off. */
    fun toggleSatellite() {
        _uiState.update { it.copy(isSatelliteEnabled = !it.isSatelliteEnabled) }
    }

    /** Toggle dark map style on/off. */
    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }

    /** Enter the Settings submenu layer (menu must already be open). */
    fun enterSettingsMenu() {
        _uiState.update { it.copy(isInSettingsMenu = true) }
    }

    /** Return from the Settings submenu to the main menu layer. */
    fun exitSettingsMenu() {
        _uiState.update { it.copy(isInSettingsMenu = false, isInDebugMenu = false) }
    }

    /** Enter the Debug submenu layer (Settings menu must already be open). */
    fun enterDebugMenu() {
        _uiState.update { it.copy(isInDebugMenu = true) }
    }

    /** Return from the Debug submenu to the Settings layer. */
    fun exitDebugMenu() {
        _uiState.update { it.copy(isInDebugMenu = false) }
    }

    /** Toggle the debug OSD overlay on/off. */
    fun toggleDebugMode() {
        _uiState.update { it.copy(isDebugMode = !it.isDebugMode) }
    }

}
