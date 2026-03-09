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
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.cos

/**
 * Diagnostic activity: configurable [MapView.setMaximumFps] + [MapLibreMap.setPrefetchZoomDelta].
 *
 * Values are supplied as Intent extras so you can sweep the parameter
 * space without recompiling:
 *
 *   Extra key       Type     Default   Meaning
 *   "maxFps"        Int      30        GL render rate cap (0 = unlimited)
 *   "prefetchDelta" Int      4         Zoom levels to prefetch below current zoom
 *   "zoom"          Double   12.0      Initial camera zoom level
 *   "bench"         Boolean  false     10-second benchmark mode (see below)
 *
 * Benchmark mode (bench=true):
 *   - Map pans right continuously for 10 seconds, starting immediately.
 *   - Records total rendered frames, average fps, and time-to-first-full-render.
 *   - OSD shows live progress; freezes on a summary when the run completes.
 *   - Designed for sweeping MAXFPS × ZOOM × PREFETCH combinations without
 *     touching the code.
 *
 * Launch via Makefile:
 *
 *   make diag-maxfps                              → normal mode, defaults
 *   make diag-bench                               → bench mode, defaults
 *   make diag-bench MAXFPS=60 ZOOM=14.0           → bench at 60 fps, closer zoom
 *   make diag-maxfps MAXFPS=0  PREFETCH=2         → unlimited fps, manual inspect
 *
 * Or directly via adb:
 *   adb shell am start -S \
 *     -n de.codevoid.aWayToGo/.diagnostic.DiagnosticMaxFpsActivity \
 *     --ei maxFps 45 --ei prefetchDelta 6 --ed zoom 14.0 --ez bench true
 */
class DiagnosticMaxFpsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MAX_FPS       = "maxFps"
        const val EXTRA_PREFETCH      = "prefetchDelta"
        const val EXTRA_ZOOM          = "zoom"
        const val EXTRA_BENCH         = "bench"
        const val DEFAULT_MAX_FPS     = 30
        const val DEFAULT_PREFETCH    = 4
        const val DEFAULT_ZOOM        = 12.0
        const val DEFAULT_BENCH       = false

        /** Benchmark run duration. */
        const val BENCH_DURATION_MS   = 10_000L

        /** Horizontal pan per Choreographer tick (pixels). */
        const val BENCH_SCROLL_PX     = 5f
    }

    private lateinit var mapView: MapView
    private lateinit var osdView: TextView

    private var maxFps      = DEFAULT_MAX_FPS
    private var prefetch    = DEFAULT_PREFETCH
    private var zoom        = DEFAULT_ZOOM
    private var bench       = DEFAULT_BENCH

    // GL-frame counters — updated from MapLibre's render callback, not Choreographer.
    // This measures actual GPU frame submissions, so setMaximumFps is reflected here.
    @Volatile private var glFrameCount    = 0
    @Volatile private var glWindowStartMs = 0L
    @Volatile private var glLastFps       = 0
    @Volatile private var glLastDtMs      = 0L

    // Benchmark state
    private              var benchStartMs      = 0L
    @Volatile private    var benchTotalFrames  = 0
    @Volatile private    var benchLoadTimeMs   = -1L   // -1 = not yet first-full-render
    @Volatile private    var benchDone         = false
    private              var glMap: MapLibreMap? = null

    // ── Choreographer: OSD refresh ───────────────────────────────────────────

    private val osdRefreshCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!benchDone) {
                val fpsLabel = if (maxFps == 0) "unlimited" else maxFps.toString()
                val zoomFmt  = "%.1f".format(zoom)
                osdView.text = if (bench) {
                    val elapsed = (SystemClock.elapsedRealtime() - benchStartMs) / 1000.0
                    "[BENCH ${"%.1f".format(elapsed)}s] MaxFPS:$fpsLabel Prefetch:$prefetch Zoom:$zoomFmt\n" +
                    "gl-fps $glLastFps  dt:${glLastDtMs}ms  frames:$benchTotalFrames"
                } else {
                    "MaxFPS:$fpsLabel Prefetch:$prefetch Zoom:$zoomFmt\ngl-fps $glLastFps  dt:${glLastDtMs}ms"
                }
            }
            // Always re-post so the OSD stays live (bench results remain frozen when benchDone).
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── Choreographer: benchmark pan ─────────────────────────────────────────

    private val benchScrollCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (benchDone) return
            val elapsedMs = SystemClock.elapsedRealtime() - benchStartMs
            if (elapsedMs >= BENCH_DURATION_MS) {
                finishBench()
                return
            }
            glMap?.panInstant(BENCH_SCROLL_PX, 0f)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── MapLibre: GL frame listener ───────────────────────────────────────────

    // onDidFinishRenderingFrame(fully, frameEncodingTime, frameRenderingTime)
    // frameRenderingTime is the GPU render duration in ms for this frame.
    private val glFrameListener =
        MapView.OnDidFinishRenderingFrameListener { _, _, frameRenderingTime ->
            glLastDtMs = frameRenderingTime.toLong()
            glFrameCount++
            if (bench && !benchDone) benchTotalFrames++
            val nowMs = SystemClock.elapsedRealtime()
            if (glWindowStartMs == 0L) glWindowStartMs = nowMs
            val windowMs = nowMs - glWindowStartMs
            if (windowMs >= 1_000L) {
                glLastFps       = (glFrameCount * 1_000L / windowMs).toInt()
                glFrameCount    = 0
                glWindowStartMs = nowMs
            }
        }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        maxFps   = intent.getIntExtra(EXTRA_MAX_FPS,       DEFAULT_MAX_FPS)
        prefetch = intent.getIntExtra(EXTRA_PREFETCH,      DEFAULT_PREFETCH)
        zoom     = intent.getDoubleExtra(EXTRA_ZOOM,       DEFAULT_ZOOM)
        bench    = intent.getBooleanExtra(EXTRA_BENCH,     DEFAULT_BENCH)

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
            text = "MaxFPS:$fpsLabel Prefetch:$prefetch Zoom:${"%.1f".format(zoom)}\nfps  --  dt:--ms"
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) })

        setContentView(root)

        mapView.onCreate(savedInstanceState)

        // Capture start time before getMapAsync so load-time includes GL init.
        if (bench) benchStartMs = SystemClock.elapsedRealtime()

        mapView.getMapAsync { map ->
            glMap = map
            mapView.setMaximumFps(maxFps)
            map.setPrefetchZoomDelta(prefetch)
            mapView.addOnDidFinishRenderingFrameListener(glFrameListener)

            // Position the camera at the target zoom immediately — tile fetching
            // begins before the style JSON even arrives.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), zoom))

            if (bench) {
                // Record the first time all visible tiles are fully rendered.
                mapView.addOnDidFinishRenderingMapListener { fully ->
                    if (fully && benchLoadTimeMs < 0L) {
                        benchLoadTimeMs = SystemClock.elapsedRealtime() - benchStartMs
                    }
                }
                Choreographer.getInstance().postFrameCallback(benchScrollCallback)
            }

            map.setStyle(styleUrl) {
                // In bench mode we don't re-position: the camera has already moved.
                // In normal mode we animate to the start position after style loads.
                if (!bench) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), zoom)
                    )
                }
            }
        }
    }

    /** Called on the main thread (from Choreographer) after BENCH_DURATION_MS. */
    private fun finishBench() {
        benchDone = true
        val avgFps   = (benchTotalFrames * 1_000L / BENCH_DURATION_MS).toInt()
        val loadStr  = if (benchLoadTimeMs >= 0L)
            "${"%.2f".format(benchLoadTimeMs / 1000.0)}s"
        else
            "n/a (still loading)"
        val fpsLabel = if (maxFps == 0) "unlimited" else maxFps.toString()
        osdView.text =
            "── Bench Results ──────────────────\n" +
            "Frames: $benchTotalFrames  Avg: $avgFps fps\n" +
            "Load time: $loadStr\n" +
            "MaxFPS:$fpsLabel  Prefetch:$prefetch  Zoom:${"%.1f".format(zoom)}"
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

/**
 * Instantly pan the map by [xPixels] right and [yPixels] down (no animation).
 * Converts pixel offsets to LatLng deltas using the web-mercator scale factor,
 * matching the approach used in MapActivity.panByAnimated.
 */
private fun MapLibreMap.panInstant(xPixels: Float, yPixels: Float) {
    val pos    = cameraPosition
    val target = pos.target ?: return
    val latRad      = Math.toRadians(target.latitude)
    val metersPerPx = 156543.03392 * cos(latRad) / Math.pow(2.0, pos.zoom)
    val latDelta    = -(yPixels * metersPerPx) / 111320.0
    val lngDelta    =  (xPixels * metersPerPx) / (111320.0 * cos(latRad))
    moveCamera(
        CameraUpdateFactory.newLatLng(
            LatLng(target.latitude + latDelta, target.longitude + lngDelta)
        )
    )
}
