package de.codevoid.aWayToGo.diagnostic

import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import de.codevoid.aWayToGo.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Diagnostic activity: [MapLibreMap.setMaximumFps] capped at 30 + prefetch enabled.
 *
 * Tests whether capping the GL render rate at 30 fps frees enough CPU/GPU
 * headroom to make tile loading feel less intrusive.  The hypothesis is that
 * at 60 fps the GL thread is starved for time, and halving the render budget
 * lets tile decode/upload threads run faster so the "tile pop-in" is shorter.
 *
 * Prefetch is enabled with a zoom-delta of 4 (load tiles 4 zoom levels above
 * the current zoom) so that zoom-out transitions hit fewer cache misses.
 *
 * How to compare:
 *   make diag            → SurfaceView / 60 fps / no explicit prefetch
 *   make diag-maxfps     → SurfaceView / 30 fps / prefetch delta 4
 *
 * What to observe while panning / zooming:
 *   - Is tile pop-in shorter or less frequent?
 *   - Does panning feel smoother despite the lower frame rate?
 *   - Does the OSD fps settle at ~30 as expected?
 *
 * Launch:
 *   adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticMaxFpsActivity
 */
class DiagnosticMaxFpsActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var osdView: TextView

    private var lastFrameNs = 0L
    private var frameCount = 0
    private var windowStartNs = 0L
    private var lastFps = 0
    private var lastDtMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) {
                lastDtMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
            }
            lastFrameNs = frameTimeNanos

            frameCount++
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            val windowNs = frameTimeNanos - windowStartNs
            if (windowNs >= 1_000_000_000L) {
                lastFps = (frameCount * 1_000_000_000L / windowNs).toInt()
                frameCount = 0
                windowStartNs = frameTimeNanos
            }

            osdView.text = "MaxFPS:30 Prefetch:4\nfps  $lastFps  dt:${lastDtMs}ms"

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json" +
                       "?key=${BuildConfig.MAPTILER_KEY}"

        val root = FrameLayout(this)

        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            text = "MaxFPS:30 Prefetch:4\nfps  --  dt:--ms"
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) })

        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            // Cap the GL render rate.  At 30 fps the GL thread wakes every
            // ~33 ms instead of ~16 ms, freeing CPU and GPU time for tile
            // decode/upload threads between frames.
            map.setMaximumFps(30)

            // Prefetch tiles 4 zoom levels above the current zoom so
            // zoom-out transitions have tiles pre-loaded.
            map.setPrefetchZoomDelta(4)

            map.setStyle(styleUrl) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), 12.0)
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
