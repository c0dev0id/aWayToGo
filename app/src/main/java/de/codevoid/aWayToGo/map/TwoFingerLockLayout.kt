package de.codevoid.aWayToGo.map

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import org.maplibre.android.maps.MapLibreMap

/**
 * FrameLayout that prevents map translation while a two-finger gesture is active.
 *
 * ### Problem
 * MapLibre allows the map to pan (translate) even while two fingers are on
 * screen, making precise pinch-zoom and rotate gestures difficult — the map
 * drifts in the direction of the average finger movement instead of staying
 * fixed under the fingers.
 *
 * ### Solution
 * Intercept touch events at the root layout level.  When a second finger
 * touches down, disable MapLibre's scroll gestures and cancel any in-flight
 * pan transition.  MapLibre checks [UiSettings.isScrollGesturesEnabled] on
 * every MOVE event, so the change takes effect immediately without needing to
 * cancel and replay the gesture sequence.
 *
 * Scroll gestures remain disabled throughout — single-finger panning is owned
 * entirely by MapActivity's custom touch listener, which handles both plain
 * pans and the 2→1 finger transition via ACTION_POINTER_UP.
 *
 * ### Usage
 * Use as the root layout in the activity.  After [MapLibreMap] is ready, call
 * [attachMap] once:
 *
 * ```kotlin
 * val root = TwoFingerLockLayout(this)
 * // … build view hierarchy …
 * mapView.getMapAsync { m ->
 *     root.attachMap(m)
 * }
 * ```
 *
 * ### Lock-ring animation callbacks
 * [onSingleTouchDown] is called when a single finger touches down.
 * [onSingleTouchEnd] is called when all fingers are lifted or the gesture is
 * cancelled.  [MapActivity] uses these to start and cancel the lock-ring arc
 * animation that precedes the map-lock context menu.
 */
class TwoFingerLockLayout(context: Context) : FrameLayout(context) {

    private var map: MapLibreMap? = null

    /** Call once the [MapLibreMap] instance is available from [MapView.getMapAsync]. */
    fun attachMap(m: MapLibreMap) {
        map = m
    }

    /**
     * Fired on [MotionEvent.ACTION_DOWN] (first finger down, single-touch gesture start).
     * [MapActivity] uses this to begin the lock-ring animation after a 50 ms delay.
     * The [MotionEvent] is passed so callers can hit-test against UI panels and suppress
     * the lock ring when the touch lands on a button or overlay (not on the map).
     */
    var onSingleTouchDown: ((MotionEvent) -> Unit)? = null

    /**
     * Fired on [MotionEvent.ACTION_UP] or [MotionEvent.ACTION_CANCEL] (all fingers lifted
     * or gesture cancelled).  [MapActivity] uses this to abort the lock-ring animation if
     * the finger is released before the 500 ms long-press threshold.
     */
    var onSingleTouchEnd: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val m = map
        if (m != null) {
            when (ev.actionMasked) {

                MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                    // Second finger landed.  Stop any in-flight pan animation
                    // and lock scroll for the duration of this gesture so only
                    // scale and rotate are applied.
                    m.cancelTransitions()
                    m.uiSettings.isScrollGesturesEnabled = false
                }

                MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount == 2) {
                    // Dropping from two fingers back to one.  Keep scroll disabled —
                    // single-finger panning is handled entirely by MapActivity's custom
                    // touch listener; re-enabling here would cause MapLibre to also
                    // scroll the map, doubling the movement speed.
                    m.uiSettings.isScrollGesturesEnabled = false
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // All fingers lifted (or gesture cancelled).  Keep scroll disabled
                    // for the same reason — the custom touch listener owns all panning.
                    m.uiSettings.isScrollGesturesEnabled = false
                }
            }
        }

        // Lock-ring animation hooks (fired regardless of map readiness).
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN            -> onSingleTouchDown?.invoke(ev)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL          -> onSingleTouchEnd?.invoke()
        }

        return super.dispatchTouchEvent(ev)
    }
}
