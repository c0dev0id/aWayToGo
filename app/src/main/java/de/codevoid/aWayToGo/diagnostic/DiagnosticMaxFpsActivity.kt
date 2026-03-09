package de.codevoid.aWayToGo.diagnostic

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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
 * Diagnostic activity: configurable [MapView.setMaximumFps] + [MapLibreMap.setPrefetchZoomDelta].
 *
 * Values are supplied as Intent integer extras so you can sweep the parameter
 * space without recompiling:
 *
 *   Extra key       Default   Meaning
 *   "maxFps"        30        GL render rate cap (0 = unlimited)
 *   "prefetchDelta" 4         Zoom levels to prefetch below current zoom
 *
 * Launch via Makefile (override defaults with Make variables):
 *
 *   make diag-maxfps                         → maxFps=30  prefetchDelta=4
 *   make diag-maxfps MAXFPS=45               → maxFps=45  prefetchDelta=4
 *   make diag-maxfps MAXFPS=0  PREFETCH=2    → unlimited fps, delta 2
 *
 * Or directly via adb:
 *   adb shell am start \
 *     -n de.codevoid.aWayToGo/.diagnostic.DiagnosticMaxFpsActivity \
 *     --ei maxFps 45 --ei prefetchDelta 6
 */
class DiagnosticMaxFpsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MAX_FPS       = "maxFps"
        const val EXTRA_PREFETCH      = "prefetchDelta"
        const val DEFAULT_MAX_FPS     = 30
        const val DEFAULT_PREFETCH    = 4
    }

    private lateinit var mapView: MapView
    private lateinit var osdView: TextView

    private var maxFps      = DEFAULT_MAX_FPS
    private var prefetch    = DEFAULT_PREFETCH

    // GL-frame counters — updated from MapLibre's render callback, not Choreographer.
    // This measures actual GPU frame submissions, so setMaximumFps is reflected here.
    @Volatile private var glFrameCount    = 0
    @Volatile private var glWindowStartMs = 0L
    @Volatile private var glLastFps       = 0
    @Volatile private var glLastDtMs      = 0L

    // Choreographer only drives OSD refresh — it no longer counts frames itself.
    private val osdRefreshCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val fpsLabel = if (maxFps == 0) "unlimited" else maxFps.toString()
            osdView.text =
                "MaxFPS:$fpsLabel Prefetch:$prefetch\ngl-fps $glLastFps  dt:${glLastDtMs}ms"
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // onDidFinishRenderingFrame(fully, frameEncodingTime, frameRenderingTime)
    // frameRenderingTime is the GPU render duration in ms for this frame.
    // We use it directly for dt and count frames for FPS over a 1-second window.
    private val glFrameListener =
        MapView.OnDidFinishRenderingFrameListener { _, _, frameRenderingTime ->
            glLastDtMs = frameRenderingTime.toLong()
            glFrameCount++
            val nowMs = SystemClock.elapsedRealtime()
            if (glWindowStartMs == 0L) glWindowStartMs = nowMs
            val windowMs = nowMs - glWindowStartMs
            if (windowMs >= 1_000L) {
                glLastFps       = (glFrameCount * 1_000L / windowMs).toInt()
                glFrameCount    = 0
                glWindowStartMs = nowMs
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        maxFps   = intent.getIntExtra(EXTRA_MAX_FPS,    DEFAULT_MAX_FPS)
        prefetch = intent.getIntExtra(EXTRA_PREFETCH,   DEFAULT_PREFETCH)

        MapLibre.getInstance(this)

        val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json" +
                       "?key=${BuildConfig.MAPTILER_KEY}"

        val root = FrameLayout(this)

        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val fpsLabel = if (maxFps == 0) "unlimited" else maxFps.toString()
        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            text = "MaxFPS:$fpsLabel Prefetch:$prefetch\nfps  --  dt:--ms"
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) })

        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            // 0 means "no cap" to MapView.setMaximumFps.
            mapView.setMaximumFps(maxFps)
            map.setPrefetchZoomDelta(prefetch)
            mapView.addOnDidFinishRenderingFrameListener(glFrameListener)

            map.setStyle(styleUrl) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), 12.0)
                )
            }
        }
    }

    override fun onStart()   { super.onStart();   mapView.onStart()  }
    override fun onResume()  {
        super.onResume()
        mapView.onResume()
        Choreographer.getInstance().postFrameCallback(osdRefreshCallback)
    }
    override fun onPause()   {
        Choreographer.getInstance().removeFrameCallback(osdRefreshCallback)
        mapView.onPause()
        super.onPause()
    }
    override fun onStop()    { super.onStop();    mapView.onStop()   }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
