package de.codevoid.aWayToGo

import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Diagnostic activity: raw MapView with zero Compose overhead.
 *
 * Purpose: isolate whether the main-thread slowness seen in MainActivity
 * comes from Compose itself or from the device/MapLibre combination.
 *
 * How to read the OSD:
 *   fps  — Choreographer frame callbacks per second. Choreographer fires
 *           on every vsync; if this is below the display refresh rate, the
 *           main thread cannot keep up even without Compose.
 *   dt   — actual frame delta in ms. Consistent 50ms = genuinely slow device.
 *           Spikes suggest bursty work (GC, tile loading callbacks, etc.).
 *   zoom — current map zoom level.
 *
 * Compare these numbers with the OSD in MainActivity:
 *   fps here ≈ fps in MainActivity → Compose is NOT the bottleneck; the
 *                                    device or MapLibre is.
 *   fps here >> fps in MainActivity → Compose overhead is the problem.
 *
 * Launch: adb shell am start -n de.codevoid.aWayToGo/.DiagnosticActivity
 */
class DiagnosticActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var osdView: TextView

    // Choreographer frame counter — runs entirely without Compose
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
            if (windowNs >= 1_000_000_000L) {          // 1 second elapsed
                lastFps = (frameCount * 1_000_000_000L / windowNs).toInt()
                frameCount = 0
                windowStartNs = frameTimeNanos
            }

            osdView.text = "fps  $lastFps  dt:${lastDtMs}ms"

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json" +
                       "?key=${BuildConfig.MAPTILER_KEY}"

        // Root layout — no Compose, no View binding, just two raw views
        val root = FrameLayout(this)

        // MapView fills the screen
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // OSD overlay — plain TextView, no Compose recomposition
        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            text = "fps  --  dt:--ms"
        }
        val osdParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) }
        root.addView(osdView, osdParams)

        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
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
