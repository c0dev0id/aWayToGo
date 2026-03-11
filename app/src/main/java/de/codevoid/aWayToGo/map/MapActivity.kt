package de.codevoid.aWayToGo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import de.codevoid.aWayToGo.map.ui.SearchOverlayResult
import de.codevoid.aWayToGo.map.ui.buildEditTopBar
import de.codevoid.aWayToGo.map.ui.buildExploreBottomBar
import de.codevoid.aWayToGo.map.ui.buildMenuPanel
import de.codevoid.aWayToGo.map.ui.buildNavigateOverlay
import de.codevoid.aWayToGo.map.ui.buildSearchOverlay
import de.codevoid.aWayToGo.search.GeocodingRepository
import de.codevoid.aWayToGo.search.RecentSearches
import de.codevoid.aWayToGo.search.SearchResult
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import de.codevoid.aWayToGo.update.AppUpdater
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// ── Drag line style layer / source IDs ───────────────────────────────────────
// Two LineLayer instances (casing behind, fill in front) produce the outlined
// look. A SymbolLayer with line-center placement overlays the distance label.
private const val SOURCE_DRAG_LINE        = "drag-line"
private const val LAYER_DRAG_LINE_CASING  = "drag-line-casing"
private const val LAYER_DRAG_LINE_FILL    = "drag-line-fill"
private const val LAYER_DRAG_LINE_LABEL   = "drag-line-label"

private const val SOURCE_SEARCH_PIN = "search-pin-src"
private const val LAYER_SEARCH_PIN  = "search-pin-circle"

private const val TILT_3D = 60.0

// Speed thresholds for adaptive compass ↔ GPS heading mode.
// Hysteresis avoids mode flipping at a stop light: higher threshold to enter
// GPS mode, lower to return to compass mode.
//   1.5 m/s ≈  5.4 km/h  → start rotating map with GPS course
//   0.5 m/s ≈  1.8 km/h  → return to compass heading when nearly stopped
private const val SPEED_TO_GPS_MS     = 1.5f
private const val SPEED_TO_COMPASS_MS = 0.5f

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

    // ── Mode UI views ─────────────────────────────────────────────────────────
    // Three horizontal bar views that make up the hamburger icon.
    // Stored separately so each can be rotated at a different speed during open/close.
    // All three share the same rotation pivot: the centre of the 64×64dp button area.
    private lateinit var hamburgerBars: Array<View>
    private lateinit var menuPanel: View
    private lateinit var menuDismissOverlay: View
    private var panelFullHeight = -1               // measured on first open; -1 = not yet measured
    private var menuAnimator: ValueAnimator? = null
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

    private val appUpdater by lazy { AppUpdater(this) }

    // ── Search ────────────────────────────────────────────────────────────────
    private lateinit var searchOverlayResult: SearchOverlayResult
    private val geocoding = GeocodingRepository()
    private lateinit var recentSearches: RecentSearches

    // Overlay used to animate screen rotation — covers the SurfaceView's brief
    // black resize frame, then fades out to reveal the re-laid-out UI.
    private var rotationOverlay: View? = null

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hasLocationPermission = false

    // True while GPS speed exceeds SPEED_TO_GPS_MS; false below SPEED_TO_COMPASS_MS.
    // Drives the adaptive compass ↔ GPS camera/render mode selection.
    private var isMoving = false

    // Owns all D-pad / joystick / zoom-key state and drives per-frame camera updates.
    private val panController = PanController(onEnterPanningMode = { enterPanningMode() })

    // ── Drag line anchor ──────────────────────────────────────────────────────
    // When set, the Choreographer loop continuously updates the drag line so it
    // connects the user's current GPS position with this anchored target.
    private var dragLineAnchor: LatLng? = null

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

            // ── Adaptive heading (compass ↔ GPS) ───────────────────────────────
            // Check GPS speed and switch CameraMode/RenderMode when the user
            // crosses the moving/stationary threshold.  O(1) no-op most frames.
            updateMovingState()

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
            textSize = 20f
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
            setOnClickListener { exitPanningMode() }
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

        // Search overlay — bottom-anchored panel that fades in/out over the map.
        // Added before the menu dismiss overlay so the menu still sits on top.
        recentSearches = RecentSearches(getSharedPreferences("search", MODE_PRIVATE))
        searchOverlayResult = buildSearchOverlay(
            context       = this,
            recentSearches = recentSearches,
            onClose       = { closeSearch() },
            onSearch      = { query -> performSearch(query) },
            onResultClick = { result -> onSearchResultSelected(result) },
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
        // The panel starts as INVISIBLE (focusable but not drawn) and is promoted to
        // VISIBLE on the first animation frame — this ensures the panel never appears
        // at the wrong (pre-keyboard) position before jumping up.
        // WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE = 1 per stable AndroidX API.
        // Kotlin cannot resolve the constant via qualified name in this core version, so we
        // use the raw int value to avoid an "Unresolved reference" compile error.
        @Suppress("WrongConstant")
        val imeDispatchMode = 1 // DISPATCH_MODE_CONTINUE
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(imeDispatchMode) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    (searchOverlayResult.root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.bottomMargin = imeBottom
                        searchOverlayResult.root.layoutParams = lp
                    }
                    // Reveal the panel on the first frame the keyboard starts to move,
                    // so the panel and keyboard animate up together from the start.
                    if (imeBottom > 0
                        && viewModel.uiState.value.isSearchOpen
                        && searchOverlayResult.root.visibility == View.INVISIBLE
                    ) {
                        searchOverlayResult.root.visibility = View.VISIBLE
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    // Close search if the user dismissed the keyboard manually.
                    // Guard: hideKeyboard() (called by hideSearchOverlay) sets
                    // isSearchOpen = false before this fires, so there is no loop.
                    val imeBottom = ViewCompat.getRootWindowInsets(root)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    if (imeBottom == 0 && viewModel.uiState.value.isSearchOpen) {
                        closeSearch()
                    }
                }
            },
        )

        // Dismiss overlay — full-screen transparent tap target that closes the menu.
        // Added last so it sits above all other views when visible.
        menuDismissOverlay = View(this).apply {
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
        buildMenuPanel(
            context       = this,
            onToggleMenu  = { toggleMenu() },
        ).also { result ->
            menuPanel      = result.root
            hamburgerBars  = result.hamburgerBars
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
            cameraMode = trackingCameraMode()
            renderMode = trackingRenderMode()
        }

        m.locationComponent.lastKnownLocation?.let { loc ->
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    14.0,
                ),
                600,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() { reassertTrackingMode() }
                    override fun onCancel() { reassertTrackingMode() }
                },
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
                    // Outside panning mode: reset map bearing to north.
                    if (viewModel.uiState.value.isInPanningMode) {
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
                            400,
                            object : MapLibreMap.CancelableCallback {
                                override fun onFinish() { reassertTrackingMode() }
                                override fun onCancel() { reassertTrackingMode() }
                            },
                        )
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
                    } else {
                        // Outside panning mode: toggle GPS camera tracking.
                        m.locationComponent.cameraMode =
                            if (m.locationComponent.cameraMode == CameraMode.NONE)
                                trackingCameraMode()
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
                        400,
                        object : MapLibreMap.CancelableCallback {
                            override fun onFinish() { reassertTrackingMode() }
                            override fun onCancel() { reassertTrackingMode() }
                        },
                    )
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

    // ── Adaptive heading (compass ↔ GPS) ──────────────────────────────────────

    /**
     * [CameraMode] that both follows the user's position AND rotates the map:
     * - Stationary ([isMoving] == false): [CameraMode.TRACKING_COMPASS] — the map
     *   heading follows the device compass so "up" is wherever you're pointing.
     * - Moving    ([isMoving] == true):  [CameraMode.TRACKING_GPS] — the map heading
     *   follows the GPS course-over-ground so "up" is the direction of travel.
     */
    private fun trackingCameraMode() =
        if (isMoving) CameraMode.TRACKING_GPS else CameraMode.TRACKING_COMPASS

    /**
     * [RenderMode] consistent with [trackingCameraMode]: the user location puck
     * always shows a directional indicator, pointing either compass or GPS bearing.
     */
    private fun trackingRenderMode() =
        if (isMoving) RenderMode.GPS else RenderMode.COMPASS

    /**
     * Check GPS speed from the most recent location fix and, if the moving/stationary
     * state has changed, switch [CameraMode] and [RenderMode] accordingly.
     *
     * Called every Choreographer frame but is an O(1) no-op in the common case
     * (no state change).  Hysteresis ([SPEED_TO_GPS_MS] / [SPEED_TO_COMPASS_MS])
     * prevents mode flipping when the user is stopped at a traffic light with a
     * speed reading hovering near zero.
     *
     * Does nothing while panning or in EDIT mode — the location component's
     * camera mode is [CameraMode.NONE] in those states and must not be overridden.
     */
    @SuppressLint("MissingPermission")
    private fun updateMovingState() {
        val uiState = viewModel.uiState.value
        if (uiState.isInPanningMode || uiState.mode == AppMode.EDIT) return

        val lc = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        if (lc.cameraMode == CameraMode.NONE) return   // user manually detached tracking

        val loc   = lc.lastKnownLocation ?: return
        val speed = if (loc.hasSpeed()) loc.speed else 0f

        // Hysteresis: once moving, require a lower speed before switching back,
        // so a momentary slow-down at lights doesn't drop back to compass.
        val nowMoving = if (isMoving) speed > SPEED_TO_COMPASS_MS else speed > SPEED_TO_GPS_MS
        if (nowMoving == isMoving) return

        isMoving = nowMoving
        lc.cameraMode = trackingCameraMode()
        lc.renderMode = trackingRenderMode()
    }

    /**
     * Re-establishes the correct [CameraMode] and [RenderMode] on the
     * [LocationComponent] after any developer-initiated [MapLibreMap.animateCamera]
     * or [MapLibreMap.moveCamera] call.
     *
     * In MapLibre, programmatic camera moves can internally disrupt the
     * LocationComponent's camera-tracking state even though
     * [LocationComponent.cameraMode] still reports the correct value.  Calling this
     * method at the end of every animation re-arms tracking so rotation and
     * position-following continue to work.
     *
     * No-ops when tracking is deliberately disabled (panning mode, EDIT mode, or
     * [CameraMode.NONE]).
     */
    private fun reassertTrackingMode() {
        val uiState = viewModel.uiState.value
        if (uiState.isInPanningMode || uiState.mode == AppMode.EDIT) return
        val lc = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        if (lc.cameraMode == CameraMode.NONE) return
        lc.cameraMode = trackingCameraMode()
        lc.renderMode  = trackingRenderMode()
    }

    // ── Mode management ───────────────────────────────────────────────────────

    /**
     * Apply (or animate) camera tilt and vertical focal-point offset for [mode].
     *
     * Navigate mode:
     *   - 30° tilt — gives a perspective view of the road ahead.
     *   - Top padding = 60 % of screen height — moves the GPS anchor point to
     *     80 % from the top (30 % lower than the default screen centre), so
     *     more of the road ahead is visible above the user's position.
     *
     * All other modes: flat (0° tilt), no padding.
     *
     * Both tilt and padding are delivered as a single [CameraPosition] update
     * so MapLibre interpolates them together in one smooth 400 ms animation.
     */
    private fun applyCameraForMode(mode: AppMode, animated: Boolean) {
        val m = map ?: return
        val screenH = resources.displayMetrics.heightPixels

        val targetTilt: Double
        val topPad: Double
        when (mode) {
            AppMode.NAVIGATE -> {
                targetTilt = 45.0
                topPad     = screenH * 0.8   // GPS dot at ~90 % from top = 40 % below centre
            }
            else -> {
                targetTilt = 0.0
                topPad     = 0.0
            }
        }

        val cur    = m.cameraPosition
        val newPos = CameraPosition.Builder()
            .target(cur.target)
            .zoom(cur.zoom)
            .bearing(cur.bearing)
            .tilt(targetTilt)
            .padding(doubleArrayOf(0.0, topPad, 0.0, 0.0))
            .build()

        if (animated) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(newPos),
                400,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() { reassertTrackingMode() }
                    override fun onCancel() { reassertTrackingMode() }
                },
            )
        } else {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(newPos))
            reassertTrackingMode()
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
                val latch = intArrayOf(3)
                fun check() { if (--latch[0] == 0) slideIn() }

                menuPanel.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        menuPanel.visibility   = View.GONE
                        menuPanel.translationX = 0f
                        check()
                    }.start()
                myLocationButton.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        myLocationButton.visibility   = View.GONE
                        myLocationButton.translationX = 0f
                        check()
                    }.start()
                exploreBottomBar.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction {
                        exploreBottomBar.visibility   = View.GONE
                        exploreBottomBar.translationY = 0f
                        check()
                    }.start()
            }

            AppMode.NAVIGATE -> {
                // Banner and STOP leave simultaneously; hide the overlay container when done.
                val latch = intArrayOf(2)
                fun check() {
                    if (--latch[0] == 0) {
                        navigateOverlay.visibility = View.GONE
                        slideIn()
                    }
                }
                navigateBanner.animate().translationY(-h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateBanner.translationY = 0f; check() }.start()
                navigateStopBtn.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateStopBtn.translationY = 0f; check() }.start()
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
        val modeChanged    = old?.mode != new.mode
        val panningChanged = old?.isInPanningMode != new.isInPanningMode
        val menuChanged    = old?.isMenuOpen != new.isMenuOpen
        val searchChanged  = old?.isSearchOpen != new.isSearchOpen

        // ── Crosshair ──────────────────────────────────────────────────────────
        // EDIT always shows the crosshair (it acts as the placement cursor).
        // Otherwise the crosshair is shown only while the user is manually panning.
        crosshairView.visibility = when {
            new.mode == AppMode.EDIT || new.isInPanningMode -> View.VISIBLE
            else                                            -> View.GONE
        }

        // ── Camera tracking ────────────────────────────────────────────────────
        // Only update when something that affects tracking actually changed.
        if (modeChanged || panningChanged) {
            when {
                // Entering NAVIGATE always re-enables GPS tracking (may override panning).
                modeChanged && new.mode == AppMode.NAVIGATE -> {
                    map?.locationComponent?.cameraMode = trackingCameraMode()
                    map?.locationComponent?.renderMode  = trackingRenderMode()
                }

                // Entering EDIT always disables tracking (panning state also cleared by ViewModel).
                modeChanged && new.mode == AppMode.EDIT ->
                    map?.locationComponent?.cameraMode = CameraMode.NONE

                // Starting to pan (any non-EDIT mode): suspend tracking.
                panningChanged && new.isInPanningMode ->
                    map?.locationComponent?.cameraMode = CameraMode.NONE

                // Stopping panning: re-enable tracking and fly back to current position.
                panningChanged && !new.isInPanningMode && old?.isInPanningMode == true -> {
                    val m   = map
                    val loc = m?.locationComponent?.lastKnownLocation
                    if (m != null && loc != null) {
                        m.locationComponent.cameraMode = trackingCameraMode()
                        m.locationComponent.renderMode  = trackingRenderMode()
                        flyToLocation(m, LatLng(loc.latitude, loc.longitude))
                    }
                }

                // Entering EXPLORE from NAVIGATE/EDIT: tracking state is unchanged —
                // the user controls it via the my-location button.
            }
        }

        // ── Search overlay ─────────────────────────────────────────────────────
        if (searchChanged) {
            if (new.isSearchOpen) showSearchOverlay() else hideSearchOverlay()
        }

        // ── Menu animation ─────────────────────────────────────────────────────
        // Skip on the first render (old == null): the panel already starts at button size.
        if (old != null && menuChanged) {
            if (new.isMenuOpen) {
                runOpenMenuAnimation()
            } else {
                // Close instantly when a mode change is also happening so the menu
                // does not fight with the mode-transition slide animation.
                runCloseMenuAnimation(instant = modeChanged)
            }
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
    }

    private fun toggleMenu() { viewModel.toggleMenu() }
    private fun openMenu()   { viewModel.openMenu() }
    private fun closeMenu()  { viewModel.closeMenu() }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun openSearch()  { viewModel.openSearch() }
    private fun closeSearch() { viewModel.closeSearch() }

    private fun showSearchOverlay() {
        // Results are intentionally NOT cleared here — the previous result list
        // (if any) remains visible so re-opening the search shows the last results.
        //
        // Start INVISIBLE (not GONE) so the search field can receive focus and
        // trigger the keyboard.  The IME animation callback promotes the panel to
        // VISIBLE on the very first animation frame, so it rises with the keyboard
        // rather than appearing at the bottom and then jumping up.
        searchOverlayResult.root.alpha = 1f
        searchOverlayResult.root.visibility = View.INVISIBLE
        searchOverlayResult.focusAndShowKeyboard()
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
                val results = geocoding.search(query)
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

        s.addSource(GeoJsonSource(SOURCE_SEARCH_PIN, collection))
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
        menuDismissOverlay.visibility = View.VISIBLE
        menuAnimator?.cancel()

        val d      = resources.displayMetrics.density
        val panelW = (280 * d).toInt()
        val panelH = getOrMeasurePanelHeight()
        val lp     = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
        val startH    = lp.height
        // Capture each bar's current rotation so the animator can interpolate from
        // wherever the animation was interrupted (e.g. rapid open→close→open).
        val barStartR = FloatArray(3) { hamburgerBars[it].rotation }
        // Top bar is fastest: it reaches 90° at ~65% of the animation, giving a
        // staggered cascade where bar 0 leads and bar 2 trails.
        val openSpeeds = floatArrayOf(1.4f, 1.2f, 1.0f)

        menuAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
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
                }
            }
            start()
        }
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
            hamburgerBars.forEach { it.rotation = 0f }
            menuDismissOverlay.visibility = View.GONE
            return
        }

        val startW    = lp.width
        val startH    = lp.height
        val barStartR = FloatArray(3) { hamburgerBars[it].rotation }
        // Reverse of open: bottom bar now fastest, top bar slowest.
        val closeSpeeds = floatArrayOf(1.0f, 1.2f, 1.4f)

        menuAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 180
            interpolator = AccelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (btnSz - startW) * t).toInt()
                lp.height = (startH + (btnSz - startH) * t).toInt()
                menuPanel.layoutParams = lp
                hamburgerBars.forEachIndexed { i, bar ->
                    val p = (t * closeSpeeds[i]).coerceAtMost(1f)
                    bar.rotation = barStartR[i] + (0f - barStartR[i]) * p
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    menuDismissOverlay.visibility = View.GONE
                }
            })
            start()
        }
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

    // ── Self-update ───────────────────────────────────────────────────────────

    /**
     * Tap handler for the version card.
     *
     * Delegates network and file I/O to [AppUpdater]; this method only manages
     * the card label and the download progress overlay.
     */
    private fun onVersionCardTapped() {
        // Ignore taps while already busy (text is not the commit hash).
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

            showDownloadOverlay()
            try {
                val apk = appUpdater.downloadApk(apkUrl) { pct ->
                    downloadProgressBar?.progress = pct
                }
                hideDownloadOverlay()
                appUpdater.installApk(apk)
            } catch (_: Exception) {
                hideDownloadOverlay()
                versionCardView.text = "error"
                delay(2_000)
                versionCardView.text = BuildConfig.GIT_COMMIT
            }
        }
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
            textSize = 20f
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
     * Animate the camera to [target] at [zoom].
     *
     * [zoom] defaults to the current camera zoom — callers that want a specific
     * level (e.g. re-centering from a cold state) must pass one explicitly.
     *
     * [onFinish] is invoked when the animation completes (or is cancelled).
     * Used by [flyToCurrentLocationThen] to chain a mode transition after the fly.
     *
     * If the target is within FLYTO_THRESHOLD_M the camera eases directly.
     * If farther away it first animates to a context zoom that shows enough
     * geography to orient the user, then eases in to the target — the same
     * "zoom out → pan → zoom in" pattern used by most navigation apps.
     */
    private fun flyToLocation(
        m: MapLibreMap,
        target: LatLng,
        zoom: Double = m.cameraPosition.zoom,
        onFinish: (() -> Unit)? = null,
    ) {
        val from     = m.cameraPosition.target ?: run { onFinish?.invoke(); return }
        val distance = from.distanceTo(target)

        if (distance < FLYTO_THRESHOLD_M) {
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(target, zoom),
                600,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() { reassertTrackingMode(); onFinish?.invoke() }
                    override fun onCancel() { reassertTrackingMode(); onFinish?.invoke() }
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
                    // Phase 2: ease into the final zoom level.
                    m.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(target, zoom),
                        500,
                        object : MapLibreMap.CancelableCallback {
                            override fun onFinish() { reassertTrackingMode(); onFinish?.invoke() }
                            override fun onCancel() { reassertTrackingMode(); onFinish?.invoke() }
                        },
                    )
                }
                override fun onCancel() { reassertTrackingMode(); onFinish?.invoke() }
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

