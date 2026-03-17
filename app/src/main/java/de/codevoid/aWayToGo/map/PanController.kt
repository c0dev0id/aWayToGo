package de.codevoid.aWayToGo.map

import android.graphics.PointF
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import de.codevoid.aWayToGo.remote.RemoteKey
import kotlin.math.sqrt

// Desired pan speed in screen pixels per second.
private const val PAN_SPEED_PX_PER_SEC = 120f

// Joystick speed table: discrete magnitude → px/s.
// Hardware sends magnitudes 2–5 (dead zone swallows 0–1).
// Piecewise-linear interpolation in joyMagnitudeToSpeed().
//   mag 2 →  15 px/s
//   mag 3 →  30 px/s
//   mag 4 →  60 px/s
//   mag 5 → 120 px/s
// Adjacent levels ramp in 300 ms (JOY_RAMP_RATE = 1/0.3 ≈ 3.33 mag-units/s).
private const val JOY_RAMP_RATE = 1f / 0.3f  // magnitude units per second

// Desired zoom speed in MapLibre zoom levels per second.
private const val ZOOM_SPEED_PER_SEC = 1.5f

// How far ahead (ms) each animateCamera call targets.
// The GL thread interpolates this segment smoothly at its own refresh rate.
// At 59 fps (16ms frames) 32ms gives the GL thread 2 frames of animation
// to interpolate between main-thread updates — enough for smooth movement
// without adding noticeable look-ahead lag.
private const val PAN_LOOK_AHEAD_MS = 32

/**
 * Linear start-ramp: 0 → 1 over [durationMs].
 *
 * Apply as: `speed = maxSpeed * (0.5f + 0.5f * startRamp(...))`
 * to get a 50 % → 100 % speed progression over the ramp window.
 *
 * Used by both the pan and zoom key loops so any tuning to the
 * ramp curve benefits all key-driven movement at once.
 */
private fun startRamp(frameTimeNanos: Long, startNs: Long, durationMs: Long = 2000L): Float =
    ((frameTimeNanos - startNs) / 1_000_000L / durationMs.toFloat()).coerceIn(0f, 1f)

/**
 * Owns all D-pad / joystick panning and zoom-key state, and drives
 * the per-frame camera update.
 *
 * [MapActivity] creates one instance and delegates to it from:
 *   - [handleRemoteEvent] for [RemoteEvent.KeyDown], [RemoteEvent.KeyUp],
 *     and [RemoteEvent.JoyInput].
 *   - The [Choreographer.FrameCallback] for per-frame camera updates.
 *
 * ### Entering panning mode
 * [PanController] never mutates UI state directly.  When a D-pad press or
 * non-neutral joystick input should trigger panning mode, it calls the
 * [onEnterPanningMode] callback supplied at construction time.  The caller
 * (typically [MapActivity]) then delegates to the [MapViewModel].
 *
 * ### Coordinate conversion
 * Pan deltas are computed in screen pixels, then converted to [LatLng] via
 * [MapLibreMap.getProjection].  This correctly handles camera tilt, bearing,
 * and padding without any manual rotation or metres-per-pixel approximation.
 *
 * ### Thread safety
 * All methods must be called on the main thread.  [panStartNs] is written
 * in [onKeyDown]/[onKeyUp] (event callbacks) and read in [onFrame]
 * (Choreographer callback) — both on the main thread, so no synchronisation
 * is needed.
 *
 * @param onEnterPanningMode Invoked when input (D-pad or joystick) triggers
 *   a transition into panning mode.  Idempotent — [MapViewModel.enterPanningMode]
 *   is a no-op when already in panning mode.
 */
class PanController(private val onEnterPanningMode: () -> Unit) {

    // Active pan/zoom directions → vsync timestamp (ns) when the key was first pressed.
    // Used to compute the speed ramp-up in onFrame().
    private val panStartNs = mutableMapOf<RemoteKey, Long>()

    // Analog joystick state: normalised [-1, 1] axes from the last JoyInput event.
    // (0, 0) = joystick released.  Written by onJoyInput, read by onFrame — both on
    // the main thread, no synchronisation needed.
    private var joyDx = 0f
    private var joyDy = 0f

    // Smoothed joystick magnitude (0–5), ramped toward the target at JOY_RAMP_RATE.
    // Decouples the speed ramp from instantaneous stick input so acceleration and
    // deceleration take ~300ms per adjacent magnitude level.
    private var joyEffectiveMag = 0f

    // Last non-zero normalised direction: preserved so the ramp-down still applies
    // movement in the same direction after the stick is released.
    private var joyLastDirX = 0f
    private var joyLastDirY = 0f

    /**
     * Call when a D-pad or zoom key is pressed ([RemoteEvent.KeyDown]).
     *
     * Directional keys (UP/DOWN/LEFT/RIGHT) also call [onEnterPanningMode] to
     * show the crosshair and suspend GPS tracking.  Zoom keys do not affect
     * panning mode — they only update the zoom ramp.
     *
     * Non-pan/zoom keys are silently ignored.
     */
    fun onKeyDown(key: RemoteKey) {
        when (key) {
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                onEnterPanningMode()
                panStartNs[key] = System.nanoTime()
            }
            RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {
                panStartNs[key] = System.nanoTime()
            }
            else -> {}
        }
    }

    /**
     * Call when a key is released ([RemoteEvent.KeyUp]).
     *
     * Removes the key from the active-pan map and cancels any in-flight
     * [MapLibreMap.animateCamera] so the map stops immediately at the release
     * point rather than coasting to the last look-ahead target.
     *
     * Non-pan keys are also handled safely — removing a key that was never
     * added is a no-op.
     */
    fun onKeyUp(key: RemoteKey, map: MapLibreMap) {
        panStartNs.remove(key)
        // Cancel the in-flight animation so the map does not coast past the
        // release point.  The next Choreographer frame issues a fresh animation
        // for any remaining active directions.
        map.cancelTransitions()
    }

    /**
     * Call when a [RemoteEvent.JoyInput] arrives.
     *
     * Updates the raw joystick axes.  If either axis is non-zero, calls
     * [onEnterPanningMode] so the crosshair appears and GPS tracking suspends.
     * The effective magnitude is smoothed in [onFrame] rather than here, so
     * the ramp-up starts from the first Choreographer frame after this call.
     */
    fun onJoyInput(dx: Float, dy: Float) {
        joyDx = dx
        joyDy = dy
        if (dx != 0f || dy != 0f) onEnterPanningMode()
    }

    /**
     * Drive the per-frame camera update for panning and zooming.
     *
     * Accumulates pan and zoom deltas from all held D-pad keys and the analog
     * joystick, then issues a single [MapLibreMap.animateCamera] call that
     * looks [PAN_LOOK_AHEAD_MS] ms ahead so the GL thread can interpolate
     * smoothly between main-thread updates.
     *
     * Pan deltas are in screen pixels and converted to [LatLng] via the map
     * projection, which natively handles camera bearing, tilt, and padding.
     *
     * @param map            The [MapLibreMap] to animate.  Null-safe — returns 0
     *                       immediately if the map is not yet ready.
     * @param frameTimeNanos Vsync timestamp from [Choreographer.FrameCallback.doFrame].
     *                       Used to compute per-key ramp-up elapsed time.
     * @param dtNs           Frame delta (ns) since the previous frame.
     *                       Used to step the joystick magnitude ramp.
     * @return Pan speed in px/s (≥ 0) for OSD display.  0 when no pan is active.
     */
    fun onFrame(map: MapLibreMap?, frameTimeNanos: Long, dtNs: Long): Float {
        var panSpeed = 0f

        if (map == null) return panSpeed
        if (panStartNs.isEmpty() && joyDx == 0f && joyDy == 0f && joyEffectiveMag <= 0.01f) {
            return panSpeed
        }

        var totalDx   = 0f   // screen pixels, right = positive
        var totalDy   = 0f   // screen pixels, down  = positive
        var totalZoom = 0f

        for ((key, startNs) in panStartNs) {
            val ramp = startRamp(frameTimeNanos, startNs)
            when (key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                    val speed = PAN_SPEED_PX_PER_SEC * (0.5f + 0.5f * ramp)
                    val px    = speed * PAN_LOOK_AHEAD_MS / 1000f
                    if (speed > panSpeed) panSpeed = speed
                    when (key) {
                        RemoteKey.UP    -> totalDy -= px
                        RemoteKey.DOWN  -> totalDy += px
                        RemoteKey.LEFT  -> totalDx -= px
                        RemoteKey.RIGHT -> totalDx += px
                        else -> {}
                    }
                }
                RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {
                    val delta = ZOOM_SPEED_PER_SEC * (0.5f + 0.5f * ramp) * PAN_LOOK_AHEAD_MS / 1000f
                    if (key == RemoteKey.ZOOM_IN) totalZoom += delta else totalZoom -= delta
                }
                else -> {}
            }
        }

        // Analog joystick — ramped speed curve.
        // targetMag: hardware sends 0.4–1.0, multiply by 5 to get 2–5.
        val inputMag  = maxOf(kotlin.math.abs(joyDx), kotlin.math.abs(joyDy)) * 5f
        val dtS       = dtNs / 1_000_000_000f
        val rampDelta = JOY_RAMP_RATE * dtS
        joyEffectiveMag = when {
            joyEffectiveMag < inputMag ->
                (joyEffectiveMag + rampDelta).coerceAtMost(inputMag)
            joyEffectiveMag > inputMag ->
                (joyEffectiveMag - rampDelta).coerceAtLeast(inputMag)
            else -> joyEffectiveMag
        }

        if (joyEffectiveMag > 0.01f) {
            // Update last known direction while stick is active.
            val len = sqrt(joyDx * joyDx + joyDy * joyDy)
            if (len > 0.01f) {
                joyLastDirX = joyDx / len
                joyLastDirY = joyDy / len
            }
            val speedPxS = joyMagnitudeToSpeed(joyEffectiveMag)
            val px = speedPxS * PAN_LOOK_AHEAD_MS / 1000f
            totalDx +=  joyLastDirX * px
            totalDy += -joyLastDirY * px   // joy Y+ = screen-up = subtract from dy
            if (speedPxS > panSpeed) panSpeed = speedPxS
        }

        if (totalDx != 0f || totalDy != 0f || totalZoom != 0f) {
            val pos    = map.cameraPosition
            val target = pos.target

            // Convert screen-space pan delta to LatLng via the map projection.
            // This handles bearing, tilt, and padding correctly without any
            // manual rotation matrix or metres-per-pixel approximation.
            val newLatLng = if (target != null && (totalDx != 0f || totalDy != 0f)) {
                val screenPos = map.projection.toScreenLocation(target)
                map.projection.fromScreenLocation(
                    PointF(screenPos.x + totalDx, screenPos.y + totalDy)
                )
            } else target

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(newLatLng)
                        .zoom(pos.zoom + totalZoom)
                        .bearing(pos.bearing)
                        .tilt(pos.tilt)
                        .padding(pos.padding)
                        .build(),
                ),
                PAN_LOOK_AHEAD_MS,
            )
        }

        return panSpeed
    }

    /**
     * Maps a smoothed magnitude (0–5) to a pan speed in px/s:
     *   0 →   0 px/s
     *   2 →  15 px/s
     *   3 →  30 px/s
     *   4 →  60 px/s
     *   5 → 120 px/s
     *
     * Values between integer levels are linearly interpolated; values outside
     * [0, 5] are clamped to the nearest endpoint.
     */
    private fun joyMagnitudeToSpeed(mag: Float): Float = when {
        mag <= 0f -> 0f
        mag < 2f  -> mag / 2f * 15f           // 0 → 0, 2 → 15
        mag < 3f  -> 15f + (mag - 2f) * 15f   // 2 → 15, 3 → 30
        mag < 4f  -> 30f + (mag - 3f) * 30f   // 3 → 30, 4 → 60
        mag < 5f  -> 60f + (mag - 4f) * 60f   // 4 → 60, 5 → 120
        else      -> 120f
    }
}
