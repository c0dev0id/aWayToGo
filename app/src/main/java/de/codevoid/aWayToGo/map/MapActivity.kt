package de.codevoid.aWayToGo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout  // kept for LayoutParams only
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import de.codevoid.aWayToGo.BuildConfig
import de.codevoid.aWayToGo.R
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos

// Desired pan speed in screen pixels per second.
private const val PAN_SPEED_PX_PER_SEC = 120f

// How far ahead (ms) each animateCamera call targets.
// The GL thread interpolates this segment smoothly at its own refresh rate.
// At 59 fps (16ms frames) 32ms gives the GL thread 2 frames of animation
// to interpolate between main-thread updates — enough for smooth movement
// without adding noticeable look-ahead lag.
private const val PAN_LOOK_AHEAD_MS = 32

private const val TILT_3D = 60.0

private const val LOCATION_PERMISSION_REQUEST = 1

// Distance threshold (metres) below which flyToLocation animates directly.
// Above this, it zooms out to a context level first, then zooms back in.
private const val FLYTO_THRESHOLD_M = 10_000.0

/**
 * Main map screen — zero Compose overhead.
 *
 * Architecture rationale: on the target device (Garmin Zumo XT2 equivalent),
 * Compose added ~34ms of main-thread work per frame, capping the UI at 14 fps
 * even though MapLibre's GL thread ran at 59 fps. This activity replaces the
 * Compose-based MapScreen with:
 *
 *   - A raw MapView (SurfaceView mode) that lets the GL thread run freely.
 *   - A single Choreographer.FrameCallback that drives panning and OSD at
 *     vsync rate with no Compose recomposition overhead.
 *   - Plain Android Views for the OSD overlay.
 *
 * All map features (location, remote control, pan ramp, tilt toggle, etc.)
 * are preserved from the Compose version.
 */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var remoteControl: RemoteControlManager
    private lateinit var osdView: TextView
    private lateinit var myLocationButton: ImageView
    private lateinit var crosshairView: View

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hasLocationPermission = false

    // True while the user is panning freely (crosshair visible, tracking disabled).
    // Entered by D-pad or touch gesture; exited by BACK, CONFIRM, or the
    // my-location button → re-enables GPS tracking and flies back to position.
    private var isInPanningMode = false

    // Active pan directions → vsync timestamp (ns) when the key was first pressed.
    // Used to compute the speed ramp-up in the Choreographer loop.
    private val panStartNs = mutableMapOf<RemoteKey, Long>()

    // ── OSD state (tracked between Choreographer frames) ──────────────────────
    private var osdLastFrameNs = 0L
    private var osdFrameCount  = 0
    private var osdWindowNs    = 0L
    private var osdLastFps     = 0
    private var osdLastDtMs    = 0L

    // ── Choreographer loop ────────────────────────────────────────────────────
    //
    // A single callback drives both panning and OSD updates.
    // This runs on the main thread at vsync rate with no Compose overhead.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtNs = if (osdLastFrameNs != 0L) frameTimeNanos - osdLastFrameNs else 16_000_000L
            osdLastFrameNs = frameTimeNanos

            // ── Pan ────────────────────────────────────────────────────────────
            val currentMap = map
            var panSpeed = 0f
            if (currentMap != null && panStartNs.isNotEmpty()) {
                // Accumulate the combined delta for all active directions into
                // a single animateCamera call to avoid competing animations.
                var totalDx = 0f
                var totalDy = 0f

                for ((key, startNs) in panStartNs) {
                    val elapsedMs = (frameTimeNanos - startNs) / 1_000_000L
                    // Linear ramp: 50 % speed at t=0, 100 % at t=2 s.
                    val ramp  = (elapsedMs / 2000f).coerceAtMost(1f)
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

                if (totalDx != 0f || totalDy != 0f) {
                    currentMap.panByAnimated(totalDx, totalDy, PAN_LOOK_AHEAD_MS)
                }
            }

            // ── OSD (DEBUG builds only) ────────────────────────────────────────
            if (BuildConfig.DEBUG) {
                osdLastDtMs = dtNs / 1_000_000L

                osdFrameCount++
                if (osdWindowNs == 0L) osdWindowNs = frameTimeNanos
                val elapsed = frameTimeNanos - osdWindowNs
                if (elapsed >= 1_000_000_000L) {
                    osdLastFps    = (osdFrameCount * 1_000_000_000L / elapsed).toInt()
                    osdFrameCount = 0
                    osdWindowNs   = frameTimeNanos
                }

                val zoom    = map?.cameraPosition?.zoom ?: 0.0
                val panLine = if (panSpeed > 0f)
                    "\npan  ${"%.0f".format(panSpeed)} px/s" else ""
                osdView.text =
                    "fps  $osdLastFps  dt:${osdLastDtMs}ms\nzoom ${"%.1f".format(zoom)}$panLine"
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on — essential for a navigation device.
        // keepScreenOn is the modern replacement for FLAG_KEEP_SCREEN_ON /
        // SCREEN_BRIGHT_WAKE_LOCK.  Setting it on the root view is sufficient;
        // Android propagates the flag to the window automatically and clears it
        // when the view detaches.
        window.decorView.keepScreenOn = true

        // Full-screen immersive: hide status bar and navigation bar.
        // Must be called before setContentView.
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: bars appear briefly on edge
        // swipe then hide again — useful during development, unobtrusive in use.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // MapLibre must be initialized first — HttpRequestUtil.setOkHttpClient()
        // (called inside TileCache.init) asserts MapLibre.getInstance() has run.
        // TileCache.init() must still be called before any MapView is created or
        // any style is loaded, so the custom OkHttp client is in place before the
        // first network request.  The getInstance() call itself makes no HTTP requests.
        MapLibre.getInstance(this)
        TileCache.init(this)
        remoteControl = RemoteControlManager(this)

        val styleUrl =
            "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"

        // Root layout — TwoFingerLockLayout disables map scrolling while a
        // two-finger (zoom/rotate) gesture is in progress.
        val root = TwoFingerLockLayout(this)

        // MapView fills the screen.
        // SurfaceView mode (the default — do NOT pass textureMode): gives MapLibre its own
        // hardware layer, so the GL thread runs at the display's native refresh rate
        // independently of the main thread.
        //
        // pixelRatio=3.0: benchmark-derived optimum. Higher pixelRatio causes MapLibre to
        // satisfy tile quality from a lower zoom tier → fewer, larger tiles per viewport →
        // less tile-fetch congestion during pan → higher and more stable gl_fps.
        // Tested values 1.0–4.0 at zoom 14/16; 3.0 gave best avg+min gl_fps on this device.
        val mapOptions = MapLibreMapOptions.createFromAttributes(this)
            .pixelRatio(3.0f)
        mapView = MapView(this, mapOptions)
        root.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // OSD overlay — plain TextView, no Compose recomposition.
        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        }
        val osdParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, 48, 16, 0) }
        root.addView(osdView, osdParams)

        // My-location button — top-left, circular dark background with ripple.
        val density = resources.displayMetrics.density
        val btnSize = (48 * density).toInt()
        val btnPad  = (12 * density).toInt()
        val btnMargin = (16 * density).toInt()

        myLocationButton = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MapActivity, R.drawable.ic_my_location))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(180, 0, 0, 0))
                },
                GradientDrawable().apply {          // ripple mask — clips to circle
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
            )
            setPadding(btnPad, btnPad, btnPad, btnPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { exitPanningMode() }
        }
        root.addView(
            myLocationButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.START)
                .apply { setMargins(btnMargin, btnMargin, 0, 0) },
        )

        // Crosshair — two full-screen red lines (horizontal + vertical) centred on screen.
        // Spans edge-to-edge so it remains readable at a glance from handlebar distance.
        // Only visible in panning mode.
        val crosshairThickness = (6 * density).toInt()
        val crosshairContainer = FrameLayout(this).apply { visibility = View.GONE }
        crosshairContainer.addView(
            View(this).apply { setBackgroundColor(Color.RED) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, crosshairThickness, Gravity.CENTER_VERTICAL,
            ),
        )
        crosshairContainer.addView(
            View(this).apply { setBackgroundColor(Color.RED) },
            FrameLayout.LayoutParams(
                crosshairThickness, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL,
            ),
        )
        crosshairView = crosshairContainer
        root.addView(
            crosshairContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(root)

        // MapView lifecycle must be driven manually (no Compose lifecycle observer here).
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            root.attachMap(m)
            // maxFps=60: matches standard display refresh rate; allows the render scheduler
            // more recovery slots than 30fps during tile-load stutter without the unnecessary
            // GPU load of 120fps. Benchmark: gl_fps avg=28 min=18 vs avg=18 min=3 at 30fps.
            mapView.setMaximumFps(60)
            // prefetchDelta=2: prefetching 2 zoom levels out instead of 4 reduces concurrent
            // tile requests competing with the current viewport, improving gl_fps during pan.
            m.setPrefetchZoomDelta(2)
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled   = true
                isCompassEnabled        = true
            }
            // Close the tile gate on ANY camera movement — touch, D-pad, or
            // programmatic (flyToLocation).  New network tile fetches are
            // queued until the camera is idle so the GL thread is not
            // interrupted by upload work mid-animation.
            // Touch gestures additionally trigger visual panning mode.
            m.addOnCameraMoveStartedListener { reason ->
                TileCache.gate.pause()
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    enterPanningMode()
                }
            }
            // Open the gate when the camera stops.  MapLibre will immediately
            // start filling in any missing tiles; the map is static, so
            // uploads do not drop visible frames.
            m.addOnCameraIdleListener {
                TileCache.gate.resume()
            }
            m.setStyle(styleUrl) { s ->
                map   = m
                style = s
                enableLocationIfReady()
            }
        }

        // Check / request location permission.
        hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOCATION_PERMISSION_REQUEST,
            )
        }

        // Collect remote control events on the main thread via lifecycleScope.
        lifecycleScope.launch {
            remoteControl.events.collect { event -> handleRemoteEvent(event) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            hasLocationPermission = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            enableLocationIfReady()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationIfReady() {
        val m = map   ?: return
        val s = style ?: return
        if (!hasLocationPermission) return

        m.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(this@MapActivity, s).build(),
            )
            isLocationComponentEnabled = true
            cameraMode  = CameraMode.TRACKING
            renderMode  = RenderMode.COMPASS
        }

        m.locationComponent.lastKnownLocation?.let { loc ->
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    14.0,
                ),
            )
        }
    }

    private fun handleRemoteEvent(event: RemoteEvent) {
        val m = map ?: return
        when (event) {

            is RemoteEvent.KeyDown -> when (event.key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                    // Moving the D-pad immediately enters panning mode so the
                    // crosshair appears and GPS tracking is suspended.
                    enterPanningMode()
                    // Record vsync-clock start time; the Choreographer loop computes ramp from here.
                    panStartNs[event.key] = System.nanoTime()
                }
                else -> {}
            }

            is RemoteEvent.KeyUp -> {
                panStartNs.remove(event.key)
                // Cancel the in-flight animation so the map does not coast past the release point.
                // The next Choreographer frame will issue a fresh animation for any remaining
                // active directions.
                m.cancelTransitions()
            }

            is RemoteEvent.ShortPress -> when (event.key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {}

                RemoteKey.ZOOM_IN  -> m.animateCamera(CameraUpdateFactory.zoomIn())
                RemoteKey.ZOOM_OUT -> m.animateCamera(CameraUpdateFactory.zoomOut())

                RemoteKey.CONFIRM ->
                    // In panning mode: confirm exits panning and re-locks on GPS.
                    // Outside panning mode: fly to current location directly.
                    if (isInPanningMode) {
                        exitPanningMode()
                    } else {
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            flyToLocation(m, LatLng(loc.latitude, loc.longitude))
                        }
                    }

                RemoteKey.BACK ->
                    // In panning mode: exit panning → re-lock on GPS.
                    // Outside panning mode: reset map bearing to north.
                    if (isInPanningMode) {
                        exitPanningMode()
                    } else {
                        m.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(m.cameraPosition.target)
                                    .zoom(m.cameraPosition.zoom)
                                    .tilt(m.cameraPosition.tilt)
                                    .bearing(0.0)
                                    .build(),
                            ),
                        )
                    }
            }

            is RemoteEvent.LongPress -> when (event.key) {
                RemoteKey.CONFIRM ->
                    m.locationComponent.cameraMode =
                        if (m.locationComponent.cameraMode == CameraMode.NONE)
                            CameraMode.TRACKING
                        else
                            CameraMode.NONE

                RemoteKey.BACK -> {
                    val currentTilt = m.cameraPosition.tilt
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(m.cameraPosition.target)
                                .zoom(m.cameraPosition.zoom)
                                .bearing(m.cameraPosition.bearing)
                                .tilt(if (currentTilt > 0.0) 0.0 else TILT_3D)
                                .build(),
                        ),
                    )
                }

                else -> {}
            }
        }
    }

    /**
     * Switch to panning mode: show the crosshair, disable GPS camera tracking.
     *
     * Idempotent — safe to call when already in panning mode (e.g. while the
     * D-pad is held and multiple KeyDown events arrive).
     */
    private fun enterPanningMode() {
        if (isInPanningMode) return
        isInPanningMode = true
        map?.locationComponent?.cameraMode = CameraMode.NONE
        crosshairView.visibility = View.VISIBLE
    }

    /**
     * Leave panning mode: hide the crosshair, re-enable GPS tracking, and
     * animate back to the user's current location.
     *
     * Also used by the my-location button so touch-only users can exit panning.
     * Calling when already outside panning mode is harmless — the crosshair will
     * stay hidden and the camera will simply fly to the current location.
     */
    private fun exitPanningMode() {
        isInPanningMode = false
        crosshairView.visibility = View.GONE
        val m   = map ?: return
        val loc = m.locationComponent.lastKnownLocation ?: return
        m.locationComponent.cameraMode = CameraMode.TRACKING
        flyToLocation(m, LatLng(loc.latitude, loc.longitude))
    }

    /**
     * Animate the camera to [target] at [zoom].
     *
     * If the target is within FLYTO_THRESHOLD_M the camera eases directly.
     * If farther away it first animates to a context zoom that shows enough
     * geography to orient the user, then eases in to the target — the same
     * "zoom out → pan → zoom in" pattern used by most navigation apps.
     */
    private fun flyToLocation(m: MapLibreMap, target: LatLng, zoom: Double = 14.0) {
        val from     = m.cameraPosition.target ?: return
        val distance = from.distanceTo(target)

        if (distance < FLYTO_THRESHOLD_M) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom), 600)
            return
        }

        // Choose a context zoom level that gives a sense of how far away we are.
        val contextZoom = when {
            distance > 5_000_000 -> 2.0
            distance > 1_000_000 -> 4.0
            distance > 200_000   -> 6.0
            distance > 50_000    -> 8.0
            else                 -> 10.0
        }

        // Phase 1: zoom out and pan to target simultaneously.
        m.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target, contextZoom),
            600,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    // Phase 2: ease into the final zoom level.
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom), 500)
                }
                override fun onCancel() {}
            },
        )
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        remoteControl.register()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        remoteControl.unregister()
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

/**
 * Animate the map centre by [xPixels]/[yPixels] screen pixels over [durationMs].
 *
 * Unlike moveCamera (instant), animateCamera hands the interpolation to the
 * GL thread, which runs at the display's native refresh rate independently of
 * the main thread. On a slow main thread (e.g. 20 fps), this means perceived
 * panning remains smooth even though camera targets only arrive at 20 fps —
 * the GL renderer fills in the in-between frames.
 *
 * Geographic delta is computed via Web Mercator resolution arithmetic to avoid
 * calling projection.toScreenLocation / fromScreenLocation each frame.
 */
private fun MapLibreMap.panByAnimated(xPixels: Float, yPixels: Float, durationMs: Int) {
    val pos    = cameraPosition
    val target = pos.target ?: return
    val latRad     = Math.toRadians(target.latitude)
    val metersPerPx = 156543.03392 * cos(latRad) / Math.pow(2.0, pos.zoom)
    val latDelta   = -(yPixels * metersPerPx) / 111320.0
    val lngDelta   =  (xPixels * metersPerPx) / (111320.0 * cos(latRad))
    animateCamera(
        CameraUpdateFactory.newLatLng(
            LatLng(target.latitude + latDelta, target.longitude + lngDelta),
        ),
        durationMs,
    )
}
