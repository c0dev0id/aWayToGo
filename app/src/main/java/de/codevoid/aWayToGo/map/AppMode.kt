package de.codevoid.aWayToGo.map

/** Top-level application mode — determines which UI chrome is visible and
 *  how the remote control keys are interpreted.
 *
 *  EXPLORE  — default entry state; free map movement, library/layer access,
 *             no crosshair.
 *  NAVIGATE — follow-mode navigation (free-roam or along a route/track);
 *             full remote control, auto-return-to-follow timer active.
 *  EDIT     — route/trip editor; crosshair always visible, editing toolbar
 *             active, no remote coverage needed.
 */
enum class AppMode { EXPLORE, NAVIGATE, EDIT }
