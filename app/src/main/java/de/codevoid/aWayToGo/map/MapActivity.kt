package de.codevoid.aWayToGo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import de.codevoid.aWayToGo.BuildConfig
import de.codevoid.aWayToGo.R
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

// ── Drag line style layer / source IDs ───────────────────────────────────────
// Two LineLayer instances (casing behind, fill in front) produce the outlined
// look. A SymbolLayer with line-center placement overlays the distance label.
private const val SOURCE_DRAG_LINE        = "drag-line"
private const val LAYER_DRAG_LINE_CASING  = "drag-line-casing"
private const val LAYER_DRAG_LINE_FILL    = "drag-line-fill"
private const val LAYER_DRAG_LINE_LABEL   = "drag-line-label"

// Web-Mercator tile-resolution constants used for bearing-aware pixel→LatLng.
// MERCATOR_CIRCUMFERENCE: Earth equatorial circumference mapped to a 256-px
//   tile at zoom 0 (standard Web-Mercator definition).
// METERS_PER_DEGREE_LAT: approximate metres per degree of latitude (near equator).
private const val MERCATOR_CIRCUMFERENCE  = 156543.03392
private const val METERS_PER_DEGREE_LAT  = 111320.0

// Desired pan speed in screen pixels per second.
private const val PAN_SPEED_PX_PER_SEC = 120f

// Analog joystick sensitivity multiplier (0.0–1.0).
// 1.0 = full PAN_SPEED_PX_PER_SEC at maximum deflection.
// 0.5 = half speed — easier to make small adjustments.
private const val JOY_SENSITIVITY = 0.5f

// Desired zoom speed in MapLibre zoom levels per second.
private const val ZOOM_SPEED_PER_SEC = 1.5f

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
    private lateinit var versionCardView: TextView

    // Progress overlay shown during APK download (null when not downloading).
    private var downloadOverlay: View? = null
    private var downloadProgressBar: ProgressBar? = null

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

    // Analog joystick state: normalised [-1, 1] axes from the last JoyInput event.
    // (0, 0) = joystick released.  Written on the main thread by handleRemoteEvent,
    // read on the main thread by the Choreographer callback — no synchronisation needed.
    private var joyDx = 0f
    private var joyDy = 0f

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

            // ── Pan + Zoom ─────────────────────────────────────────────────────
            val currentMap = map
            var panSpeed = 0f
            if (currentMap != null && (panStartNs.isNotEmpty() || joyDx != 0f || joyDy != 0f)) {
                // Accumulate deltas for all held keys.  Pan and zoom are merged into
                // a single animateCamera call so they never compete with each other.
                var totalDx   = 0f
                var totalDy   = 0f
                var totalZoom = 0f

                for ((key, startNs) in panStartNs) {
                    val elapsedMs = (frameTimeNanos - startNs) / 1_000_000L
                    // Linear ramp: 50 % speed at t=0, 100 % at t=2 s.
                    val ramp = (elapsedMs / 2000f).coerceAtMost(1f)
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

                // Analog joystick — no ramp, magnitude IS the speed fraction.
                // JOY_SENSITIVITY scales down the effective pan speed for finer control.
                if (joyDx != 0f || joyDy != 0f) {
                    val px = PAN_SPEED_PX_PER_SEC * JOY_SENSITIVITY * PAN_LOOK_AHEAD_MS / 1000f
                    totalDx +=  joyDx * px
                    totalDy += -joyDy * px   // joy Y+ = screen-up = subtract from dy
                    val joySpeed = maxOf(kotlin.math.abs(joyDx), kotlin.math.abs(joyDy)) *
                        PAN_SPEED_PX_PER_SEC * JOY_SENSITIVITY
                    if (joySpeed > panSpeed) panSpeed = joySpeed
                }

                if (totalDx != 0f || totalDy != 0f || totalZoom != 0f) {
                    val pos    = currentMap.cameraPosition
                    val target = pos.target

                    // Bearing-aware pan: rotate screen-space vector by map bearing so
                    // pushing UP always scrolls map content down regardless of rotation.
                    val newLatLng = if (target != null && (totalDx != 0f || totalDy != 0f)) {
                        val bearingRad  = Math.toRadians(pos.bearing)
                        val cosB        = cos(bearingRad).toFloat()
                        val sinB        = sin(bearingRad).toFloat()
                        val rotatedDx   = totalDx * cosB - totalDy * sinB
                        val rotatedDy   = totalDx * sinB + totalDy * cosB
                        val latRad      = Math.toRadians(target.latitude)
                        val metersPerPx = MERCATOR_CIRCUMFERENCE * cos(latRad) / Math.pow(2.0, pos.zoom)
                        val latDelta    = -(rotatedDy * metersPerPx) / METERS_PER_DEGREE_LAT
                        val lngDelta    =  (rotatedDx * metersPerPx) / (METERS_PER_DEGREE_LAT * cos(latRad))
                        LatLng(target.latitude + latDelta, target.longitude + lngDelta)
                    } else target

                    currentMap.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(newLatLng)
                                .zoom(pos.zoom + totalZoom)
                                .bearing(pos.bearing)
                                .tilt(pos.tilt)
                                .build(),
                        ),
                        PAN_LOOK_AHEAD_MS,
                    )
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

        // Crosshair — gradient arms fading to transparent + circular reticle at centre.
        // Only visible in panning mode.
        crosshairView = CrosshairView(this).apply { visibility = View.GONE }
        root.addView(
            crosshairView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Version card — bottom-right corner.  Shows the installed build's short
        // commit hash.  Tap to check GitHub for a newer pre-release and install it.
        versionCardView = TextView(this).apply {
            text = BuildConfig.GIT_COMMIT
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(
                (8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onVersionCardTapped() }
        }
        root.addView(
            versionCardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, btnMargin, btnMargin) },
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
                // Enable ease-out after touch gestures (fling/pan inertia, pinch-zoom
                // deceleration, rotate deceleration).  These are touch-only — programmatic
                // animateCamera calls (remote control) are not affected.
                setAllVelocityAnimationsEnabled(true)
                // Default fling duration is 150ms (ANIMATION_DURATION_FLING_BASE) — too abrupt.
                // 500ms gives a natural coast-to-stop for pan, zoom, and rotate.
                flingAnimationBaseTime = 500L
                // Default velocity threshold is 1000 (px/s) — too high, so only fast swipes
                // trigger the ease-out.  300 lets moderate-speed swipes also coast to a stop.
                flingThreshold = 300
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

            is RemoteEvent.JoyInput -> {
                joyDx = event.dx
                joyDy = event.dy
                // Non-neutral joystick movement enters panning mode just like a D-pad press.
                if (event.dx != 0f || event.dy != 0f) enterPanningMode()
            }

            is RemoteEvent.KeyDown -> when (event.key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                    // Moving the D-pad immediately enters panning mode so the
                    // crosshair appears and GPS tracking is suspended.
                    enterPanningMode()
                    panStartNs[event.key] = System.nanoTime()
                }
                RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {
                    // Zoom keys use the same ramp-up / look-ahead mechanism as pan.
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
                RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {}  // handled by KeyDown/KeyUp

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
                    if (isInPanningMode) {
                        // Capture the map position under the crosshair (screen centre)
                        // and draw the drag line from the current GPS fix to that point.
                        val screenCenter = PointF(mapView.width / 2f, mapView.height / 2f)
                        val target = m.projection.fromScreenLocation(screenCenter)
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            setDragLine(LatLng(loc.latitude, loc.longitude), target)
                        }
                    } else {
                        // Outside panning mode: toggle GPS camera tracking.
                        m.locationComponent.cameraMode =
                            if (m.locationComponent.cameraMode == CameraMode.NONE)
                                CameraMode.TRACKING
                            else
                                CameraMode.NONE
                    }

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

    // ── Self-update ───────────────────────────────────────────────────────────

    /**
     * Tap handler for the version card.
     *
     * Checks GitHub for a pre-release newer than the current build, then
     * downloads and opens it with the package installer if one is found.
     * The card text reflects the current state (checking / up to date / error).
     */
    private fun onVersionCardTapped() {
        // Ignore taps while already busy (text is not the commit hash).
        if (versionCardView.text.toString() != BuildConfig.GIT_COMMIT) return
        versionCardView.text = "checking…"

        lifecycleScope.launch {
            val apkUrl = fetchLatestPreReleaseApkUrl()
            if (apkUrl == null) {
                versionCardView.text = "up to date"
                delay(2_000)
                versionCardView.text = BuildConfig.GIT_COMMIT
                return@launch
            }

            showDownloadOverlay()
            try {
                val apk = downloadApk(apkUrl) { pct -> downloadProgressBar?.progress = pct }
                hideDownloadOverlay()
                installApk(apk)
            } catch (_: Exception) {
                hideDownloadOverlay()
                versionCardView.text = "error"
                delay(2_000)
                versionCardView.text = BuildConfig.GIT_COMMIT
            }
        }
    }

    /**
     * Fetches the GitHub releases list and returns the download URL of the APK
     * asset from the most recent pre-release.
     * Returns null if already on that release or on any network/parse error.
     *
     * "Already on that release" is determined by checking whether the APK filename
     * of the latest pre-release contains [BuildConfig.GIT_COMMIT] — the short hash
     * embedded in the filename by the CI pipeline (e.g. "aWayToGo-abc1234.apk").
     * No timestamp comparison needed: if the hash matches we are up to date.
     */
    private suspend fun fetchLatestPreReleaseApkUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/c0dev0id/aWayToGo/releases")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connect()
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            // Find the most recent pre-release that has an APK asset.
            var bestTime = 0L
            var bestUrl: String? = null
            var isCurrentBuild = false

            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                if (!rel.optBoolean("prerelease")) continue
                val published = fmt.parse(rel.optString("published_at"))?.time ?: continue
                if (published <= bestTime) continue

                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name  = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        bestUrl        = asset.optString("browser_download_url")
                        bestTime       = published
                        // If the filename contains our commit hash this release was
                        // built from the same commit — nothing to update.
                        isCurrentBuild = name.contains(BuildConfig.GIT_COMMIT)
                        break
                    }
                }
            }

            if (isCurrentBuild) null else bestUrl
        } catch (_: Exception) { null }
    }

    /** Shows a semi-transparent overlay with a horizontal progress bar. */
    private fun showDownloadOverlay() {
        val d = resources.displayMetrics.density

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max      = 100
            progress = 0
        }
        downloadProgressBar = bar

        val label = TextView(this).apply {
            text = "Downloading update…"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, (8 * d).toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 24, 24, 24))
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            addView(label)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true   // consume touches so nothing behind is tapped
            addView(container, FrameLayout.LayoutParams(
                (280 * d).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        downloadOverlay = overlay

        (window.decorView as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun hideDownloadOverlay() {
        downloadOverlay?.let { (window.decorView as? ViewGroup)?.removeView(it) }
        downloadOverlay    = null
        downloadProgressBar = null
    }

    /**
     * Downloads the APK at [url] to externalCacheDir/update.apk.
     * Calls [onProgress] (0–100) on the main thread as data arrives.
     * Must be called from a coroutine; suspends on IO.
     */
    private suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connect()
            val total = conn.contentLengthLong
            val dest  = File(externalCacheDir, "update.apk")
            var lastPct = -1

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(65_536)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            dest
        }

    /** Opens the downloaded APK with the system package installer. */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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

    /**
     * Draw (or update) the navigation drag line from [from] to [to].
     *
     * Visual spec: 4.5dp red fill with 1.5dp dark-red casing on each side
     * (= 7.5dp casing drawn first, 4.5dp fill on top), both at 60% opacity,
     * plus a distance label placed at the midpoint and rotated to follow it.
     *
     * On the first call the GeoJSON source and three style layers are created.
     * On subsequent calls only the source data is updated — the layers stay.
     * The label text uses a comma decimal separator ("4,5km") for readability
     * on the motorcycle-mounted device.
     */
    private fun setDragLine(from: LatLng, to: LatLng) {
        val s = style ?: return

        val distKm = from.distanceTo(to) / 1000.0
        val label  = "${"%.1f".format(distKm).replace('.', ',')}km"

        val geometry = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(from.longitude, from.latitude),
                Point.fromLngLat(to.longitude,   to.latitude),
            )
        )
        val feature    = Feature.fromGeometry(geometry)
        feature.addStringProperty("label", label)
        val collection = FeatureCollection.fromFeatures(listOf(feature))

        // If the source already exists, just push new data — layers stay in place.
        val existing = s.getSourceAs<GeoJsonSource>(SOURCE_DRAG_LINE)
        if (existing != null) {
            existing.setGeoJson(collection)
            return
        }

        s.addSource(GeoJsonSource(SOURCE_DRAG_LINE, collection))

        // Casing — dark-red, wider than the fill so 1.5dp sticks out on each side.
        // Original: 10dp.  Reduced by 25%: 7.5dp.
        s.addLayer(
            LineLayer(LAYER_DRAG_LINE_CASING, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.lineColor("#8B0000"),
                    PropertyFactory.lineWidth(7.5f),
                    PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Fill — red, drawn on top of the casing.
        // Original: 6dp.  Reduced by 25%: 4.5dp.
        s.addLayer(
            LineLayer(LAYER_DRAG_LINE_FILL, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.lineColor("#FF0000"),
                    PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Distance label at the midpoint, rotated to follow the line.
        s.addLayer(
            SymbolLayer(LAYER_DRAG_LINE_LABEL, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                    PropertyFactory.textSize(18f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#8B0000"),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textAllowOverlap(true),
                )
            }
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

