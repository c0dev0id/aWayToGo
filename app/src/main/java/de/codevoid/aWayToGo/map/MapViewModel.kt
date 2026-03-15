package de.codevoid.aWayToGo.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.aWayToGo.update.AppUpdater
import de.codevoid.aWayToGo.update.ConnectivityChecker
import de.codevoid.aWayToGo.map.TileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = getApplication<Application>()
        .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MapUiState())

    init {
        // Restore settings persisted from the previous session.
        // Direct _uiState.update avoids writing the pref back during init.
        if (prefs.getBoolean("debug_mode", false))
            _uiState.update { it.copy(isDebugMode = true) }
        if (prefs.getBoolean("frequent_updates", false))
            _uiState.update { it.copy(isFrequentUpdatesEnabled = true) }
    }

    private val appUpdater       = AppUpdater(getApplication())
    private var _downloadedApk: File? = null
    private var downloadJob:     Job? = null
    private var frequentUpdateJob: Job? = null

    private fun setDownloadState(s: DownloadState) = _uiState.update { it.copy(downloadState = s) }

    fun startDownload(url: String) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                val apk = appUpdater.downloadApk(url) { p ->
                    setDownloadState(DownloadState.Downloading(p))
                }
                _downloadedApk = apk
                setDownloadState(DownloadState.Ready)
            } catch (_: CancellationException) {
                setDownloadState(DownloadState.Idle)
            } catch (_: Exception) {
                setDownloadState(DownloadState.Error)
                delay(2_000)
                setDownloadState(DownloadState.Idle)
            }
        }
    }

    fun checkUpdateIfDue() {
        if (_downloadedApk != null) { setDownloadState(DownloadState.Ready); return }
        if (downloadJob?.isActive == true) return
        val lastCheck = prefs.getLong("last_update_check_ms", 0L)
        if (System.currentTimeMillis() - lastCheck < 24 * 60 * 60_000L) return
        if (!ConnectivityChecker.isStableOnline(getApplication())) return
        viewModelScope.launch {
            prefs.edit().putLong("last_update_check_ms", System.currentTimeMillis()).apply()
            val url = appUpdater.checkForUpdate() ?: return@launch
            appUpdater.cleanupStaleFiles(url)
            startDownload(url)
        }
    }

    fun checkOnDemand() {
        if (downloadJob?.isActive == true) return
        viewModelScope.launch {
            setDownloadState(DownloadState.Checking)
            val url = appUpdater.checkForUpdate()
            if (url == null) {
                setDownloadState(DownloadState.UpToDate)
                delay(2_000)
                setDownloadState(DownloadState.Idle)
                return@launch
            }
            appUpdater.cleanupStaleFiles(url)
            startDownload(url)
        }
    }

    fun installApk(activityClass: Class<out android.app.Activity>) {
        val apk = _downloadedApk ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            setDownloadState(DownloadState.Installing)
            try { appUpdater.installApk(apk, activityClass) }
            catch (_: CancellationException) { setDownloadState(DownloadState.Ready) }
            catch (_: Exception) { setDownloadState(DownloadState.Error) }
        }
    }

    fun startFrequentUpdatePolling() {
        stopFrequentUpdatePolling()
        frequentUpdateJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.downloadState == DownloadState.Idle &&
                    ConnectivityChecker.isStableOnline(getApplication())) {
                    val url = appUpdater.checkForUpdate()
                    if (url != null) { appUpdater.cleanupStaleFiles(url); startDownload(url) }
                }
                delay(5 * 60_000L)
            }
        }
    }

    fun stopFrequentUpdatePolling() { frequentUpdateJob?.cancel(); frequentUpdateJob = null }

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
                mode               = mode,
                isMenuOpen         = false,
                isInSettingsMenu   = false,
                isInDebugMenu      = false,
                isInOfflineMapsMenu = false,
                isInTileSelectMode = false,
                isInMapStyleMenu   = false,
                isInMapStyleMode   = false,
                isSearchOpen       = if (mode == AppMode.EXPLORE) current.isSearchOpen else false,
                isInPanningMode    = when (mode) {
                    AppMode.NAVIGATE, AppMode.EDIT -> false
                    else                           -> current.isInPanningMode
                },
                // NAVIGATE enables follow mode automatically; other modes disable it.
                isFollowModeActive = mode == AppMode.NAVIGATE,
            )
        }
    }

    /**
     * Enter panning mode: crosshair becomes visible, GPS camera tracking suspends.
     * Also disables follow mode — the user taking manual control breaks the GPS lock.
     *
     * Idempotent — calling when already in panning mode emits no state change.
     */
    fun enterPanningMode() {
        _uiState.update { it.copy(isInPanningMode = true, isFollowModeActive = false) }
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

    /**
     * Enable follow mode: camera locks on to the GPS puck and tracks every position
     * update. Also clears panning mode so the crosshair is hidden.
     *
     * Idempotent — repeated calls while already following produce no extra emissions.
     */
    fun enableFollowMode() {
        _uiState.update { it.copy(isFollowModeActive = true, isInPanningMode = false) }
    }

    /** Disable follow mode: camera stops tracking the GPS puck. */
    fun disableFollowMode() {
        _uiState.update { it.copy(isFollowModeActive = false) }
    }

    /** Toggle follow mode on/off. */
    fun toggleFollowMode() {
        _uiState.update { it.copy(isFollowModeActive = !it.isFollowModeActive, isInPanningMode = false) }
    }

    /** Expand the hamburger panel. */
    fun openMenu() {
        _uiState.update { it.copy(isMenuOpen = true) }
    }

    /** Collapse the hamburger panel and exit any open submenu. */
    fun closeMenu() {
        _uiState.update { it.copy(isMenuOpen = false, isInSettingsMenu = false, isInDebugMenu = false, isInOfflineMapsMenu = false, isInTileSelectMode = false, isInMapStyleMenu = false, isInMapStyleMode = false) }
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

    /** Toggle the debug OSD overlay on/off. Persists the choice across sessions. */
    fun toggleDebugMode() {
        _uiState.update { it.copy(isDebugMode = !it.isDebugMode) }
        prefs.edit().putBoolean("debug_mode", _uiState.value.isDebugMode).apply()
    }

    /**
     * Toggle Course Up / North Up orientation.
     *
     * When Course Up is enabled the map bearing tracks the GPS course (direction
     * of travel) so the driving direction always points toward the top of the screen.
     * When disabled (North Up) the map bearing returns to 0° (north at top).
     *
     * Rotation is driven by the Choreographer GPS follow loop; it only takes effect
     * while [isFollowModeActive] is true.
     */
    fun toggleCourseUp() {
        _uiState.update { it.copy(isCourseUpEnabled = !it.isCourseUpEnabled) }
    }

    /** Toggles the debug frequent-update polling (every 5 min while screen is on). Persists across sessions. */
    fun toggleFrequentUpdates() {
        _uiState.update { it.copy(isFrequentUpdatesEnabled = !it.isFrequentUpdatesEnabled) }
        prefs.edit().putBoolean("frequent_updates", _uiState.value.isFrequentUpdatesEnabled).apply()
    }

    /** Open the map-lock context menu (triggered by long-press on the crosshair). */
    fun openMapLockMenu() {
        _uiState.update { it.copy(isMapLockMenuOpen = true) }
    }

    /** Close the map-lock context menu. */
    fun closeMapLockMenu() {
        _uiState.update { it.copy(isMapLockMenuOpen = false) }
    }

    /** Toggle fuel station POI overlay on/off. */
    fun toggleFuelStations() {
        _uiState.update { it.copy(isFuelStationsEnabled = !it.isFuelStationsEnabled) }
    }

    /** Toggle offline mode: forces all tile requests to use the disk cache only. */
    fun toggleOfflineMode() {
        _uiState.update { it.copy(isOfflineMode = !it.isOfflineMode) }
        TileCache.isOfflineMode = _uiState.value.isOfflineMode
    }

    /** Enter the tile-selection / offline-download overlay (closes the menu). */
    fun enterTileSelectMode() {
        _uiState.update { it.copy(
            isMenuOpen          = false,
            isInSettingsMenu    = false,
            isInDebugMenu       = false,
            isInOfflineMapsMenu = false,
            isInTileSelectMode  = true,
        )}
    }

    /** Exit tile-selection mode. */
    fun exitTileSelectMode() {
        _uiState.update { it.copy(isInTileSelectMode = false) }
    }

    /** Enter the Map Style submenu layer (menu must already be open). */
    fun enterMapStyleMenu() {
        _uiState.update { it.copy(isInMapStyleMenu = true) }
    }

    /** Return from the Map Style submenu to the main menu layer. */
    fun exitMapStyleMenu() {
        _uiState.update { it.copy(isInMapStyleMenu = false, isInMapStyleMode = false) }
    }

    /** Enter map style mode: panel expands, chrome slides off screen. */
    fun enterMapStyleMode() {
        _uiState.update { it.copy(isInMapStyleMode = true) }
    }

    /** Exit map style mode: panel collapses back to submenu size, chrome returns. */
    fun exitMapStyleMode() {
        _uiState.update { it.copy(isInMapStyleMode = false) }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        frequentUpdateJob?.cancel()
    }

    /** Enter the Offline Maps submenu layer (menu must already be open).
     *  Also activates tile-selection mode so the grid appears on the map. */
    fun enterOfflineMapsMenu() {
        _uiState.update { it.copy(isInOfflineMapsMenu = true, isInTileSelectMode = true) }
    }

    /** Return from the Offline Maps submenu to the main menu layer.
     *  Also deactivates tile-selection mode. */
    fun exitOfflineMapsMenu() {
        _uiState.update { it.copy(isInOfflineMapsMenu = false, isInTileSelectMode = false) }
    }

}
