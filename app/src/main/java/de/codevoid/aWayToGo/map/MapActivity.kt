package de.codevoid.aWayToGo.map

import kotlin.math.cos
import kotlin.math.sqrt
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.codevoid.aWayToGo.BuildConfig
import de.codevoid.aWayToGo.R
import de.codevoid.aWayToGo.map.ui.Anim
import de.codevoid.aWayToGo.map.ui.AnimationLatch
import de.codevoid.aWayToGo.map.ui.AnimatorBag
import de.codevoid.aWayToGo.map.ui.SearchOverlayResult
import de.codevoid.aWayToGo.map.ui.buildEditTopBar
import de.codevoid.aWayToGo.map.ui.buildExploreBottomBar
import de.codevoid.aWayToGo.map.ui.MenuPanelResult
import de.codevoid.aWayToGo.map.ui.buildMenuPanel
import de.codevoid.aWayToGo.map.ui.buildNavigateOverlay
import de.codevoid.aWayToGo.map.ui.buildSearchOverlay
import de.codevoid.aWayToGo.map.ui.makePillButton
import de.codevoid.aWayToGo.search.BoundingBox
import de.codevoid.aWayToGo.search.GeocodingRepository
import de.codevoid.aWayToGo.search.RecentSearches
import de.codevoid.aWayToGo.search.SearchResult
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import de.codevoid.aWayToGo.update.AppUpdater
import de.codevoid.aWayToGo.update.ConnectivityChecker
import de.codevoid.aWayToGo.update.DownloadProgress
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// ── Map style URLs ────────────────────────────────────────────────────────────
private const val STYLE_OUTDOOR = "https://api.maptiler.com/maps/outdoor-v2/style.json?key="
private const val STYLE_DARK    = "https://api.maptiler.com/maps/dataviz-dark/style.json?key="
private const val SOURCE_SATELLITE  = "satellite-raster-src"
private const val LAYER_SATELLITE   = "satellite-raster-layer"
private const val SAT_TILE_TEMPLATE = "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key="
private const val SAT_ANIMATE_MS    = 350L

// ── Drag line style layer / source IDs ───────────────────────────────────────
// Two LineLayer instances (casing behind, fill in front) produce the outlined
// look. A SymbolLayer with line-center placement overlays the distance label.
private const val SOURCE_DRAG_LINE        = "drag-line"
private const val LAYER_DRAG_LINE_CASING  = "drag-line-casing"
private const val LAYER_DRAG_LINE_FILL    = "drag-line-fill"
private const val LAYER_DRAG_LINE_LABEL   = "drag-line-label"

private const val SOURCE_SEARCH_PIN = "search-pin-src"
private const val LAYER_SEARCH_PIN  = "search-pin-circle"


private const val LOCATION_PERMISSION_REQUEST = 1

// GPS follow: dead-reckoning look-ahead passed to animateCamera so MapLibre
// interpolates between frames rather than snapping once per GPS fix.
private const val FOLLOW_LOOK_AHEAD_MS = 100

// GPS follow: outlier rejection threshold.  Early & Sykulski (2020) measured
// GPS σ ≈ 8.5 m.  We accept any fix implying less than 300 km/h — generous
// enough to cover GPS noise at low speed while rejecting true outliers.
private const val FOLLOW_MAX_SPEED_MS = 83.3   // 300 km/h in m/s

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
    private lateinit var satelliteToggleBtn: TextView
    private lateinit var darkModeToggleBtn: TextView
    private lateinit var courseUpToggleBtn: TextView
    private lateinit var followToggleBtn: TextView
    private lateinit var myLocationButton: ImageView
    private lateinit var crosshairView: View
    private lateinit var versionCardView: TextView

    // ── Mode UI views ─────────────────────────────────────────────────────────
    // Three horizontal bar views that make up the hamburger icon.
    // Stored separately so each can be rotated at a different speed during open/close.
    // All three share the same rotation pivot: the centre of the 64×64dp button area.
    private lateinit var hamburgerBars: Array<View>
    private lateinit var menuPanel: View
    private lateinit var menuPanelResult: MenuPanelResult
    private lateinit var menuDismissOverlay: View
    private var panelFullHeight = -1               // measured on first open; -1 = not yet measured
    private var settingsMenuHeight = -1            // measured on first enter; -1 = not yet measured
    private var debugMenuHeight = -1               // measured on first enter; -1 = not yet measured
    private var menuAnimator: ValueAnimator? = null
    private var settingsMenuAnimator: ValueAnimator? = null
    private var debugMenuAnimator: ValueAnimator? = null
    private var satelliteAnimator: ValueAnimator? = null
    // Tracks all ValueAnimators so they can be cancelled together in onDestroy().
    private val animBag = AnimatorBag()
    private lateinit var exploreBottomBar: FrameLayout
    private lateinit var navigateOverlay: FrameLayout
    private lateinit var navigateBanner: View
    private lateinit var navigateStopBtn: View
    private lateinit var editTopBar: LinearLayout

    private val viewModel: MapViewModel by viewModels()
    // Last state that was fully rendered; used to diff new vs old in renderUiState().
    private var renderedState: MapUiState? = null

    // Progress overlay shown during APK download (null when not downloading).
    private var downloadOverlay: View? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadLabel: TextView? = null
    private var downloadJob: Job? = null
    private var isDownloadOverlayVisible = false
    private var lastDownloadProgress: DownloadProgress? = null

    // ── Benchmark ─────────────────────────────────────────────────────────────
    private var benchmarkJob: Job? = null
    private var benchmarkOverlay: View? = null
    private var benchmarkStatusLabel: TextView? = null
    @Volatile private var benchmarkGlFrameNanos: MutableList<Long>? = null

    private val appUpdater by lazy { AppUpdater(this) }

    // non-null when an update has been found but not yet downloaded.
    private var pendingUpdateUrl: String? = null
    // drives the version card blink loop while an update is pending.
    private var updateAnimJob: Job? = null
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    // ── Search ────────────────────────────────────────────────────────────────
    private lateinit var searchOverlayResult: SearchOverlayResult
    private val geocoding = GeocodingRepository()
    private lateinit var recentSearches: RecentSearches
    /** LatLng of the most recently selected search result; null if none yet. */
    private var lastSearchTarget: LatLng? = null

    // Overlay used to animate screen rotation — covers the SurfaceView's brief
    // black resize frame, then fades out to reveal the re-laid-out UI.
    private var rotationOverlay: View? = null

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hasLocationPermission = false

    // Owns all D-pad / joystick / zoom-key state and drives per-frame camera updates.
    private val panController = PanController(onEnterPanningMode = { enterPanningMode() })

    // ── Drag line anchor ──────────────────────────────────────────────────────
    // When set, the Choreographer loop continuously updates the drag line so it
    // connects the user's current GPS position with this anchored target.
    private var dragLineAnchor: LatLng? = null

    // ── Follow mode ───────────────────────────────────────────────────────────
    // Dead-reckoning state for smooth GPS follow.
    //
    // GPS errors are t-distributed (σ ≈ 8.5 m, ν ≈ 4.5) with occasional outliers
    // of 100 m+ (Early & Sykulski 2020).  Rather than snapping the camera to each
    // raw fix, we:
    //   1. Reject fixes implying speed > FOLLOW_MAX_SPEED_MS (outlier filter).
    //   2. Estimate velocity from the last two valid fixes.
    //   3. Every Choreographer frame, predict position = last_fix + velocity × elapsed
    //      and animate the camera there with a short look-ahead so MapLibre interpolates
    //      smoothly between frames.
    //
    // NaN signals "no valid fix yet".
    private var followLastLat     = Double.NaN
    private var followLastLon     = Double.NaN
    private var followLastTimeNs  = 0L       // nanoTime of last accepted GPS fix
    private var followVelocityLat = 0.0      // degrees/ns
    private var followVelocityLon = 0.0      // degrees/ns

    // ── OSD state (tracked between Choreographer frames) ──────────────────────
    private var osdLastFrameNs = 0L
    private var osdFrameCount  = 0
    private var osdWindowNs    = 0L
    private var osdLastFps     = 0
    private var osdLastDtMs    = 0L
    @Volatile private var osdGlFps = 0.0
    private var osdRxLast      = 0L
    private var osdTxLast      = 0L
    private var osdRxRate      = 0L  // bytes/s
    private var osdTxRate      = 0L  // bytes/s

    // ── Choreographer loop ────────────────────────────────────────────────────
    //
    // A single callback drives both panning and OSD updates.
    // This runs on the main thread at vsync rate with no Compose overhead.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtNs = if (osdLastFrameNs != 0L) frameTimeNanos - osdLastFrameNs else 16_000_000L
            osdLastFrameNs = frameTimeNanos

            // ── Pan + Zoom ─────────────────────────────────────────────────────
            // PanController owns all D-pad/joystick state and returns the current
            // pan speed for OSD display.
            val panSpeed = panController.onFrame(map, frameTimeNanos, dtNs)

            // ── Drag line live update ────────────────────────────────────────
            // Keep the drag line's "from" end pinned to the current GPS position
            // so it tracks the user as they move toward the anchored target.
            dragLineAnchor?.let { anchor ->
                map?.locationComponent?.lastKnownLocation?.let { loc ->
                    setDragLine(LatLng(loc.latitude, loc.longitude), anchor)
                }
            }

            // ── GPS Follow ───────────────────────────────────────────────────
            // Smooth dead-reckoning follow:
            //   • When a new GPS fix arrives, validate it (outlier rejection) and
            //     update the velocity estimate from the delta to the previous fix.
            //   • Every frame, predict the current position by extrapolating the
            //     last valid fix forward at that velocity, then animate the camera
            //     there with a short look-ahead so MapLibre GL interpolates smoothly
            //     between frames instead of snapping once per GPS update.
            //   • In Course Up mode, the bearing tracks loc.bearing (GPS course
            //     over ground) so the driving direction always points to screen-top.
            //     When Course Up is off (North Up), bearing stays at 0°.
            val uiState = viewModel.uiState.value
            if (uiState.isFollowModeActive) {
                val m   = map   ?: return@doFrame
                val cur = m.cameraPosition ?: return@doFrame
                var gpsBearing = cur.bearing   // updated below if Course Up is on
                m.locationComponent?.lastKnownLocation?.let { loc ->
                    if (loc.latitude != followLastLat || loc.longitude != followLastLon) {
                        // New fix — validate before accepting.
                        val elapsedNs = frameTimeNanos - followLastTimeNs
                        if (!followLastLat.isNaN() && elapsedNs > 0L) {
                            val dLat   = loc.latitude  - followLastLat
                            val dLon   = loc.longitude - followLastLon
                            val dMeters = sqrt(
                                (dLat  * 111_000.0).let { it * it } +
                                (dLon  * 111_000.0 * cos(Math.toRadians(loc.latitude))).let { it * it }
                            )
                            val speedMs = dMeters / (elapsedNs / 1_000_000_000.0)
                            if (speedMs < FOLLOW_MAX_SPEED_MS) {
                                // Valid fix — update velocity and accept.
                                followVelocityLat = dLat / elapsedNs.toDouble()
                                followVelocityLon = dLon / elapsedNs.toDouble()
                                followLastLat    = loc.latitude
                                followLastLon    = loc.longitude
                                followLastTimeNs = frameTimeNanos
                            }
                            // else: outlier — keep predicting from last valid fix.
                        } else {
                            // First fix ever — accept unconditionally, no velocity yet.
                            followLastLat    = loc.latitude
                            followLastLon    = loc.longitude
                            followLastTimeNs = frameTimeNanos
                        }
                    }
                    // Course Up: use GPS course-over-ground when available.
                    if (uiState.isCourseUpEnabled && loc.hasBearing()) {
                        gpsBearing = loc.bearing.toDouble()
                    }
                }
                // Predict and animate every frame (requires at least one valid fix).
                if (!followLastLat.isNaN()) {
                    val elapsedNs = frameTimeNanos - followLastTimeNs
                    val predLat   = followLastLat + followVelocityLat * elapsedNs
                    val predLon   = followLastLon + followVelocityLon * elapsedNs
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(predLat, predLon))
                                .zoom(cur.zoom)
                                .bearing(gpsBearing)
                                .tilt(cur.tilt)
                                .padding(cur.padding)
                                .build(),
                        ),
                        FOLLOW_LOOK_AHEAD_MS,
                    )
                }
            }

            // ── OSD ───────────────────────────────────────────────────────────
            osdLastDtMs = dtNs / 1_000_000L

            osdFrameCount++
            if (osdWindowNs == 0L) osdWindowNs = frameTimeNanos
            val elapsed = frameTimeNanos - osdWindowNs
            if (elapsed >= 1_000_000_000L) {
                osdLastFps    = (osdFrameCount * 1_000_000_000L / elapsed).toInt()
                osdFrameCount = 0
                osdWindowNs   = frameTimeNanos

                // Network rate — sampled once per second
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                if (osdRxLast != 0L) {
                    osdRxRate = rx - osdRxLast
                    osdTxRate = tx - osdTxLast
                }
                osdRxLast = rx
                osdTxLast = tx
            }

            if (osdView.visibility == View.VISIBLE) {
                val zoom   = map?.cameraPosition?.zoom ?: 0.0
                val loc    = map?.locationComponent?.lastKnownLocation
                val hasFix = loc != null
                val acc    = loc?.accuracy ?: 0f
                val panLine = if (panSpeed > 0f) "\npan  ${"%.0f".format(panSpeed)} px/s" else ""
                osdView.text = buildString {
                    append("fps  $osdLastFps  dt:${osdLastDtMs}ms\n")
                    append("zoom ${"%.1f".format(zoom)}  gl_fps ${"%.0f".format(osdGlFps)}\n")
                    append("net  rx:${osdRxRate / 1024}kB/s  tx:${osdTxRate / 1024}kB/s\n")
                    append("gps  fix:${if (hasFix) "Y" else "N"}  acc:${"%.0f".format(acc)}m")
                    if (panLine.isNotEmpty()) append(panLine)
                }
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

        // Suppress the OS rotation animation entirely.
        // ROTATION_ANIMATION_SEAMLESS is ideal but silently falls back to CROSSFADE
        // when the GL SurfaceView cannot be composited seamlessly — and CROSSFADE
        // fades through black, which is worse than no animation.
        // JUMPCUT skips the animation entirely: the window resizes in place with no
        // freeze frame, no fade, and no black flash.  For a full-screen map that
        // handles its own configChanges this is the cleanest result.
        window.attributes = window.attributes.apply {
            rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        }

        // MapLibre must be initialized first — HttpRequestUtil.setOkHttpClient()
        // (called inside TileCache.init) asserts MapLibre.getInstance() has run.
        // TileCache.init() must still be called before any MapView is created or
        // any style is loaded, so the custom OkHttp client is in place before the
        // first network request.  The getInstance() call itself makes no HTTP requests.
        MapLibre.getInstance(this)
        TileCache.init(this)
        remoteControl = RemoteControlManager(this)

        val styleUrl = styleUrl(isDark = false)

        // Root layout — TwoFingerLockLayout disables map scrolling while a
        // two-finger (zoom/rotate) gesture is in progress.
        val root = TwoFingerLockLayout(this)

        // MapView fills the screen.
        // SurfaceView mode (the default — do NOT pass textureMode): gives MapLibre its own
        // hardware layer, so the GL thread runs at the display's native refresh rate
        // independently of the main thread.
        // pixelRatio: do NOT set. MapLibre 13 changed how this interacts with the rendering
        // surface — a non-default value causes the map to render at 1/pixelRatio of the
        // screen size and appear in a corner. The v11 benchmark optimum of 3.0 no longer
        // applies. Re-evaluate tile-fetch tuning via a different mechanism once stable.
        val mapOptions = MapLibreMapOptions.createFromAttributes(this)
        mapView = MapView(this, mapOptions)
        root.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val density = resources.displayMetrics.density

        // Top-right container: OSD debug info (debug builds only) + map toggle buttons.
        val topRightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // OSD overlay — plain TextView, no Compose recomposition.
        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        topRightContainer.addView(
            osdView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END },
        )

        val btnTopMargin = (8 * density).toInt()

        satelliteToggleBtn = makePillButton(this, "SAT") { viewModel.toggleSatellite() }
        topRightContainer.addView(
            satelliteToggleBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; topMargin = btnTopMargin },
        )

        darkModeToggleBtn = makePillButton(this, "DARK") { viewModel.toggleDarkMode() }
        topRightContainer.addView(
            darkModeToggleBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; topMargin = btnTopMargin },
        )

        courseUpToggleBtn = makePillButton(this, "CRS") { viewModel.toggleCourseUp() }
        topRightContainer.addView(
            courseUpToggleBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; topMargin = btnTopMargin },
        )

        followToggleBtn = makePillButton(this, "FOL") { viewModel.toggleFollowMode() }
        topRightContainer.addView(
            followToggleBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; topMargin = btnTopMargin },
        )

        root.addView(
            topRightContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(0, 48, 16, 0) },
        )

        // My-location button — bottom-left, circular dark background with ripple.
        val btnSize = (64 * density).toInt()
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
            setOnClickListener {
                val m   = map ?: return@setOnClickListener
                val loc = m.locationComponent?.lastKnownLocation ?: return@setOnClickListener
                flyToLocation(m, LatLng(loc.latitude, loc.longitude))
            }
        }
        root.addView(
            myLocationButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(btnMargin, 0, 0, btnMargin) },
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

        // ── Mode overlays ─────────────────────────────────────────────────────
        // Only one set is visible at a time; renderUiState() manages visibility.

        exploreBottomBar = buildExploreBottomBar(
            context   = this,
            onRide    = { flyToCurrentLocationThen {}; setMode(AppMode.NAVIGATE) },
            onSearch  = { openSearch() },
            onPlan    = { flyToCurrentLocationThen {}; setMode(AppMode.EDIT) },
        )
        root.addView(
            exploreBottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins(0, 0, 0, btnMargin) },
        )

        buildNavigateOverlay(
            context = this,
            onStop  = { setMode(AppMode.EXPLORE) },
        ).also { result ->
            navigateOverlay  = result.root
            navigateBanner   = result.banner
            navigateStopBtn  = result.stopBtn
        }
        root.addView(
            navigateOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        editTopBar = buildEditTopBar(
            context   = this,
            onDiscard = { setMode(AppMode.EXPLORE) },
            onSave    = { setMode(AppMode.EXPLORE) },
        )
        root.addView(
            editTopBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        // Version card — bottom-right corner.  Shows the installed build's short
        // commit hash.  Tap to check GitHub for a newer pre-release and install it.
        versionCardView = TextView(this).apply {
            text = BuildConfig.GIT_COMMIT
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 0, 0, 0))
                cornerRadius = 16f * density
            }
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

        // Search overlay — bottom-anchored panel that fades in/out over the map.
        // Added before the menu dismiss overlay so the menu still sits on top.
        recentSearches = RecentSearches(getSharedPreferences("search", MODE_PRIVATE))
        searchOverlayResult = buildSearchOverlay(
            context        = this,
            recentSearches = recentSearches,
            onClose        = { closeSearch() },
            onSearch       = { query -> performSearch(query) },
            onResultClick  = { result -> onSearchResultSelected(result) },
        )
        root.addView(
            searchOverlayResult.root.apply { visibility = View.GONE },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // Smoothly track the keyboard height so the search panel rises and falls
        // in sync with the keyboard animation.
        //
        // WindowInsetsAnimationCompat.Callback fires every frame during the IME
        // animation (API 30+) rather than only at the final value like the static
        // OnApplyWindowInsetsListener would, giving us per-frame position updates.
        //
        // Dismissing the keyboard (e.g. back gesture) slides the panel back to the
        // screen edge but does NOT close the search — the user must tap the map to do
        // that.  This gives the result list maximum screen space after "Go" is tapped.
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (!viewModel.uiState.value.isSearchOpen) return insets
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    (searchOverlayResult.root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.bottomMargin = imeBottom
                        searchOverlayResult.root.layoutParams = lp
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (!viewModel.uiState.value.isSearchOpen) return
                    // Snap to the final keyboard position after the animation ends.
                    val imeBottom = ViewCompat.getRootWindowInsets(root)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    (searchOverlayResult.root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.bottomMargin = imeBottom
                        searchOverlayResult.root.layoutParams = lp
                    }
                }
            },
        )

        // Apply side margins from system bars (e.g. landscape navigation bar) so the
        // search panel never overlaps buttons on the left/right edges.
        ViewCompat.setOnApplyWindowInsetsListener(searchOverlayResult.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin  = bars.left
                lp.rightMargin = bars.right
                v.layoutParams = lp
            }
            insets
        }

        // Dismiss overlay — full-screen transparent tap target that closes the menu.
        // Added last so it sits above all other views when visible.
        menuDismissOverlay = View(this).apply {
            background = null   // suppress theme-default pressed-state highlight (would flicker on tap)
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            setOnClickListener { closeMenu() }
        }
        root.addView(
            menuDismissOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Popup menu panel — the hamburger button IS this panel's top-left corner.
        // Starts at button size (64×64dp); runOpenMenuAnimation/runCloseMenuAnimation
        // animate the layout params to expand/collapse it.
        // cornerRadius=32dp gives a perfect circle at button size.
        //
        // The hamburger click lambda checks whether we're inside the settings layer:
        // if so, it acts as a "back" button; otherwise it toggles the menu open/closed.
        buildMenuPanel(
            context      = this,
            onToggleMenu = {
                val s = viewModel.uiState.value
                when {
                    s.isInDebugMenu    -> viewModel.exitDebugMenu()
                    s.isInSettingsMenu -> viewModel.exitSettingsMenu()
                    else               -> toggleMenu()
                }
            },
        ).also { result ->
            menuPanelResult = result
            menuPanel       = result.root
            hamburgerBars   = result.hamburgerBars
            // Settings row in main list → enters the settings submenu layer.
            result.settingsRowInList.setOnClickListener { viewModel.enterSettingsMenu() }
            // Debug row in settings → enters the debug submenu layer.
            result.debugRowInSettings.setOnClickListener { viewModel.enterDebugMenu() }
            // Debug Mode toggle → flips isDebugMode in state.
            result.debugContent.getChildAt(0).setOnClickListener { viewModel.toggleDebugMode() }
            // Run Benchmark → starts the benchmark.
            result.debugContent.getChildAt(1).setOnClickListener { startBenchmark() }
        }
        root.addView(
            menuPanel,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.START)
                .apply { setMargins(btnMargin, btnMargin, 0, 0) },
        )

        setContentView(root)

        // Observe UI state and render changes reactively.
        // repeatOnLifecycle(STARTED): pauses collection when the app is in the background
        // and re-subscribes on resume, which re-emits the current state and re-applies
        // all visibilities / animations to handle any changes that happened off-screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state, renderedState)
                    renderedState = state
                }
            }
        }

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
            // A tap on the map surface closes the search panel (all elements).
            m.addOnMapClickListener {
                if (viewModel.uiState.value.isSearchOpen) {
                    closeSearch()
                    true  // consumed — don't propagate further
                } else {
                    false
                }
            }
            m.addOnCameraMoveStartedListener { reason ->
                TileCache.gate.pause()
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    if (viewModel.uiState.value.isFollowModeActive) {
                        viewModel.disableFollowMode()
                    } else {
                        enterPanningMode()
                    }
                }
            }
            // Open the gate when the camera stops.  MapLibre will immediately
            // start filling in any missing tiles; the map is static, so
            // uploads do not drop visible frames.
            m.addOnCameraIdleListener {
                TileCache.gate.resume()
            }
            if (BuildConfig.DEBUG) {
                var glFrameCount = 0
                var glWindowNs   = 0L
                mapView.addOnDidFinishRenderingFrameListener { _, _, _ ->
                    val now = System.nanoTime()
                    if (glWindowNs == 0L) { glWindowNs = now; return@addOnDidFinishRenderingFrameListener }
                    glFrameCount++
                    val elapsed = now - glWindowNs
                    if (elapsed >= 1_000_000_000L) {
                        osdGlFps     = glFrameCount * 1_000_000_000.0 / elapsed
                        glFrameCount = 0
                        glWindowNs   = now
                    }
                }
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
        }

        m.locationComponent.lastKnownLocation?.let { loc ->
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    14.0,
                ),
                600,
            )
        }
    }

    private fun handleRemoteEvent(event: RemoteEvent) {
        val m = map ?: return
        when (event) {

            is RemoteEvent.JoyInput -> panController.onJoyInput(event.dx, event.dy)

            is RemoteEvent.KeyDown -> panController.onKeyDown(event.key)

            is RemoteEvent.KeyUp -> panController.onKeyUp(event.key, m)

            is RemoteEvent.ShortPress -> when (event.key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {}
                RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {}  // handled by KeyDown/KeyUp

                RemoteKey.CONFIRM ->
                    // In panning mode: confirm exits panning and re-locks on GPS.
                    // Outside panning mode: fly to current location directly.
                    if (viewModel.uiState.value.isInPanningMode) {
                        exitPanningMode()
                    } else {
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            flyToLocation(m, LatLng(loc.latitude, loc.longitude))
                        }
                    }

                RemoteKey.BACK ->
                    // In panning mode: exit panning → re-lock on GPS.
                    if (viewModel.uiState.value.isInPanningMode) {
                        exitPanningMode()
                    }
            }

            is RemoteEvent.LongPress -> when (event.key) {
                RemoteKey.CONFIRM ->
                    if (viewModel.uiState.value.isInPanningMode) {
                        // Capture the map position under the crosshair (screen centre)
                        // and anchor the drag line to that point.  The Choreographer
                        // loop keeps the line's "from" end on the current GPS fix.
                        val screenCenter = PointF(mapView.width / 2f, mapView.height / 2f)
                        val target = m.projection.fromScreenLocation(screenCenter)
                        dragLineAnchor = target
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            setDragLine(LatLng(loc.latitude, loc.longitude), target)
                        }
                    }

                else -> {}
            }
        }
    }

    /**
     * Switch to panning mode: show the crosshair, disable GPS camera tracking.
     *
     * Idempotent — StateFlow only emits when the value changes, so repeated calls
     * while already in panning mode produce no extra renders.
     */
    private fun enterPanningMode() {
        viewModel.enterPanningMode()
    }

    /**
     * Leave panning mode: hide the crosshair, re-enable GPS tracking, and
     * animate back to the user's current location.
     *
     * The camera tracking re-enable and flyToLocation are triggered reactively
     * in [renderUiState] when it observes [MapUiState.isInPanningMode] go false.
     */
    private fun exitPanningMode() {
        viewModel.exitPanningMode()
    }

    // ── Map movement primitives ────────────────────────────────────────────────

    /**
     * Smoothly animates the map tilt to [angle] degrees.
     *
     * Default 45° gives a comfortable perspective view.  Pass 0.0 to flatten the map.
     * All other camera properties (position, zoom, bearing, padding) are preserved.
     */
    private fun tilt(angle: Double = 45.0, durationMs: Int = 400) {
        val m = map ?: return
        val cur = m.cameraPosition
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(cur.target)
                    .zoom(cur.zoom)
                    .bearing(cur.bearing)
                    .tilt(angle)
                    .padding(cur.padding)
                    .build(),
            ),
            durationMs,
        )
    }

    /**
     * Smoothly rotates the map so [bearing] degrees faces upward.
     *
     * The animation duration scales with angular distance (100–600 ms) so both
     * small corrections and large turns feel natural.  Calling this again while
     * an animation is in-flight cancels it and starts toward the new target —
     * sensor or GPS updates naturally chain into smooth continuous rotation.
     *
     * All other camera properties (position, zoom, tilt, padding) are preserved.
     */
    private fun rotate(bearing: Double) {
        val m = map ?: return
        val cur = m.cameraPosition
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(cur.target)
                    .zoom(cur.zoom)
                    .bearing(bearing)
                    .tilt(cur.tilt)
                    .padding(cur.padding)
                    .build(),
            ),
            angularDuration(cur.bearing, bearing),
        )
    }

    /** Maps shortest angular distance (0–180°) to animation duration (100–600 ms). */
    private fun angularDuration(from: Double, to: Double): Int {
        val delta = Math.abs(((to - from + 540.0) % 360.0) - 180.0)
        return (100 + (delta / 180.0) * 500).toInt()
    }

    // ── Mode management ───────────────────────────────────────────────────────

    /**
     * Apply (or animate) camera tilt and vertical focal-point offset for [mode].
     *
     * Navigate mode: 45° tilt with top padding that anchors the GPS dot at ~90 %
     * from the top, keeping more of the road ahead visible.  When [animated] is
     * true the camera flies to the current GPS position (with zoom scaling for
     * distance) before settling at the final tilt and padding.
     *
     * All other modes: flat (0° tilt), no padding, 400 ms smooth animation.
     */
    private fun applyCameraForMode(mode: AppMode, animated: Boolean) {
        val m = map ?: return
        val screenH = resources.displayMetrics.heightPixels

        if (mode == AppMode.NAVIGATE && animated) {
            // Fly to GPS position with tilt and focal-point padding applied at arrival.
            // flyToLocation uses the two-phase zoom when far from GPS, a single smooth
            // animation when nearby — the tilt/padding are always set in the final phase.
            val gpsTarget = m.locationComponent.lastKnownLocation
                ?.let { LatLng(it.latitude, it.longitude) }
                ?: m.cameraPosition.target
                ?: return
            flyToLocation(
                m, gpsTarget,
                zoom    = 17.0,
                tilt    = 45.0,
                padding = doubleArrayOf(0.0, screenH * 0.5, 0.0, 0.0),
                enableZoom = true,
            )
            return
        }

        val targetTilt: Double
        val topPad: Double
        when (mode) {
            AppMode.NAVIGATE -> { targetTilt = 45.0; topPad = screenH * 0.5 }
            else             -> { targetTilt = 0.0;  topPad = 0.0 }
        }

        val cur    = m.cameraPosition
        val newPos = CameraPosition.Builder()
            .target(cur.target)
            .zoom(if (mode == AppMode.NAVIGATE) 17.0 else cur.zoom)
            .bearing(cur.bearing)
            .tilt(targetTilt)
            .padding(doubleArrayOf(0.0, topPad, 0.0, 0.0))
            .build()

        if (animated) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(newPos), 400)
        } else {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(newPos))
        }
    }

    /**
     * Switch the app to [mode].
     *
     * Delegates to [MapViewModel.setMode]; [renderUiState] reacts to the state
     * change and drives the two-phase animation (outgoing chrome slides off-screen,
     * then incoming chrome slides in from the appropriate edge).
     */
    private fun setMode(mode: AppMode) {
        viewModel.setMode(mode)
    }

    /** Instant (no-animation) visibility setup — used only on app startup. */
    private fun applyModeInstant(mode: AppMode) {
        val inExplore  = mode == AppMode.EXPLORE
        val inNavigate = mode == AppMode.NAVIGATE
        val inEdit     = mode == AppMode.EDIT

        menuPanel.visibility        = if (inExplore)  View.VISIBLE else View.GONE
        myLocationButton.visibility = if (inExplore)  View.VISIBLE else View.GONE
        exploreBottomBar.visibility = if (inExplore)  View.VISIBLE else View.GONE
        navigateOverlay.visibility  = if (inNavigate) View.VISIBLE else View.GONE
        editTopBar.visibility       = if (inEdit)      View.VISIBLE else View.GONE

        // Ensure panel is at button size when not in Explore (so it looks correct on return).
        if (!inExplore) {
            val btnSz = (64 * resources.displayMetrics.density).toInt()
            (menuPanel.layoutParams as FrameLayout.LayoutParams).also { lp ->
                lp.width = btnSz; lp.height = btnSz
                menuPanel.layoutParams = lp
            }
            hamburgerBars.forEach { it.rotation = 0f }
        }
    }

    /**
     * Two-phase animated mode transition.
     *
     * Phase 1 (200ms, accelerate): outgoing elements translate off-screen.
     * Phase 2 (250ms, decelerate): incoming elements translate in from their
     * respective edge — starts immediately when the last outgoing animation ends.
     *
     * Explore ←→ Navigate/Edit:
     *   - hamburger + locate-me slide left (off screen start = -screenW)
     *   - Ride/Search/Plan bar slides down  (off screen start = +screenH)
     *   - Navigate banner slides up         (off screen start = -screenH)
     *   - Navigate STOP slides down         (off screen start = +screenH)
     *   - Edit top bar slides up            (off screen start = -screenH)
     */
    private fun animateModeTransition(from: AppMode, to: AppMode) {
        val w        = resources.displayMetrics.widthPixels.toFloat()
        val h        = resources.displayMetrics.heightPixels.toFloat()
        val outDur   = 200L
        val inDur    = 250L
        val outInterp = AccelerateInterpolator()
        val inInterp  = DecelerateInterpolator()

        // ── Phase 2: slide incoming elements in ───────────────────────────────
        fun slideIn() {
            // Animate camera tilt / focal-point offset in sync with the incoming UI.
            applyCameraForMode(to, animated = true)

            when (to) {
                AppMode.EXPLORE -> {
                    menuPanel.translationX        = -w
                    myLocationButton.translationX = -w
                    exploreBottomBar.translationY = h
                    exploreBottomBar.alpha        = 1f   // clear any search-fade leftover
                    menuPanel.visibility          = View.VISIBLE
                    myLocationButton.visibility   = View.VISIBLE
                    exploreBottomBar.visibility   = View.VISIBLE
                    menuPanel.animate().translationX(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                    myLocationButton.animate().translationX(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                    exploreBottomBar.animate().translationY(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                }
                AppMode.NAVIGATE -> {
                    navigateBanner.translationY  = -h
                    navigateStopBtn.translationY = h
                    navigateOverlay.visibility   = View.VISIBLE
                    navigateBanner.animate().translationY(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                    navigateStopBtn.animate().translationY(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                }
                AppMode.EDIT -> {
                    editTopBar.translationY = -h
                    editTopBar.visibility   = View.VISIBLE
                    editTopBar.animate().translationY(0f)
                        .setDuration(inDur).setInterpolator(inInterp).start()
                }
            }
        }

        // ── Phase 1: slide outgoing elements out, then fire slideIn() ─────────
        when (from) {
            AppMode.EXPLORE -> {
                // Three views leave simultaneously; fire slideIn() when all three finish.
                val latch = AnimationLatch(3) { slideIn() }

                menuPanel.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        menuPanel.visibility   = View.GONE
                        menuPanel.translationX = 0f
                        latch.decrement()
                    }.start()
                myLocationButton.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        myLocationButton.visibility   = View.GONE
                        myLocationButton.translationX = 0f
                        latch.decrement()
                    }.start()
                exploreBottomBar.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        exploreBottomBar.visibility   = View.GONE
                        exploreBottomBar.translationY = 0f
                        latch.decrement()
                    }.start()
            }

            AppMode.NAVIGATE -> {
                // Banner and STOP leave simultaneously; hide the overlay container when done.
                val latch = AnimationLatch(2) {
                    navigateOverlay.visibility = View.GONE
                    slideIn()
                }
                navigateBanner.animate().translationY(-h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateBanner.translationY = 0f; latch.decrement() }.start()
                navigateStopBtn.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateStopBtn.translationY = 0f; latch.decrement() }.start()
            }

            AppMode.EDIT -> {
                editTopBar.animate().translationY(-h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        editTopBar.visibility   = View.GONE
                        editTopBar.translationY = 0f
                        slideIn()
                    }.start()
            }
        }
    }

    // ── Map style helpers ─────────────────────────────────────────────────────

    private fun styleUrl(isDark: Boolean): String {
        val key = BuildConfig.MAPTILER_KEY
        return if (isDark) "$STYLE_DARK$key" else "$STYLE_OUTDOOR$key"
    }

    private fun reloadStyle(isDark: Boolean) {
        val m = map ?: return
        val savedCamera = m.cameraPosition
        style = null
        m.setStyle(styleUrl(isDark)) { s ->
            style = s
            m.moveCamera(CameraUpdateFactory.newCameraPosition(savedCamera))
            reactivateLocationComponent()
            if (viewModel.uiState.value.isSatelliteEnabled) {
                applySatelliteLayer(enabled = true, animated = false)
            }
        }
    }

    /**
     * Re-activates the location component after a style reload WITHOUT
     * moving the camera. [enableLocationIfReady] animates to the current
     * GPS position, which is correct on first launch but wrong after a
     * style-only change (e.g. dark-mode toggle).
     */
    @SuppressLint("MissingPermission")
    private fun reactivateLocationComponent() {
        val m = map   ?: return
        val s = style ?: return
        if (!hasLocationPermission) return

        m.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(this@MapActivity, s).build(),
            )
            isLocationComponentEnabled = true
        }
    }

    private fun setToggleActive(btn: TextView, active: Boolean) {
        val d = resources.displayMetrics.density
        val r = 24 * d
        val bg = if (active) Color.argb(220, 30, 100, 200) else Color.argb(180, 0, 0, 0)
        btn.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(bg)
            },
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.WHITE)
            },
        )
    }

    private fun applySatelliteLayer(enabled: Boolean, animated: Boolean) {
        val s = style ?: return

        satelliteAnimator?.cancel()
        satelliteAnimator = null

        if (enabled) {
            if (s.getSourceAs<RasterSource>(SOURCE_SATELLITE) == null) {
                val tileSet = TileSet("2.1.0", "$SAT_TILE_TEMPLATE${BuildConfig.MAPTILER_KEY}")
                s.addSource(RasterSource(SOURCE_SATELLITE, tileSet, 512))
                val satLayer = RasterLayer(LAYER_SATELLITE, SOURCE_SATELLITE).apply {
                    setProperties(PropertyFactory.rasterOpacity(0f))
                }
                // Insert satellite above base-map fills but below labels/icons.
                val firstSymbol = s.layers.firstOrNull { it is SymbolLayer }
                if (firstSymbol != null) {
                    s.addLayerBelow(satLayer, firstSymbol.id)
                } else {
                    s.addLayer(satLayer)
                }
            }
            val layer = s.getLayerAs<RasterLayer>(LAYER_SATELLITE) ?: return
            val targetOpacity = 1f

            if (!animated) {
                layer.setProperties(PropertyFactory.rasterOpacity(targetOpacity))
                return
            }
            val startOpacity = layer.rasterOpacity?.value ?: 0f
            satelliteAnimator = animBag.add(ValueAnimator.ofFloat(startOpacity, targetOpacity).apply {
                duration = SAT_ANIMATE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    style?.getLayerAs<RasterLayer>(LAYER_SATELLITE)
                        ?.setProperties(PropertyFactory.rasterOpacity(va.animatedValue as Float))
                }
                start()
            })
        } else {
            val layer = s.getLayerAs<RasterLayer>(LAYER_SATELLITE) ?: return
            val startOpacity = layer.rasterOpacity?.value ?: 1f

            if (!animated) {
                s.removeLayer(LAYER_SATELLITE)
                s.removeSource(SOURCE_SATELLITE)
                return
            }
            satelliteAnimator = animBag.add(ValueAnimator.ofFloat(startOpacity, 0f).apply {
                duration = SAT_ANIMATE_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener { va ->
                    style?.getLayerAs<RasterLayer>(LAYER_SATELLITE)
                        ?.setProperties(PropertyFactory.rasterOpacity(va.animatedValue as Float))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (satelliteAnimator === animation || satelliteAnimator == null) {
                            style?.removeLayer(LAYER_SATELLITE)
                            style?.removeSource(SOURCE_SATELLITE)
                        }
                    }
                })
                start()
            })
        }
    }

    // ── Reactive state renderer ───────────────────────────────────────────────

    /**
     * Applies [new] UI state to all views.
     *
     * Called from the [viewModel] StateFlow collector on the main thread whenever
     * the state changes.  [old] is `null` on the very first call (initial setup),
     * which triggers instant visibility setup with no animations.
     *
     * All three state dimensions are handled independently so partial changes
     * (e.g. only [MapUiState.isInPanningMode] changing) do the minimum work.
     */
    private fun renderUiState(new: MapUiState, old: MapUiState?) {
        val modeChanged         = old?.mode != new.mode
        val panningChanged      = old?.isInPanningMode != new.isInPanningMode
        val followChanged       = old?.isFollowModeActive != new.isFollowModeActive
        val menuChanged         = old?.isMenuOpen != new.isMenuOpen
        val searchChanged       = old?.isSearchOpen != new.isSearchOpen
        val settingsMenuChanged = old?.isInSettingsMenu != new.isInSettingsMenu
        val debugMenuChanged    = old?.isInDebugMenu != new.isInDebugMenu
        val debugModeChanged    = old?.isDebugMode != new.isDebugMode

        // ── Crosshair ──────────────────────────────────────────────────────────
        // EDIT always shows the crosshair (it acts as the placement cursor).
        // Otherwise the crosshair is shown only while the user is manually panning.
        crosshairView.visibility = when {
            new.mode == AppMode.EDIT || new.isInPanningMode -> View.VISIBLE
            else                                            -> View.GONE
        }


        // ── Fly-to on follow mode enable ───────────────────────────────────────
        // Snap the camera to GPS immediately when follow mode is turned on so
        // there is no visible delay before the Choreographer tracking takes over.
        if (followChanged && new.isFollowModeActive) {
            // Reset dead-reckoning state so stale velocity is not extrapolated
            // from a previous follow session.
            followLastLat     = Double.NaN
            followLastLon     = Double.NaN
            followLastTimeNs  = 0L
            followVelocityLat = 0.0
            followVelocityLon = 0.0
            val m   = map
            val loc = m?.locationComponent?.lastKnownLocation
            if (m != null && loc != null) {
                flyToLocation(m, LatLng(loc.latitude, loc.longitude))
            }
        }

        // ── Fly-to on panning exit (without follow mode) ──────────────────────
        // When panning exits and follow mode is NOT active, fly back to GPS once.
        // If follow mode IS active, the snap above (or the Choreographer loop)
        // already handles re-centering.
        if (panningChanged && !new.isInPanningMode && old?.isInPanningMode == true
            && !new.isFollowModeActive) {
            val m   = map
            val loc = m?.locationComponent?.lastKnownLocation
            if (m != null && loc != null) {
                flyToLocation(m, LatLng(loc.latitude, loc.longitude))
            }
        }

        // ── Search overlay ─────────────────────────────────────────────────────
        if (searchChanged) {
            if (new.isSearchOpen) {
                showSearchOverlay()
                exploreBottomBar.animate().alpha(0f).setDuration(200).start()
            } else {
                hideSearchOverlay()
                // Skip the fade-back if a mode transition is also firing — the
                // mode animation owns the bottom bar and the two ViewPropertyAnimators
                // would cancel each other (only the last call to animate() wins).
                if (!modeChanged) {
                    exploreBottomBar.animate().alpha(1f).setDuration(200).start()
                }
            }
        }

        // ── Menu animation ─────────────────────────────────────────────────────
        // Skip on the first render (old == null): the panel already starts at button size.
        if (old != null && menuChanged) {
            if (new.isMenuOpen) {
                runOpenMenuAnimation()
            } else {
                // Closing: reset debug layer views first (outermost), then settings layer.
                if (old.isInDebugMenu) {
                    debugMenuAnimator?.cancel()
                    menuPanelResult.debugGhostHeader.visibility = View.GONE
                    menuPanelResult.debugGhostHeader.alpha = 0f
                    menuPanelResult.debugContent.visibility = View.GONE
                    menuPanelResult.debugContent.alpha = 0f
                }
                if (old.isInSettingsMenu) {
                    settingsMenuAnimator?.cancel()
                    menuPanelResult.settingsGhostHeader.visibility = View.GONE
                    menuPanelResult.settingsGhostHeader.alpha = 0f
                    menuPanelResult.settingsContent.visibility = View.GONE
                    menuPanelResult.settingsContent.alpha = 0f
                    menuPanelResult.mainMenuScroll.visibility = View.VISIBLE
                    menuPanelResult.mainMenuScroll.alpha = 1f
                    menuPanelResult.settingsRowIcon.alpha = 1f
                    hamburgerBars.forEach { it.scaleX = 1f; it.translationX = 0f; it.translationY = 0f }
                }
                // Close instantly when a mode change is also happening so the menu
                // does not fight with the mode-transition slide animation.
                runCloseMenuAnimation(instant = modeChanged)
            }
        }

        // ── Settings submenu transition ────────────────────────────────────────
        // Only animate when the menu is open and the settings layer changes.
        if (old != null && settingsMenuChanged && new.isMenuOpen) {
            if (new.isInSettingsMenu) runEnterSettingsAnimation()
            else runExitSettingsAnimation()
        }

        // ── Debug submenu transition ───────────────────────────────────────────
        // Only animate when the menu is open and the debug layer changes.
        if (old != null && debugMenuChanged && new.isMenuOpen) {
            if (new.isInDebugMenu) runEnterDebugAnimation()
            else runExitDebugAnimation()
        }

        // ── Debug overlay ──────────────────────────────────────────────────────
        if (debugModeChanged || old == null) {
            osdView.visibility = if (new.isDebugMode) View.VISIBLE else View.GONE
            menuPanelResult.debugToggleLabel.text =
                "Debug Mode: ${if (new.isDebugMode) "ON" else "OFF"}"
        }

        // ── Mode transition ────────────────────────────────────────────────────
        if (modeChanged) {
            if (old == null) {
                // First render: instant visibility setup, no animation.
                applyModeInstant(new.mode)
                applyCameraForMode(new.mode, animated = false)
            } else {
                animateModeTransition(old.mode, new.mode)
                applyCameraForMode(new.mode, animated = true)
            }
        }

        // ── Map style toggles ──────────────────────────────────────────────────
        val satChanged  = old?.isSatelliteEnabled != new.isSatelliteEnabled
        val darkChanged = old?.isDarkMode         != new.isDarkMode

        if (darkChanged) {
            reloadStyle(new.isDarkMode)   // callback handles satellite re-add
        } else if (satChanged) {
            applySatelliteLayer(enabled = new.isSatelliteEnabled, animated = old != null)
        }

        setToggleActive(satelliteToggleBtn, new.isSatelliteEnabled)
        setToggleActive(darkModeToggleBtn,  new.isDarkMode)
        setToggleActive(courseUpToggleBtn,  new.isCourseUpEnabled)
        setToggleActive(followToggleBtn,    new.isFollowModeActive)

        // ── Course Up → North Up transition ───────────────────────────────────
        // When Course Up is turned off, animate the map back to 0° (north at top).
        val courseUpChanged = old?.isCourseUpEnabled != new.isCourseUpEnabled
        if (courseUpChanged && !new.isCourseUpEnabled) {
            rotate(0.0)
        }
    }

    private fun toggleMenu() { viewModel.toggleMenu() }
    private fun openMenu()   { viewModel.openMenu() }
    private fun closeMenu()  { viewModel.closeMenu() }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun openSearch()  { viewModel.openSearch() }
    private fun closeSearch() { viewModel.closeSearch() }

    private fun showSearchOverlay() {
        // Results are intentionally NOT cleared here — the previous result list
        // (if any) remains visible so re-opening shows the last results.
        //
        // The panel appears at the screen edge (bottomMargin = 0, no keyboard).
        // The keyboard opens only when the user taps the search field; the IME
        // animation callback then smoothly slides the panel up in sync.
        searchOverlayResult.root.alpha = 0f
        (searchOverlayResult.root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = 0
            searchOverlayResult.root.layoutParams = lp
        }
        searchOverlayResult.root.visibility = View.VISIBLE
        searchOverlayResult.root.animate().alpha(1f).setDuration(200).start()
        searchOverlayResult.prepareForOpen()
        panToLastSearchResult()
    }

    /**
     * When the search panel opens and a previous result exists, pan the map so
     * that result sits at 25 % from the top edge of the screen — neatly visible
     * in the space between the top of the screen and the search panel below.
     *
     * ### Geometry
     * We want [lastSearchTarget] to appear at Y = 25 % of the map height.
     * Its current screen position is obtained via the map projection, then we
     * compute the screen-space delta from that position to the target row,
     * apply the same delta to the screen centre to find where the camera centre
     * should be, and convert that back to a [LatLng] for `animateCamera`.
     *
     *   resultScreen  = projection.toScreenLocation(lastSearchTarget)
     *   dx = resultScreen.x − w/2          (centre it horizontally too)
     *   dy = resultScreen.y − h * 0.25     (lift it to 25 % from top)
     *   newCameraScreen = PointF(w/2 + dx, h/2 + dy)
     *   newCameraLatLng = projection.fromScreenLocation(newCameraScreen)
     */
    private fun panToLastSearchResult() {
        val target = lastSearchTarget ?: return
        val m      = map              ?: return
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        if (w == 0f || h == 0f) return

        val resultScreen = m.projection.toScreenLocation(target)
        val dx = resultScreen.x - w / 2f
        val dy = resultScreen.y - h * 0.25f
        val newCameraScreen = PointF(w / 2f + dx, h / 2f + dy)
        val newCameraTarget = m.projection.fromScreenLocation(newCameraScreen)

        flyToLocation(m, newCameraTarget, enableZoom = false)
    }

    private fun hideSearchOverlay() {
        searchOverlayResult.hideKeyboard()
        searchOverlayResult.root.animate().alpha(0f).setDuration(150)
            .withEndAction {
                searchOverlayResult.root.visibility = View.GONE
                searchOverlayResult.root.alpha = 1f   // reset for next open
            }
            .start()
    }

    private fun performSearch(query: String) {
        recentSearches.saveSearchTerm(query)
        lifecycleScope.launch {
            searchOverlayResult.showLoading()
            try {
                // Determine anchor point based on GPS/Map toggle
                val anchor: LatLng? = if (searchOverlayResult.isGpsAnchor()) {
                    map?.locationComponent?.lastKnownLocation?.let {
                        LatLng(it.latitude, it.longitude)
                    }
                } else {
                    map?.cameraPosition?.target
                }

                // Compute viewbox when local search is enabled
                val viewbox: BoundingBox? = if (searchOverlayResult.isLocalSearch() && anchor != null) {
                    // Use the visible map region as the bounding box
                    val bounds = map?.projection?.visibleRegion?.latLngBounds
                    if (bounds != null) {
                        BoundingBox(
                            minLon = bounds.longitudeWest,
                            minLat = bounds.latitudeSouth,
                            maxLon = bounds.longitudeEast,
                            maxLat = bounds.latitudeNorth,
                        )
                    } else null
                } else null

                val rawResults = geocoding.search(
                    query,
                    viewbox = viewbox,
                    bounded = searchOverlayResult.isLocalSearch(),
                    lat = anchor?.latitude,
                    lon = anchor?.longitude,
                )

                // Enrich each result with distance/bearing from the anchor.
                // Ordering is provided by Nominatim's proximity bias (lat/lon params above).
                val results = if (anchor != null) {
                    rawResults.map { r ->
                        val out = FloatArray(2)
                        Location.distanceBetween(anchor.latitude, anchor.longitude, r.lat, r.lon, out)
                        r.copy(distanceMeters = out[0], bearingDeg = out[1])
                    }
                } else {
                    rawResults
                }
                searchOverlayResult.showResults(results)
            } catch (_: Exception) {
                searchOverlayResult.showError()
            }
        }
    }

    private fun onSearchResultSelected(result: SearchResult) {
        recentSearches.saveLocation(result.displayName, result.lat, result.lon)
        closeSearch()
        val target = LatLng(result.lat, result.lon)
        lastSearchTarget = target
        val m = map ?: return
        flyToLocation(m, target, zoom = 14.0)
        placeSearchPin(target)
    }

    private fun placeSearchPin(target: LatLng) {
        val s = style ?: return
        val feature    = Feature.fromGeometry(Point.fromLngLat(target.longitude, target.latitude))
        val collection = FeatureCollection.fromFeatures(listOf(feature))

        val existing = s.getSourceAs<GeoJsonSource>(SOURCE_SEARCH_PIN)
        if (existing != null) {
            existing.setGeoJson(collection)
            return
        }

        s.addSource(GeoJsonSource(SOURCE_SEARCH_PIN, collection, GeoJsonOptions().withSynchronousUpdate(true)))
        s.addLayer(
            CircleLayer(LAYER_SEARCH_PIN, SOURCE_SEARCH_PIN).apply {
                setProperties(
                    PropertyFactory.circleColor("#FF0000"),
                    PropertyFactory.circleRadius(10f),
                    PropertyFactory.circleStrokeColor("#8B0000"),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleOpacity(0.9f),
                )
            }
        )
    }

    /**
     * Expand the panel from button size (64×64dp) to its full content size.
     *
     * Pure animation helper called from [renderUiState] when [MapUiState.isMenuOpen]
     * transitions to true.  Never mutates state directly.
     *
     * The LayoutParams width+height animate so the cornerRadius (32dp) stays
     * visually constant — the shape smoothly transforms from circle → rounded rect.
     * The hamburger icon rotates 90° during the expansion with a staggered cascade.
     */
    private fun runOpenMenuAnimation() {
        menuAnimator?.cancel()
        menuDismissOverlay.visibility = View.VISIBLE

        val d      = resources.displayMetrics.density
        val panelW = (280 * d).toInt()
        val panelH = getOrMeasurePanelHeight()
        val lp     = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
        val startH    = lp.height
        // Capture each bar's current rotation and scaleX so the animator can
        // interpolate from wherever the animation was interrupted (e.g. rapid
        // open→close→open, or close from back-arrow state).
        val barStartR  = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartSX = FloatArray(3) { hamburgerBars[it].scaleX }
        // Top bar is fastest: it reaches 90° at ~65% of the animation, giving a
        // staggered cascade where bar 0 leads and bar 2 trails.
        val openSpeeds = floatArrayOf(1.4f, 1.2f, 1.0f)

        menuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (panelW - startW) * t).toInt()
                lp.height = (startH + (panelH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                hamburgerBars.forEachIndexed { i, bar ->
                    val p = (t * openSpeeds[i]).coerceAtMost(1f)
                    bar.rotation = barStartR[i] + (90f - barStartR[i]) * p
                    bar.scaleX   = barStartSX[i] + (1f - barStartSX[i]) * p
                }
            }
            start()
        })
    }

    /**
     * Collapse the panel back to button size (64×64dp).
     *
     * Pure animation helper called from [renderUiState] when [MapUiState.isMenuOpen]
     * transitions to false.  Never mutates state directly.
     *
     * @param instant  When true (simultaneous mode change), collapses immediately
     *                 with no animation — avoids fighting with the mode-transition slide.
     */
    private fun runCloseMenuAnimation(instant: Boolean = false) {
        menuAnimator?.cancel()

        val d     = resources.displayMetrics.density
        val btnSz = (64 * d).toInt()
        val lp    = menuPanel.layoutParams as FrameLayout.LayoutParams

        if (instant) {
            lp.width  = btnSz
            lp.height = btnSz
            menuPanel.layoutParams = lp
            hamburgerBars.forEach { it.rotation = 0f; it.scaleX = 1f; it.translationX = 0f; it.translationY = 0f }
            menuDismissOverlay.visibility = View.GONE
            return
        }

        val startW      = lp.width
        val startH      = lp.height
        val barStartR   = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartS   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTY  = FloatArray(3) { hamburgerBars[it].translationY }
        // Reverse of open: bottom bar now fastest, top bar slowest.
        val closeSpeeds = floatArrayOf(1.0f, 1.2f, 1.4f)

        menuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 180
            interpolator = AccelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (btnSz - startW) * t).toInt()
                lp.height = (startH + (btnSz - startH) * t).toInt()
                menuPanel.layoutParams = lp
                hamburgerBars.forEachIndexed { i, bar ->
                    val p = (t * closeSpeeds[i]).coerceAtMost(1f)
                    bar.rotation     = barStartR[i]  + (0f - barStartR[i])  * p
                    bar.scaleX       = barStartS[i]  + (1f - barStartS[i])  * p
                    bar.translationX = barStartTX[i] + (0f - barStartTX[i]) * p
                    bar.translationY = barStartTY[i] + (0f - barStartTY[i]) * p
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    menuDismissOverlay.visibility = View.GONE
                }
            })
            start()
        })
    }

    /**
     * Returns the panel's full expanded height in pixels.
     *
     * Measured on the first call by forcing a measure pass at full panel width.
     * Cached thereafter so subsequent open/close animations avoid re-measuring.
     */
    private fun getOrMeasurePanelHeight(): Int {
        if (panelFullHeight > 0) return panelFullHeight
        val d      = resources.displayMetrics.density
        val panelW = (280 * d).toInt()
        val wSpec  = View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY)
        val hSpec  = View.MeasureSpec.makeMeasureSpec(
            resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST,
        )
        menuPanel.measure(wSpec, hSpec)
        panelFullHeight = menuPanel.measuredHeight
        return panelFullHeight
    }

    /**
     * Returns the settings panel height in pixels (64dp header + settings items).
     *
     * Measured on first call by forcing a layout pass on settingsContent.
     * Cached thereafter.
     */
    private fun getOrMeasureSettingsHeight(): Int {
        if (settingsMenuHeight > 0) return settingsMenuHeight
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val panelW = (280 * d).toInt()
        val wSpec  = View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY)
        val hSpec  = View.MeasureSpec.makeMeasureSpec(
            resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST,
        )
        menuPanelResult.settingsContent.measure(wSpec, hSpec)
        settingsMenuHeight = itemH + menuPanelResult.settingsContent.measuredHeight
        return settingsMenuHeight
    }

    /**
     * Returns the debug panel height in pixels (64dp header + debug items).
     *
     * Measured on first call by forcing a layout pass on debugContent.
     * Cached thereafter.
     */
    private fun getOrMeasureDebugHeight(): Int {
        if (debugMenuHeight > 0) return debugMenuHeight
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val panelW = (280 * d).toInt()
        val wSpec  = View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY)
        val hSpec  = View.MeasureSpec.makeMeasureSpec(
            resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST,
        )
        menuPanelResult.debugContent.measure(wSpec, hSpec)
        debugMenuHeight = itemH + menuPanelResult.debugContent.measuredHeight
        return debugMenuHeight
    }

    /**
     * Animate from the Settings submenu into the Debug submenu layer.
     *
     * Sequence (all concurrent, [Anim.NORMAL] duration):
     * - Debug ghost header slides up from Debug row position (y=1×itemH) to header (y=0) and fades in.
     * - Settings content and Settings ghost header fade out.
     * - Debug content fades in (starts at t=0.3 to stagger behind the fade-out).
     * - Panel height shrinks from settings height to debug height.
     * - Hamburger bars stay in back-arrow shape (already set).
     */
    private fun runEnterDebugAnimation() {
        debugMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val debugGhost      = menuPanelResult.debugGhostHeader
        val settingsGhost   = menuPanelResult.settingsGhostHeader
        val settingsContent = menuPanelResult.settingsContent
        val debugContent    = menuPanelResult.debugContent

        val ghostStartY = itemH.toFloat()
        debugGhost.translationY = ghostStartY
        debugGhost.alpha        = 0f
        debugGhost.visibility   = View.VISIBLE

        debugContent.visibility = View.VISIBLE
        debugContent.alpha      = 0f

        val targetH = getOrMeasureDebugHeight()
        val lp      = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startH  = lp.height

        debugMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams     = lp
                debugGhost.translationY    = ghostStartY * (1f - t)
                debugGhost.alpha           = t
                settingsGhost.alpha        = 1f - t
                settingsContent.alpha      = 1f - t
                debugContent.alpha         = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settingsGhost.visibility   = View.GONE
                    settingsContent.visibility = View.GONE
                }
            })
            start()
        })
    }

    /**
     * Animate back from the Debug submenu to the Settings layer.
     *
     * Reverses [runEnterDebugAnimation].
     */
    private fun runExitDebugAnimation() {
        debugMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val debugGhost      = menuPanelResult.debugGhostHeader
        val settingsGhost   = menuPanelResult.settingsGhostHeader
        val settingsContent = menuPanelResult.settingsContent
        val debugContent    = menuPanelResult.debugContent

        val ghostEndY = itemH.toFloat()
        settingsGhost.alpha      = 0f
        settingsGhost.visibility = View.VISIBLE
        settingsContent.alpha      = 0f
        settingsContent.visibility = View.VISIBLE

        val targetH = getOrMeasureSettingsHeight()
        val lp      = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startH  = lp.height

        debugMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams     = lp
                debugGhost.translationY    = ghostEndY * t
                debugGhost.alpha           = 1f - t
                settingsGhost.alpha        = t
                settingsContent.alpha      = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                debugContent.alpha         = (1f - t / 0.7f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    debugGhost.visibility   = View.GONE
                    debugGhost.alpha        = 0f
                    debugContent.visibility = View.GONE
                    debugContent.alpha      = 0f
                }
            })
            start()
        })
    }

    /**
     * Animate from the main menu into the settings submenu layer.
     *
     * Sequence (all concurrent, [Anim.NORMAL] duration):
     * - Ghost header slides up from list position (y=6×itemH) to header (y=0) and fades in.
     * - Main menu ScrollView fades out.
     * - Settings content fades in (starts at t=0.3 to stagger behind the fade-out).
     * - Panel height shrinks from full-menu height to settings height.
     * - Hamburger bars transform to back arrow (←):
     *     bar 0 → −45°, scaleX → 0.5   (upper arrowhead `/`)
     *     bar 1 → 0°,   scaleX → 1.0   (horizontal body)
     *     bar 2 → +45°, scaleX → 0.5   (lower arrowhead `\`)
     * - Settings row icon fades out so it appears to teleport from left to right
     *   (the ghost header shows the icon on the right side).
     */
    private fun runEnterSettingsAnimation() {
        settingsMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost           = menuPanelResult.settingsGhostHeader
        val scroll          = menuPanelResult.mainMenuScroll
        val settingsContent = menuPanelResult.settingsContent
        val rowIcon         = menuPanelResult.settingsRowIcon

        val ghostStartY = (6 * itemH).toFloat()
        ghost.translationY = ghostStartY
        ghost.alpha        = 0f
        ghost.visibility   = View.VISIBLE

        settingsContent.visibility = View.VISIBLE
        settingsContent.alpha      = 0f

        val targetH   = getOrMeasureSettingsHeight()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startH    = lp.height

        val barTargetRot    = floatArrayOf(-45f, 0f, +45f)
        val barTargetScale  = floatArrayOf(0.5f, 1.0f, 0.5f)
        val barTargetTransX = floatArrayOf(-1.5f * d, +2f * d, -1.5f * d)
        val barTargetTransY = floatArrayOf(+1.5f * d,  0f,     -1.5f * d)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha = rowIcon.alpha

        settingsMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY = ghostStartY * (1f - t)
                ghost.alpha        = t
                scroll.alpha       = 1f - t
                settingsContent.alpha = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                // Icon fades out so it appears to teleport to the right side of the header.
                rowIcon.alpha = iconStartAlpha * (1f - t)
                hamburgerBars.forEachIndexed { i, bar ->
                    bar.rotation     = barStartRot[i]    + (barTargetRot[i]    - barStartRot[i])    * t
                    bar.scaleX       = barStartScale[i]  + (barTargetScale[i]  - barStartScale[i])  * t
                    bar.translationX = barStartTransX[i] + (barTargetTransX[i] - barStartTransX[i]) * t
                    bar.translationY = barStartTransY[i] + (barTargetTransY[i] - barStartTransY[i]) * t
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scroll.visibility = View.GONE
                }
            })
            start()
        })
    }

    /**
     * Animate back from the settings submenu layer to the main menu.
     *
     * Reverses [runEnterSettingsAnimation]: ghost header slides back down, scroll fades in,
     * settings content fades out, panel expands to full height, bars return to 90° (open state).
     */
    private fun runExitSettingsAnimation() {
        settingsMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost           = menuPanelResult.settingsGhostHeader
        val scroll          = menuPanelResult.mainMenuScroll
        val settingsContent = menuPanelResult.settingsContent
        val rowIcon         = menuPanelResult.settingsRowIcon

        val ghostEndY = (6 * itemH).toFloat()
        scroll.alpha      = 0f
        scroll.visibility = View.VISIBLE

        val targetH   = getOrMeasurePanelHeight()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startH    = lp.height

        val barTargetRot    = floatArrayOf(90f, 90f, 90f)
        val barTargetScale  = floatArrayOf(1.0f, 1.0f, 1.0f)
        val barTargetTransX = floatArrayOf(0f, 0f, 0f)
        val barTargetTransY = floatArrayOf(0f, 0f, 0f)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha = rowIcon.alpha  // typically 0f (was faded out on enter)

        settingsMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY     = ghostEndY * t
                ghost.alpha            = 1f - t
                scroll.alpha           = t
                settingsContent.alpha  = (1f - t / 0.7f).coerceIn(0f, 1f)
                // Fade the settings row icon back in as we return to the main menu.
                rowIcon.alpha = iconStartAlpha + (1f - iconStartAlpha) * t
                hamburgerBars.forEachIndexed { i, bar ->
                    bar.rotation     = barStartRot[i]    + (barTargetRot[i]    - barStartRot[i])    * t
                    bar.scaleX       = barStartScale[i]  + (barTargetScale[i]  - barStartScale[i])  * t
                    bar.translationX = barStartTransX[i] + (barTargetTransX[i] - barStartTransX[i]) * t
                    bar.translationY = barStartTransY[i] + (barTargetTransY[i] - barStartTransY[i]) * t
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ghost.visibility           = View.GONE
                    ghost.alpha                = 0f
                    settingsContent.visibility = View.GONE
                    settingsContent.alpha      = 0f
                    rowIcon.alpha              = 1f
                }
            })
            start()
        })
    }

    // ── Self-update ───────────────────────────────────────────────────────────

    /**
     * Silently checks for an update at most once every 24 hours on resume.
     *
     * - Requires a validated internet connection ([ConnectivityChecker.isStableOnline]).
     * - If an update has already been found this session, just (re-)starts the animation.
     * - The 24 h rate-limit only applies to automatic checks; manual taps bypass it.
     * - On success, sets [pendingUpdateUrl] and starts [startUpdateAvailableAnimation].
     */
    private fun checkUpdateIfDue() {
        // Already found an update this session — restore the animation only.
        pendingUpdateUrl?.let { url ->
            AppUpdater.versionHashFromUrl(url)?.let { hash -> startUpdateAvailableAnimation(hash) }
            return
        }
        val lastCheck = prefs.getLong("last_update_check_ms", 0L)
        if (System.currentTimeMillis() - lastCheck < 24 * 60 * 60 * 1_000L) return
        if (!ConnectivityChecker.isStableOnline(this)) return

        lifecycleScope.launch {
            prefs.edit().putLong("last_update_check_ms", System.currentTimeMillis()).apply()
            val url = appUpdater.checkForUpdate() ?: return@launch
            appUpdater.cleanupStaleFiles(url)
            pendingUpdateUrl = url
            AppUpdater.versionHashFromUrl(url)?.let { hash -> startUpdateAvailableAnimation(hash) }
        }
    }

    /**
     * Colours the version card with the blue accent and starts the blink loop:
     * old hash (5 s) → 500 ms fade → [newHash] (2 s) → 500 ms fade → repeat.
     */
    private fun startUpdateAvailableAnimation(newHash: String) {
        stopUpdateAvailableAnimation()
        val d = resources.displayMetrics.density
        versionCardView.background = GradientDrawable().apply {
            setColor(Color.argb(220, 0, 80, 160))
            cornerRadius = 16f * d
        }
        updateAnimJob = lifecycleScope.launch {
            while (isActive) {
                versionCardView.alpha = 1f
                versionCardView.text = BuildConfig.GIT_COMMIT
                delay(5_000)

                versionCardView.animate().alpha(0f).setDuration(500).start()
                delay(500)
                versionCardView.text = newHash
                versionCardView.animate().alpha(1f).setDuration(200).start()
                delay(2_000)

                versionCardView.animate().alpha(0f).setDuration(500).start()
                delay(500)
                versionCardView.text = BuildConfig.GIT_COMMIT
                versionCardView.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    /** Cancels the update blink loop and restores the card to full opacity. */
    private fun stopUpdateAvailableAnimation() {
        updateAnimJob?.cancel()
        updateAnimJob = null
        versionCardView.animate().cancel()
        versionCardView.alpha = 1f
    }

    /**
     * Tap handler for the version card.
     *
     * **Path A — card is flashing (update already found):**
     * Starts the download immediately without re-checking GitHub.
     * On cancel or error the animation resumes, since the update is still available.
     *
     * **Path B — card is not flashing (manual check):**
     * Shows "checking…", calls [AppUpdater.checkForUpdate], then either "up to date"
     * or starts the download.  Not rate-limited — always runs when tapped.
     */
    private fun onVersionCardTapped() {
        // If downloading with the overlay hidden, re-show it.
        if (downloadJob?.isActive == true && !isDownloadOverlayVisible) {
            showDownloadOverlay()
            lastDownloadProgress?.let { updateDownloadUi(it) }
            return
        }

        // ── Path A: update already found, card is flashing ───────────────────
        val url = pendingUpdateUrl
        if (url != null && downloadJob?.isActive != true) {
            stopUpdateAvailableAnimation()
            resetVersionCard()
            showDownloadOverlay()
            downloadJob = lifecycleScope.launch {
                try {
                    val apk = appUpdater.downloadApk(url) { progress ->
                        lastDownloadProgress = progress
                        updateDownloadUi(progress)
                    }
                    pendingUpdateUrl = null
                    dismissDownloadOverlay()
                    resetVersionCard()
                    appUpdater.installApk(apk)
                } catch (_: CancellationException) {
                    dismissDownloadOverlay()
                    resetVersionCard()
                    AppUpdater.versionHashFromUrl(url)?.let { startUpdateAvailableAnimation(it) }
                } catch (_: Exception) {
                    dismissDownloadOverlay()
                    versionCardView.text = "error"
                    delay(2_000)
                    resetVersionCard()
                    AppUpdater.versionHashFromUrl(url)?.let { startUpdateAvailableAnimation(it) }
                }
            }
            return
        }

        // ── Path B: manual check (card not flashing, not downloading) ─────────
        if (versionCardView.text.toString() != BuildConfig.GIT_COMMIT) return
        versionCardView.text = "checking…"

        lifecycleScope.launch {
            val apkUrl = appUpdater.checkForUpdate()
            if (apkUrl == null) {
                versionCardView.text = "up to date"
                delay(2_000)
                versionCardView.text = BuildConfig.GIT_COMMIT
                return@launch
            }

            appUpdater.cleanupStaleFiles(apkUrl)
            pendingUpdateUrl = apkUrl

            showDownloadOverlay()
            downloadJob = launch {
                try {
                    val apk = appUpdater.downloadApk(apkUrl) { progress ->
                        lastDownloadProgress = progress
                        updateDownloadUi(progress)
                    }
                    pendingUpdateUrl = null
                    dismissDownloadOverlay()
                    resetVersionCard()
                    appUpdater.installApk(apk)
                } catch (_: CancellationException) {
                    dismissDownloadOverlay()
                    resetVersionCard()
                    AppUpdater.versionHashFromUrl(apkUrl)?.let { startUpdateAvailableAnimation(it) }
                } catch (_: Exception) {
                    dismissDownloadOverlay()
                    versionCardView.text = "error"
                    delay(2_000)
                    resetVersionCard()
                    AppUpdater.versionHashFromUrl(apkUrl)?.let { startUpdateAvailableAnimation(it) }
                }
            }
        }
    }

    private fun formatMb(bytes: Long): String =
        "%.2f".format(bytes / 1_048_576.0)

    private fun formatSpeed(bytesPerSecond: Long): String {
        val mbps = bytesPerSecond / 1_048_576.0
        return if (mbps >= 1.0) "%.1f MB/s".format(mbps)
        else "%.0f KB/s".format(bytesPerSecond / 1_024.0)
    }

    private fun updateDownloadUi(p: DownloadProgress) {
        val sizeText = "${formatMb(p.bytesDownloaded)}/${formatMb(p.totalBytes)} MB"
        val pct = if (p.totalBytes > 0) (p.bytesDownloaded * 100 / p.totalBytes).toInt() else 0

        if (isDownloadOverlayVisible) {
            downloadProgressBar?.progress = pct
            val speedText = if (p.bytesPerSecond > 0) " · ${formatSpeed(p.bytesPerSecond)}" else ""
            downloadLabel?.text = "$sizeText$speedText"
        } else {
            // Show progress on the version card.
            versionCardView.text = sizeText
            updateVersionCardProgress(pct)
        }
    }

    private fun updateVersionCardProgress(pct: Int) {
        val d = resources.displayMetrics.density
        val r = 16f * d
        val base = GradientDrawable().apply {
            setColor(Color.argb(160, 0, 0, 0))
            cornerRadius = r
        }
        val accent = GradientDrawable().apply {
            setColor(Color.argb(220, 0, 80, 160))
            cornerRadius = r
        }
        val clip = ClipDrawable(accent, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(base, clip))
        versionCardView.background = layers
        clip.level = pct * 100   // ClipDrawable level is 0–10000
    }

    private fun resetVersionCard() {
        stopUpdateAvailableAnimation()
        val d = resources.displayMetrics.density
        versionCardView.text = BuildConfig.GIT_COMMIT
        versionCardView.background = GradientDrawable().apply {
            setColor(Color.argb(160, 0, 0, 0))
            cornerRadius = 16f * d
        }
        lastDownloadProgress = null
    }

    /** Shows a semi-transparent overlay with a progress bar, size label, and cancel button. */
    private fun showDownloadOverlay() {
        // Remove any existing overlay first.
        dismissDownloadOverlay()

        val d = resources.displayMetrics.density
        isDownloadOverlayVisible = true

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max      = 100
            progress = 0
        }
        downloadProgressBar = bar

        val title = TextView(this).apply {
            text = "Downloading…"
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cancelBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding((8 * d).toInt(), 0, 0, 0)
            setOnClickListener { downloadJob?.cancel() }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(cancelBtn)
        }

        val sizeLabel = TextView(this).apply {
            text = ""
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 14f
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        downloadLabel = sizeLabel

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 24, 24, 24))
                cornerRadius = 16f * d
            }
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            addView(headerRow)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (12 * d).toInt() })
            addView(sizeLabel)
            // Consume taps on the card so they don't hide the overlay.
            isClickable = true
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            // Tap outside the card → hide overlay (download continues).
            setOnClickListener { hideDownloadOverlayKeepDownloading() }
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

    /** Hides the overlay but keeps the download running; progress moves to the version card. */
    private fun hideDownloadOverlayKeepDownloading() {
        dismissDownloadOverlay()
        // Push current progress into the version card.
        lastDownloadProgress?.let { updateDownloadUi(it) }
    }

    /** Removes the overlay views and resets overlay-related references. */
    private fun dismissDownloadOverlay() {
        downloadOverlay?.let { (window.decorView as? ViewGroup)?.removeView(it) }
        downloadOverlay     = null
        downloadProgressBar = null
        downloadLabel       = null
        isDownloadOverlayVisible = false
    }

    /**
     * Animate the camera to [target].
     *
     * [zoom] defaults to the current camera zoom.
     * [tilt] defaults to the current camera tilt — pass a value to override
     *   (e.g. 45.0 for NAVIGATE mode).
     * [padding] defaults to the current camera padding — pass a value to override.
     * [enableZoom] controls whether the two-phase zoom-out/zoom-in animation plays.
     *   Pass `false` to pan without changing zoom (e.g. search-result pan or mode
     *   transitions that should not zoom).
     * [onFinish] is invoked when the animation completes (or is cancelled).
     *
     * When [enableZoom] is true and the target is beyond FLYTO_THRESHOLD_M, the
     * camera first zooms to a context level that shows the route geography, then
     * eases in to [zoom] at [target] — the same "zoom out → pan → zoom in"
     * pattern used by most navigation apps.  For nearby targets the camera eases
     * directly without the intermediate zoom.
     *
     * When [enableZoom] is false a single 600 ms animation is used regardless of
     * distance, keeping the current zoom level throughout.
     */
    private fun flyToLocation(
        m: MapLibreMap,
        target: LatLng,
        zoom: Double = m.cameraPosition.zoom,
        tilt: Double = m.cameraPosition.tilt,
        padding: DoubleArray? = null,
        enableZoom: Boolean = true,
        onFinish: (() -> Unit)? = null,
    ) {
        val resolvedPadding = padding ?: m.cameraPosition.padding ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val from     = m.cameraPosition.target ?: run { onFinish?.invoke(); return }
        val distance = from.distanceTo(target)

        // Build the final CameraPosition (used by both the direct and phase-2 paths).
        fun finalPos() = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .tilt(tilt)
            .bearing(m.cameraPosition.bearing)
            .padding(resolvedPadding)
            .build()

        if (!enableZoom || distance < FLYTO_THRESHOLD_M) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(finalPos()),
                600,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() { onFinish?.invoke() }
                    override fun onCancel() { onFinish?.invoke() }
                },
            )
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
                    // Phase 2: ease into the final zoom, tilt, and padding.
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(finalPos()),
                        500,
                        object : MapLibreMap.CancelableCallback {
                            override fun onFinish() { onFinish?.invoke() }
                            override fun onCancel() { onFinish?.invoke() }
                        },
                    )
                }
                override fun onCancel() { onFinish?.invoke() }
            },
        )
    }

    /**
     * Fly to the user's current GPS position (preserving zoom), then invoke [onComplete].
     *
     * Used by the Ride and Plan buttons to centre the map before the mode transition
     * so the incoming mode's camera adjustments (tilt, focal offset) animate from a
     * known position rather than from wherever the user was panning.
     *
     * Calls [onComplete] immediately if no map or last-known location is available,
     * so the mode switch always proceeds regardless of GPS state.
     */
    private fun flyToCurrentLocationThen(onComplete: () -> Unit) {
        val m   = map
        val loc = m?.locationComponent?.lastKnownLocation
        if (m == null || loc == null) { onComplete(); return }
        flyToLocation(m, LatLng(loc.latitude, loc.longitude), onFinish = onComplete)
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

        s.addSource(GeoJsonSource(SOURCE_DRAG_LINE, collection, GeoJsonOptions().withSynchronousUpdate(true)))

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

    // ── Benchmark ─────────────────────────────────────────────────────────────

    private data class FpsStats(val avg: Float, val min: Float, val max: Float)

    private data class BenchmarkResult(
        val zoom: Int,
        val glStats: FpsStats,
        val mainAvg: Float, val mainMin: Float, val mainMax: Float,
        val dtAvgMs: Long, val dtMinMs: Long, val dtMaxMs: Long,
    )

    /**
     * Computes per-second FPS windows from a list of GL frame timestamps (nanoseconds).
     * Returns average, min, and max across all complete 1-second windows.
     */
    private fun computeFpsStats(nanos: List<Long>): FpsStats {
        if (nanos.size < 2) return FpsStats(0f, 0f, 0f)
        val windowFps = mutableListOf<Float>()
        var windowStart = nanos.first()
        var count = 0
        for (t in nanos.drop(1)) {
            count++
            val elapsed = t - windowStart
            if (elapsed >= 1_000_000_000L) {
                windowFps.add(count * 1_000_000_000f / elapsed)
                count = 0
                windowStart = t
            }
        }
        if (windowFps.isEmpty()) {
            val totalFps = (nanos.size - 1) * 1_000_000_000f / (nanos.last() - nanos.first())
            return FpsStats(totalFps, totalFps, totalFps)
        }
        return FpsStats(
            avg = windowFps.average().toFloat(),
            min = windowFps.minOrNull() ?: 0f,
            max = windowFps.maxOrNull() ?: 0f,
        )
    }

    /**
     * Closes the menu and runs pan benchmarks at zoom levels 8, 10, 14, 16.
     *
     * Uses central Tokyo (Shinjuku) as the benchmark location — one of the
     * densest OSM-mapped areas globally (roads, buildings, transit, POIs).
     * Camera is tilted 45° so the perspective frustum covers more geometry,
     * maximising rendering load.
     *
     * For each zoom level:
     *  1. Evicts the tile disk cache so tiles must be fetched fresh.
     *  2. Moves the camera to Shinjuku at the target zoom with 45° tilt.
     *  3. Pans east continuously for 5 seconds while collecting GL FPS and
     *     main-thread FPS / frame-dt metrics.
     *
     * Results are shown in a custom overlay when all four runs complete.
     */
    private fun startBenchmark() {
        val m = map ?: return
        benchmarkJob?.cancel()
        closeMenu()
        showBenchmarkProgressOverlay()

        benchmarkJob = lifecycleScope.launch {
            val results = mutableListOf<BenchmarkResult>()

            // Central Tokyo / Shinjuku — extremely dense OSM data.
            val startTarget = LatLng(35.6896, 139.7006)
            val benchTilt = 45.0

            for (zoom in listOf(8, 10, 14, 16)) {
                updateBenchmarkStatus("Zoom $zoom / 16…")

                // Clear tile cache on IO thread.
                withContext(Dispatchers.IO) { TileCache.clearCache() }

                // Move to start position at this zoom with 45° tilt (instant).
                m.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(startTarget)
                        .zoom(zoom.toDouble())
                        .tilt(benchTilt)
                        .build()
                ))
                delay(800)

                // Prepare collectors.
                val glNanos = mutableListOf<Long>()
                benchmarkGlFrameNanos = glNanos

                val glListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    benchmarkGlFrameNanos?.add(System.nanoTime())
                }
                mapView.addOnDidFinishRenderingFrameListener(glListener)

                val mainFpsSamples = mutableListOf<Int>()
                val dtMsSamples    = mutableListOf<Long>()
                val sampleJob = launch {
                    while (isActive) {
                        delay(200)
                        mainFpsSamples.add(osdLastFps)
                        dtMsSamples.add(osdLastDtMs)
                    }
                }

                // Pan east continuously for 5 seconds in 50ms steps.
                // Target 600 logical pixels/second. In web-mercator with 512px
                // tiles, one logical pixel = 360 / (512 * 2^zoom) degrees of
                // longitude (independent of latitude).
                val degPerPx = 360.0 / (512.0 * Math.pow(2.0, zoom.toDouble()))
                val degPerStep = 600.0 * degPerPx * 0.05  // 50ms step
                val benchEnd = System.currentTimeMillis() + 5_000L
                var lng = startTarget.longitude
                while (System.currentTimeMillis() < benchEnd) {
                    lng += degPerStep
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(startTarget.latitude, lng))
                                .zoom(zoom.toDouble())
                                .tilt(benchTilt)
                                .build()
                        ),
                        100,
                    )
                    delay(50)
                }

                sampleJob.cancel()
                mapView.removeOnDidFinishRenderingFrameListener(glListener)
                benchmarkGlFrameNanos = null

                val mainAvg = if (mainFpsSamples.isEmpty()) 0f else mainFpsSamples.average().toFloat()
                val mainMin = mainFpsSamples.minOrNull()?.toFloat() ?: 0f
                val mainMax = mainFpsSamples.maxOrNull()?.toFloat() ?: 0f
                val dtAvg   = if (dtMsSamples.isEmpty()) 0L else dtMsSamples.average().toLong()
                val dtMin   = dtMsSamples.minOrNull() ?: 0L
                val dtMax   = dtMsSamples.maxOrNull() ?: 0L

                results.add(
                    BenchmarkResult(
                        zoom     = zoom,
                        glStats  = computeFpsStats(glNanos),
                        mainAvg  = mainAvg,
                        mainMin  = mainMin,
                        mainMax  = mainMax,
                        dtAvgMs  = dtAvg,
                        dtMinMs  = dtMin,
                        dtMaxMs  = dtMax,
                    )
                )
            }

            dismissBenchmarkProgressOverlay()
            showBenchmarkResultsOverlay(results)
        }
    }

    private fun showBenchmarkProgressOverlay() {
        dismissBenchmarkProgressOverlay()
        val d = resources.displayMetrics.density

        val statusLabel = TextView(this).apply {
            text = "Starting benchmark…"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        benchmarkStatusLabel = statusLabel

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.argb(220, 180, 180, 255))
            textSize = 15f
            setPadding(0, (12 * d).toInt(), 0, 0)
            setOnClickListener {
                benchmarkJob?.cancel()
                dismissBenchmarkProgressOverlay()
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 24, 24, 24))
                cornerRadius = 16f * d
            }
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            addView(statusLabel)
            addView(cancelBtn)
            isClickable = true
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            addView(container, FrameLayout.LayoutParams(
                (280 * d).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        benchmarkOverlay = overlay
        (window.decorView as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun updateBenchmarkStatus(msg: String) {
        benchmarkStatusLabel?.text = msg
    }

    private fun dismissBenchmarkProgressOverlay() {
        benchmarkOverlay?.let { (window.decorView as? ViewGroup)?.removeView(it) }
        benchmarkOverlay     = null
        benchmarkStatusLabel = null
    }

    private fun formatBenchmarkResults(results: List<BenchmarkResult>): String {
        val sb = StringBuilder()
        sb.appendLine("aWayToGo Benchmark Results")
        sb.appendLine("Build: ${BuildConfig.GIT_COMMIT}  (${BuildConfig.BUILD_TIME})")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
        sb.appendLine()
        for (r in results) {
            sb.appendLine("Zoom ${r.zoom}")
            sb.appendLine("  GL:   avg ${"%.0f".format(r.glStats.avg)}  min ${"%.0f".format(r.glStats.min)}  max ${"%.0f".format(r.glStats.max)} fps")
            sb.appendLine("  Main: avg ${"%.0f".format(r.mainAvg)}  min ${"%.0f".format(r.mainMin)}  max ${"%.0f".format(r.mainMax)} fps")
            sb.appendLine("  dt:   avg ${r.dtAvgMs}  min ${r.dtMinMs}  max ${r.dtMaxMs} ms")
        }
        return sb.toString()
    }

    private fun shareBenchmarkResults(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "aWayToGo Benchmark Results")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share benchmark results"))
    }

    private fun saveBenchmarkResults(text: String) {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(getExternalFilesDir(null), "benchmark_$ts.txt")
        file.writeText(text)
        android.widget.Toast.makeText(
            this, "Saved to ${file.name}", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun showBenchmarkResultsOverlay(results: List<BenchmarkResult>) {
        val d = resources.displayMetrics.density

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Benchmark Results"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * d).toInt() })

        for (r in results) {
            val zoomLabel = TextView(this).apply {
                text = "Zoom ${r.zoom}"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val glLine = TextView(this).apply {
                text = "  GL:   avg ${"%.0f".format(r.glStats.avg)}  min ${"%.0f".format(r.glStats.min)}  max ${"%.0f".format(r.glStats.max)} fps"
                setTextColor(Color.argb(220, 200, 220, 255))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val mainLine = TextView(this).apply {
                text = "  Main: avg ${"%.0f".format(r.mainAvg)}  min ${"%.0f".format(r.mainMin)}  max ${"%.0f".format(r.mainMax)} fps"
                setTextColor(Color.argb(220, 200, 255, 200))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val dtLine = TextView(this).apply {
                text = "  dt:   avg ${r.dtAvgMs}  min ${r.dtMinMs}  max ${r.dtMaxMs} ms"
                setTextColor(Color.argb(200, 220, 220, 220))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            content.addView(zoomLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() })
            content.addView(glLine)
            content.addView(mainLine)
            content.addView(dtLine)
        }

        val resultText = formatBenchmarkResults(results)

        val shareBtn = TextView(this).apply {
            text = "Share"
            setTextColor(Color.argb(220, 180, 180, 255))
            textSize = 15f
            gravity = Gravity.CENTER
        }

        val saveBtn = TextView(this).apply {
            text = "Save"
            setTextColor(Color.argb(220, 180, 180, 255))
            textSize = 15f
            gravity = Gravity.CENTER
        }

        val closeBtn = TextView(this).apply {
            text = "Close"
            setTextColor(Color.argb(220, 180, 180, 255))
            textSize = 15f
            gravity = Gravity.CENTER
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(shareBtn, lp)
            addView(saveBtn, lp)
            addView(closeBtn, lp)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 24, 24, 24))
                cornerRadius = 16f * d
            }
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
            addView(
                ScrollView(this@MapActivity).apply { addView(content) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (300 * d).toInt(),
                ),
            )
            addView(buttonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * d).toInt() })
            isClickable = true
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            addView(container, FrameLayout.LayoutParams(
                (320 * d).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }

        shareBtn.setOnClickListener { shareBenchmarkResults(resultText) }
        saveBtn.setOnClickListener { saveBenchmarkResults(resultText) }
        closeBtn.setOnClickListener {
            (window.decorView as? ViewGroup)?.removeView(overlay)
        }
        overlay.setOnClickListener {
            (window.decorView as? ViewGroup)?.removeView(overlay)
        }

        (window.decorView as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
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
        checkUpdateIfDue()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        remoteControl.unregister()
        mapView.onPause()
        stopUpdateAvailableAnimation()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        animBag.cancelAll()
        updateAnimJob?.cancel()
        benchmarkJob?.cancel()
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

    /**
     * Called instead of recreating the Activity when the device is rotated.
     *
     * Declared in AndroidManifest via configChanges="orientation|screenSize|...".
     * The Activity (and MapLibre's GL surface) are kept alive; only screen-dependent
     * state needs updating.
     *
     * Rotation transition:
     *   1. A black overlay is placed over the window before the layout reflow, hiding
     *      the brief black frame emitted by SurfaceView while its GL viewport resizes.
     *   2. super.onConfigurationChanged() triggers the reflow synchronously.
     *   3. The overlay fades out over 300 ms, cross-fading from the old orientation
     *      into the new one without any jarring flash or redraw artefact.
     *
     * Additional bookkeeping:
     *   - System bars: re-hidden in case Android briefly restores them during rotation.
     *   - panelFullHeight: invalidated — it was measured against the old screen height.
     *   - Camera padding: re-applied with the new screen height (Navigate mode uses
     *     screenH * 0.8 for the focal-point offset).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        // ── 1. Add the black overlay BEFORE the reflow so the resize is hidden. ──
        val decorView = window.decorView as ViewGroup
        rotationOverlay?.let { decorView.removeView(it) }
        val overlay = View(this).apply { setBackgroundColor(Color.BLACK) }
        rotationOverlay = overlay
        decorView.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ── 2. Trigger the layout reflow. ─────────────────────────────────────
        super.onConfigurationChanged(newConfig)

        // ── 3. Post-reflow bookkeeping. ───────────────────────────────────────

        // Re-hide system bars (rotation can cause them to flash back for one frame).
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Invalidate the panel height cache — screen height (the AT_MOST bound) changed.
        panelFullHeight = -1

        // Re-apply camera padding with the new screen height, no animation so the
        // map does not sweep during the transition.
        applyCameraForMode(viewModel.uiState.value.mode, animated = false)

        // ── 4. Fade the overlay out, revealing the re-laid-out UI. ───────────
        // A 50 ms start-delay lets the first re-drawn frame settle before we
        // begin fading, so there is no partial-black-frame visible at the edges.
        overlay.animate()
            .alpha(0f)
            .setDuration(300)
            .setStartDelay(50)
            .withEndAction {
                decorView.removeView(overlay)
                if (rotationOverlay === overlay) rotationOverlay = null
            }
            .start()
    }
}

