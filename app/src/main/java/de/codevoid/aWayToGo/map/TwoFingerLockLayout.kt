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
 * Re-enable scroll as soon as the multi-finger gesture ends — either when the
 * pointer count drops back to one (so single-finger panning resumes naturally)
 * or when all fingers are lifted.
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
 */
class TwoFingerLockLayout(context: Context) : FrameLayout(context) {

    private var map: MapLibreMap? = null

    /** Call once the [MapLibreMap] instance is available from [MapView.getMapAsync]. */
    fun attachMap(m: MapLibreMap) {
        map = m
    }

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
                    // Dropping from two fingers back to one — re-enable panning
                    // so the remaining finger can continue scrolling normally.
                    m.uiSettings.isScrollGesturesEnabled = true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // All fingers lifted (or gesture cancelled) — unconditionally
                    // restore scroll in case it was disabled.
                    m.uiSettings.isScrollGesturesEnabled = true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
