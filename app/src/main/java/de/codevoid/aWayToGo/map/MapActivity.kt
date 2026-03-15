package de.codevoid.aWayToGo.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.net.TrafficStats
import android.net.Uri
import android.provider.Settings
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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
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
import de.codevoid.aWayToGo.map.ui.AnimatorBag
import de.codevoid.aWayToGo.map.ui.SearchOverlayResult
import de.codevoid.aWayToGo.map.ui.buildEditTopBar
import de.codevoid.aWayToGo.map.ui.buildExploreBottomBar
import de.codevoid.aWayToGo.map.ui.MapLockMenuPanelResult
import de.codevoid.aWayToGo.map.ui.buildMapLockMenuPanel
import de.codevoid.aWayToGo.map.ui.MenuPanelResult
import de.codevoid.aWayToGo.map.ui.buildMenuPanel
import de.codevoid.aWayToGo.map.ui.TileGridOverlay
import android.content.ClipData
import android.content.ClipboardManager
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
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
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
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

// ── Map style URLs ────────────────────────────────────────────────────────────
private const val STYLE_OUTDOOR = "https://api.maptiler.com/maps/outdoor-v2/style.json?key="
private const val STYLE_DARK    = "https://api.maptiler.com/maps/dataviz-dark/style.json?key="
private const val SOURCE_SATELLITE  = "satellite-raster-src"
private const val LAYER_SATELLITE   = "satellite-raster-layer"
private const val SAT_TILE_TEMPLATE   = "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key="
private const val SAT_ANIMATE_MS      = 350L
private const val TILES_V3_TEMPLATE   = "https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key="

// ── Drag line style layer / source IDs ───────────────────────────────────────
// SOURCE_DRAG_LINE      — the wobbly/straight line geometry.
// SOURCE_DRAG_LINE_AUX  — two Point features: label (25 % from puck) + anchor pin.
// Layers: two-layer drop shadow, dark casing, tape-pattern fill, distance label,
//         outer pin circle, inner pin dot.
private const val SOURCE_DRAG_LINE          = "drag-line"
private const val SOURCE_DRAG_LINE_AUX      = "drag-line-aux"
private const val LAYER_DRAG_LINE_CASING    = "drag-line-casing"
private const val LAYER_DRAG_LINE_FILL      = "drag-line-fill"
private const val LAYER_DRAG_LINE_LABEL     = "drag-line-label"
private const val LAYER_DRAG_LINE_PIN_OUTER = "drag-line-pin-outer"
private const val LAYER_DRAG_LINE_PIN_INNER = "drag-line-pin-inner"
private const val LAYER_DRAG_LINE_SHADOW_OUTER = "drag-line-shadow-outer"
private const val LAYER_DRAG_LINE_SHADOW_INNER = "drag-line-shadow-inner"
private const val IMAGE_TAPE_PATTERN        = "tape-pattern"

/** Duration (ms) of the smooth anchor-slide animation when re-placing the drag line. */
private const val ANCHOR_SLIDE_MS = 500.0

private const val SOURCE_SEARCH_PIN = "search-pin-src"
private const val LAYER_SEARCH_PIN  = "search-pin-circle"

private const val LAYER_FUEL      = "fuel-stations"
private const val FUEL_ICON_ID    = "fuel-station-icon"

private const val SOURCE_TILE_GRID_LINES = "tile-grid-lines-src"
private const val LAYER_TILE_GRID_LINE   = "tile-grid-line"

private const val SOURCE_OFFLINE_BORDER  = "offline-border-src"
private const val SOURCE_OFFLINE_LABEL   = "offline-label-src"
private const val LAYER_OFFLINE_BORDER   = "offline-border-line"
private const val LAYER_OFFLINE_LABEL    = "offline-border-label"


private const val LOCATION_PERMISSION_REQUEST = 1
private const val PREFS_NAME           = "aWayToGo"
private const val PREF_TILE_SELECTION  = "tile_selection"
private const val PREF_APPLY_PENDING   = "apply_pending"

// GPS follow: outlier rejection threshold.  Early & Sykulski (2020) measured
// GPS σ ≈ 8.5 m.  We accept any fix implying less than 300 km/h — generous
// enough to cover GPS noise at low speed while rejecting true outliers.
private const val FOLLOW_MAX_SPEED_MS = 83.3   // 300 km/h in m/s

// Animation duration per GPS fix; slightly longer than the 200 ms GPS interval
// so consecutive animations overlap → continuous smooth motion.
private const val GPS_FOLLOW_ANIM_MS = 250

// Below this speed we snap to the exact fix (no animation) so noisy stationary
// GPS readings don't make the camera swing.
private const val LOW_SPEED_MS = 2.0   // ≈ 7 km/h

// Course Up bearing smoothing: EMA weight applied each GPS fix (~5 Hz).
// Chosen to give the same ~3 s smoothing time constant as 0.005 at 60 fps.
private const val BEARING_SMOOTH_ALPHA = 0.06

// Compass bearing smoothing when falling back to sensor azimuth (no GPS bearing).
// ~10× more responsive than GPS EMA since the rotation-vector sensor is already
// hardware-filtered and needs less software smoothing.
private const val COMPASS_SMOOTH_ALPHA = 0.1

private const val FLYTO_DURATION_MS = 800


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
    private lateinit var fuelStationToggleBtn: TextView
    private lateinit var myLocationButton: ImageView
    private lateinit var crosshairView: CrosshairView
    private lateinit var versionCardView: TextView
    private lateinit var fuelTooltipCard: TextView
    private lateinit var topRightContainer: android.widget.LinearLayout
    private lateinit var tileGridOverlay: TileGridOverlay
    private lateinit var tileSelectCard: TextView
    private var tileDownloadJob:   Job? = null
    @Volatile private var tileDownloadDone  = 0
    private var tileDownloadTotal = 0
    @Volatile private var tileDownloadBytes = 0L
    private var tileDownloadStartNs = 0L
    private var tileDownloadClip: ClipDrawable? = null
    private var osdLastTilePct = -1
    /** Camera position saved when entering tile-select mode; restored on exit. */
    private var tileSelectSavedCamera: CameraPosition? = null

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
    private var settingsMenuWidth = -1             // measured on first enter; -1 = not yet measured
    private var debugMenuHeight = -1               // measured on first enter; -1 = not yet measured
    private var debugMenuWidth = -1                // measured on first enter; -1 = not yet measured
    private var menuAnimator: ValueAnimator? = null
    private var settingsMenuAnimator: ValueAnimator? = null
    private var debugMenuAnimator: ValueAnimator? = null
    private var offlineMapsMenuAnimator: ValueAnimator? = null
    private var offlineMapsMenuWidth  = -1
    private var offlineMapsMenuHeight = -1
    private var mapStyleMenuAnimator: ValueAnimator? = null
    private var mapStyleModeAnimator: ValueAnimator? = null
    private var mapStyleMenuWidth  = -1
    private var mapStyleMenuHeight = -1
    private var satelliteAnimator: ValueAnimator? = null
    // Tracks all ValueAnimators so they can be cancelled together in onDestroy().
    private val animBag = AnimatorBag()

    // ── Map lock menu ──────────────────────────────────────────────────────────
    // Shown when the user long-presses the crosshair (remote CONFIRM or finger).
    // The circle arc on the crosshair animates for 450 ms (starting 50 ms after
    // press) to signal the long-press; when complete the context menu expands.
    private lateinit var mapLockMenuPanelResult: MapLockMenuPanelResult
    private lateinit var mapLockPanel: FrameLayout
    private lateinit var mapLockDismissOverlay: View
    private var mapLockMenuAnimator: ValueAnimator? = null
    private var lockRingAnimator: ValueAnimator? = null
    private lateinit var exploreBottomBar: FrameLayout
    private lateinit var navigateOverlay: FrameLayout
    private lateinit var navigateBanner: View
    private lateinit var navigateStopBtn: View
    private lateinit var editTopBar: LinearLayout

    private val viewModel: MapViewModel by viewModels()
    // Last state that was fully rendered; used to diff new vs old in renderUiState().
    private var renderedState: MapUiState? = null

    // Progress overlay shown during APK download (null when not downloading).
    private var downloadJob: Job? = null
    // non-null once the APK has been fully downloaded and is ready to install.
    private var downloadedApk: File? = null
    // drives 5-min polling when Frequent Updates is enabled.
    private var frequentUpdateJob: Job? = null

    // ── Benchmark ─────────────────────────────────────────────────────────────
    private var benchmarkJob: Job? = null
    private var benchmarkOverlay: View? = null
    private var benchmarkStatusLabel: TextView? = null
    @Volatile private var benchmarkGlFrameNanos: MutableList<Long>? = null

    private val appUpdater by lazy { AppUpdater(this) }
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

    // ── Direct 1-finger touch pan ──────────────────────────────────────────────
    // MapLibre's scroll gesture is disabled; we drive the camera directly on every
    // ACTION_MOVE pixel so there is no touch-slop delay before the map starts moving.
    private var panFlingAnimator:    ValueAnimator?  = null
    private var panVelocityTracker:  VelocityTracker? = null
    private var panTouchActive       = false
    private var panTouchLastX        = 0f
    private var panTouchLastY        = 0f
    /** True once panning mode / follow-disable has been triggered for this gesture. */
    private var panModeTriggered     = false

    // ── Drag line anchor ──────────────────────────────────────────────────────
    // When set, the Choreographer loop continuously updates the drag line so it
    // connects the user's current GPS position with this anchored target.
    private var dragLineAnchor: LatLng? = null
    // nanoTime() when the anchor was most recently placed; drives the whip animation.
    private var anchorSetTimeNs: Long = 0L
    private val dragLineAnimator = DragLineAnimator()
    // True once the whip animation has settled to a straight line; cleared on
    // every new anchor placement so the animation runs fresh each time.
    private var dragLineSettled = false

    // ── Anchor slide animation ────────────────────────────────────────────────
    // When the user places a new anchor while one already exists, the anchor
    // smoothly slides from the old position to the new one over ANCHOR_SLIDE_MS.
    private var anchorSlideFrom:    LatLng? = null
    private var anchorSlideStartNs: Long    = 0L

    // ── Follow mode ───────────────────────────────────────────────────────────
    // Last accepted GPS fix position; NaN signals "no valid fix yet".
    // Updated on each incoming GPS fix (rawLocationListener, ~5 Hz).
    private var followLastLat = Double.NaN
    private var followLastLon = Double.NaN

    // ── Course Up bearing smoothing (circular EMA) ────────────────────────────
    // Stored as sin/cos components to avoid 0°/360° wrap-around artefacts.
    // NaN = not yet initialised; reset whenever Course Up is disabled.
    private var followSmoothedSin = Double.NaN
    private var followSmoothedCos = Double.NaN

    // ── Synthetic location engine ─────────────────────────────────────────────
    // The puck is driven directly from GPS fixes in rawLocationListener.
    // Raw GPS is subscribed separately (via LocationManager) to avoid a feedback
    // loop: locationComponent → rawGpsLocation → prediction.
    private val syntheticEngine = SyntheticLocationEngine()
    private var rawGpsLocation: Location? = null
    private var locationManager: LocationManager? = null
    private val rawLocationListener = LocationListener { loc ->
        rawGpsLocation = loc

        // ── Outlier rejection ────────────────────────────────────────────────
        if (!followLastLat.isNaN()) {
            if (loc.hasSpeed() && loc.speed >= FOLLOW_MAX_SPEED_MS) return@LocationListener
            val dLat    = loc.latitude  - followLastLat
            val dLon    = loc.longitude - followLastLon
            val dMeters = sqrt(
                (dLat  * 111_000.0).let { it * it } +
                (dLon  * 111_000.0 * cos(Math.toRadians(loc.latitude))).let { it * it }
            )
            // 200 ms interval → 300 km/h = 16.67 m per fix max
            if (dMeters > FOLLOW_MAX_SPEED_MS * 0.2) return@LocationListener
        }

        // ── Accept fix ───────────────────────────────────────────────────────
        followLastLat = loc.latitude
        followLastLon = loc.longitude

        // ── OSD cache invalidation ───────────────────────────────────────────
        if (loc.accuracy != osdCachedAcc || !osdCachedHasFix) {
            osdCachedAcc    = loc.accuracy
            osdCachedHasFix = true
            osdDirty        = true
        }

        // ── Update puck ──────────────────────────────────────────────────────
        syntheticEngine.pushLocation(loc)

        // ── Bearing EMA ──────────────────────────────────────────────────────
        val m       = map ?: return@LocationListener
        val uiState = viewModel.uiState.value
        if (uiState.isCourseUpEnabled) {
            val gpsCourse = loc.takeIf { it.hasBearing() }
                ?.bearing?.let { Math.toRadians(it.toDouble()) }
            val compass   = compassBearingRad.takeUnless { it.isNaN() }?.toDouble()
            val sourceRad = gpsCourse ?: compass
            val alpha     = if (gpsCourse != null) BEARING_SMOOTH_ALPHA else COMPASS_SMOOTH_ALPHA
            if (sourceRad != null) {
                if (followSmoothedSin.isNaN()) {
                    followSmoothedSin = sin(sourceRad)
                    followSmoothedCos = cos(sourceRad)
                } else {
                    followSmoothedSin = (1.0 - alpha) * followSmoothedSin + alpha * sin(sourceRad)
                    followSmoothedCos = (1.0 - alpha) * followSmoothedCos + alpha * cos(sourceRad)
                }
            }
        }

        // ── Camera follow ────────────────────────────────────────────────────
        if (!uiState.isFollowModeActive) {
            updateCrosshairAlpha()
            return@LocationListener
        }
        val cur        = m.cameraPosition
        val gpsBearing = if (uiState.isCourseUpEnabled && !followSmoothedSin.isNaN())
            Math.toDegrees(atan2(followSmoothedSin, followSmoothedCos))
        else cur.bearing
        val targetTilt = if (uiState.mode == AppMode.NAVIGATE) 45.0 else cur.tilt
        val isMoving   = loc.hasSpeed() && loc.speed >= LOW_SPEED_MS
        val animMs     = if (isMoving) GPS_FOLLOW_ANIM_MS else 0
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .zoom(cur.zoom)
                    .bearing(gpsBearing)
                    .tilt(targetTilt)
                    .padding(cur.padding)
                    .build()
            ),
            animMs,
        )

        updateCrosshairAlpha()

        // ── Settled drag-line refresh ────────────────────────────────────────
        // While the whip animation is running doFrame pushes geometry every vsync.
        // Once settled, only GPS fixes move the puck end — update here at 5 Hz.
        if (dragLineSettled) {
            dragLineAnchor?.let { anchor ->
                setDragLine(LatLng(followLastLat, followLastLon), anchor, 0.0)
            }
        }
    }

    // ── Compass sensor ────────────────────────────────────────────────────────
    // TYPE_ROTATION_VECTOR fuses accelerometer + magnetometer + gyroscope (OS-side).
    // Its azimuth is used as the Course Up bearing source when GPS hasBearing()
    // returns false (stationary or no signal).
    private var sensorManager: SensorManager? = null
    private var compassBearingRad = Float.NaN   // azimuth in radians, -π..π

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rot    = FloatArray(9)
            val orient = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rot, event.values)
            SensorManager.getOrientation(rot, orient)
            compassBearingRad = orient[0]   // azimuth
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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

    // ── OSD value cache ───────────────────────────────────────────────────────
    private var osdCachedZoom    = -1.0
    private var osdCachedAcc     = -1f
    private var osdCachedHasFix  = false
    private var osdLastPanActive = false
    private var osdDirty         = true

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

            // ── GPS Follow ───────────────────────────────────────────────────
            // Camera follow and puck updates are now event-driven (rawLocationListener,
            // ~5 Hz).  Crosshair fade is event-driven too (see updateCrosshairAlpha).
            val m = map
            if (m != null && !followLastLat.isNaN()) {
                dragLineAnchor?.let { anchor ->
                    val isSliding       = anchorSlideFrom != null
                    val effectiveAnchor = slideAnchor() ?: anchor
                    if (!dragLineSettled || isSliding) {
                        setDragLine(LatLng(followLastLat, followLastLon), effectiveAnchor, frameTimeNanos / 1_000_000_000.0)
                    }
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
                osdDirty  = true   // fps and net-rate values just changed
            }

            val panActive = panSpeed > 0f
            if (panActive != osdLastPanActive) { osdLastPanActive = panActive; osdDirty = true }
            if (panActive) osdDirty = true   // pan speed value changes every frame while panning

            if (osdView.visibility == View.VISIBLE && osdDirty) {
                osdDirty = false
                val zoom    = osdCachedZoom.takeIf { it >= 0 } ?: (map?.cameraPosition?.zoom ?: 0.0)
                val panLine = if (osdLastPanActive) "\npan  ${"%.0f".format(panSpeed)} px/s" else ""
                osdView.text = buildString {
                    append("fps  $osdLastFps  dt:${osdLastDtMs}ms\n")
                    append("zoom ${"%.1f".format(zoom)}  gl_fps ${"%.0f".format(osdGlFps)}\n")
                    append("net  rx:${osdRxRate / 1024}kB/s  tx:${osdTxRate / 1024}kB/s\n")
                    append("gps  fix:${if (osdCachedHasFix) "Y" else "N"}  acc:${"%.0f".format(osdCachedAcc)}m")
                    if (panLine.isNotEmpty()) append(panLine)
                }
            }

            // ── Tile download progress (vsync-driven) ───────────────────────────
            val dlTotal = tileDownloadTotal
            if (tileDownloadJob?.isActive == true && dlTotal > 0) {
                val done  = tileDownloadDone
                val bytes = tileDownloadBytes
                val pct   = (done * 100 / dlTotal).coerceIn(0, 100)
                if (pct != osdLastTilePct) {
                    osdLastTilePct          = pct
                    tileDownloadClip?.level = pct * 100
                    val elapsedS  = (frameTimeNanos - tileDownloadStartNs) / 1_000_000_000.0
                    val rate      = if (elapsedS > 0.5) bytes / elapsedS else 0.0
                    val rateText  = if (rate > 0) " (%.1fMB/s)".format(rate / (1024.0 * 1024.0)) else ""
                    tileSelectCard.text = "$done/$dlTotal$rateText"
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

        // Restore debug settings persisted from the previous session.
        if (prefs.getBoolean("debug_mode", false))       viewModel.toggleDebugMode()
        if (prefs.getBoolean("frequent_updates", false)) viewModel.toggleFrequentUpdates()

        val styleUrl = styleUrl(isDark = false)

        // Root layout — TwoFingerLockLayout disables map scrolling while a
        // two-finger (zoom/rotate) gesture is in progress.
        val root = TwoFingerLockLayout(this).apply {
            // Start the lock-ring animation on any touch-down in panning mode.
            // The ring grows over 450 ms; at 500 ms MapLibre fires onMapLongClickListener
            // which calls openMapLockMenu().  If the user lifts their finger before 500 ms
            // (short tap / pan), onSingleTouchEnd cancels the animation.
            onSingleTouchDown = {
                if (viewModel.uiState.value.isInPanningMode
                    && !viewModel.uiState.value.isMapLockMenuOpen
                ) {
                    startLockRingAnimation()
                }
            }
            onSingleTouchEnd = { cancelLockRingAnimation() }
        }

        // MapView fills the screen.
        // SurfaceView mode (the default — do NOT pass textureMode): gives MapLibre its own
        // hardware layer, so the GL thread runs at the display's native refresh rate
        // independently of the main thread.
        // pixelRatio: do NOT set. Causes partial map render in MapLibre 13 OpenGL ES.
        // The v11 benchmark optimum of 3.0 no longer applies; tile-fetch tuning must be
        // achieved via other means.
        //
        // logoEnabled/attributionEnabled=false: removes two views from the map hierarchy,
        // eliminating their layout passes. Attribution is displayed elsewhere in the app.
        val mapOptions = MapLibreMapOptions.createFromAttributes(this)
            .logoEnabled(false)
            .attributionEnabled(false)
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
        topRightContainer = LinearLayout(this).apply {
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

        fuelStationToggleBtn = makePillButton(this, "FUEL") { viewModel.toggleFuelStations() }
        topRightContainer.addView(
            fuelStationToggleBtn,
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
                flyToLocation(m, LatLng(loc.latitude, loc.longitude), zoom = 16.0)
            }
        }
        root.addView(
            myLocationButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(btnMargin, 0, 0, btnMargin) },
        )

        crosshairView = CrosshairView(this).apply { visibility = View.GONE }

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
        // versionCardView is added to root AFTER the dismiss overlays (see below)
        // so it sits above them in z-order and always receives taps directly.

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

        // ── Map lock dismiss overlay ───────────────────────────────────────────
        // Full-screen transparent tap target that closes the map-lock context menu.
        // Sits below the map lock panel so taps on the panel itself fall through to
        // the menu items; taps outside the panel dismiss the menu.
        mapLockDismissOverlay = View(this).apply {
            background = null
            isClickable = true
            isFocusable = false
            visibility  = View.GONE
            setOnClickListener { closeMapLockMenu() }
        }
        root.addView(
            mapLockDismissOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // ── Map lock menu panel ────────────────────────────────────────────────
        // Centered on screen (same position as the crosshair / lock ring).
        // Starts GONE; runOpenMapLockMenuAnimation expands it using the same
        // ValueAnimator mechanism as the hamburger panel.
        val ringDiameterPx = (80 * density).toInt()  // 2 × LOCK_RING_RADIUS_DP
        buildMapLockMenuPanel(this).also { result ->
            mapLockMenuPanelResult = result
            mapLockPanel           = result.root
            result.copyCoordinatesRow.setOnClickListener { onMapLockCopyCoordinates() }
            result.placeDragLineRow.setOnClickListener   { onMapLockPlaceDragLine()   }
            result.navigateRow.setOnClickListener        { closeMapLockMenu()         } // stub
            result.quickSearchRow.setOnClickListener     { closeMapLockMenu()         } // stub
        }
        val ringRadiusPx = ringDiameterPx / 2
        root.addView(
            mapLockPanel,
            FrameLayout.LayoutParams(ringDiameterPx, ringDiameterPx, Gravity.TOP or Gravity.START)
                .apply {
                    setMargins(
                        resources.displayMetrics.widthPixels  / 2 - ringRadiusPx,
                        resources.displayMetrics.heightPixels / 2 - ringRadiusPx,
                        0, 0,
                    )
                },
        )

        // CrosshairView above the lock panel so ring/icon remain visible through the hole.
        // No click listener — touch events fall through to the dismiss overlay below.
        root.addView(
            crosshairView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Fuel station tooltip card — shown when the crosshair rests over a fuel station.
        // Positioned above the crosshair (above screen centre), 50% opaque.
        fuelTooltipCard = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            setPadding(
                (12 * density).toInt(), (6 * density).toInt(),
                (12 * density).toInt(), (6 * density).toInt(),
            )
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.argb(128, 10, 10, 10))
            }
            visibility = View.GONE
        }
        root.addView(
            fuelTooltipCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL,
            ).apply { bottomMargin = (80 * density).toInt() },
        )

        // ── Tile select mode views ─────────────────────────────────────────────
        // Selection fill overlay — MATCH_PARENT transparent Canvas View; draws blue
        // fills for selected tiles via direct drawRect calls (instant, one frame).
        tileGridOverlay = TileGridOverlay(this).apply { visibility = View.GONE }
        root.addView(tileGridOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        restoreTileSelection()

        // Tile selection / progress card — bottom-right.
        tileSelectCard = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity  = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 0, 0, 0))
                cornerRadius = 16 * density
            }
            visibility = View.GONE
        }
        root.addView(
            tileSelectCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, (16 * density).toInt(), (72 * density).toInt()) },
        )

        // Resume interrupted apply if the app was killed mid-download.
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_APPLY_PENDING, false)) {
            applyTileSelection()
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

        // Version card added here (above dismiss overlays in z-order) so it always
        // receives taps directly, even when the menu dismiss overlay is visible.
        root.addView(
            versionCardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, btnMargin, btnMargin) },
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
                    s.isInDebugMenu       -> viewModel.exitDebugMenu()
                    s.isInSettingsMenu    -> viewModel.exitSettingsMenu()
                    s.isInOfflineMapsMenu -> viewModel.exitOfflineMapsMenu()
                    s.isInMapStyleMode    -> viewModel.exitMapStyleMode()
                    s.isInMapStyleMenu    -> viewModel.exitMapStyleMenu()
                    else                  -> toggleMenu()
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
            // Frequent Updates → 5-min update polling while screen is on.
            result.debugContent.getChildAt(2).setOnClickListener { viewModel.toggleFrequentUpdates() }
            // Offline Mode → forces all tile requests to use disk cache only.
            result.debugContent.getChildAt(3).setOnClickListener { viewModel.toggleOfflineMode() }
            // Clear Offline Data → evicts all cached tiles.
            result.debugContent.getChildAt(4).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { TileCache.clearCache() }
            }
            // Map Style row → opens the map style submenu.
            result.mapStyleRowInList.setOnClickListener { viewModel.enterMapStyleMenu() }
            // Road preset → enters map style mode.
            result.mapStyleContent.getChildAt(0).setOnClickListener { viewModel.enterMapStyleMode() }
            // Offroad preset → enters map style mode.
            result.mapStyleContent.getChildAt(1).setOnClickListener { viewModel.enterMapStyleMode() }
            // Offline Maps row → opens the offline maps submenu and activates tile-select mode.
            result.offlineMapsRowInList.setOnClickListener { viewModel.enterOfflineMapsMenu() }
            // Apply row inside offline maps submenu → syncs cache to selection.
            result.offlineMapsContent.getChildAt(0).setOnClickListener {
                if (tileDownloadJob?.isActive == true) {
                    tileDownloadJob?.cancel()
                    tileDownloadJob   = null
                    tileDownloadDone  = 0
                    tileDownloadTotal = 0
                    tileDownloadBytes = 0L
                    tileDownloadClip  = null
                    osdLastTilePct    = -1
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_APPLY_PENDING, false).apply()
                    updateTileSelectCard()
                } else {
                    applyTileSelection()
                }
            }
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
                isRotateGesturesEnabled  = true
                isTiltGesturesEnabled    = true
                isCompassEnabled         = true
                // Disable MapLibre's built-in scroll gesture.  Single-finger panning is
                // handled by our own touch listener (direct 1:1 camera movement with no
                // touch-slop delay).  Multi-touch gestures (pinch-zoom, rotate, tilt) are
                // still fully handled by MapLibre.
                isScrollGesturesEnabled  = false
                // Enable ease-out after multi-touch gestures (pinch-zoom deceleration,
                // rotate deceleration).  Scroll velocity is handled by our own fling code.
                setAllVelocityAnimationsEnabled(true)
            }
            // Close the tile gate on ANY camera movement — touch, D-pad, or
            // programmatic (flyToLocation).  New network tile fetches are
            // queued until the camera is idle so the GL thread is not
            // interrupted by upload work mid-animation.
            // Touch gestures additionally trigger visual panning mode.
            // A tap on the map surface closes the search panel (all elements).
            m.addOnMapClickListener { latLng ->
                val s = viewModel.uiState.value
                when {
                    s.isInTileSelectMode -> {
                        val z = tileGridOverlay.gridZoom
                        val x = lonToTile(latLng.longitude, z)
                        val y = latToTile(latLng.latitude,  z)
                        tileGridOverlay.toggleTile(x, y)
                        tileGridOverlay.invalidate()
                        updateTileSelectCard()
                        true
                    }
                    s.isSearchOpen -> { closeSearch(); true }
                    else -> false
                }
            }
            m.addOnCameraIdleListener {
                if (viewModel.uiState.value.isInTileSelectMode) {
                    refreshTileGrid()
                    tileGridOverlay.invalidate()
                }
            }
            m.addOnCameraMoveListener {
                if (viewModel.uiState.value.isInTileSelectMode) tileGridOverlay.invalidate()
            }

            m.addOnCameraMoveStartedListener { reason ->
                // Don't gate during follow mode — the camera never idles while
                // riding, so tiles would never load.  The 2-connection cap in
                // TileCache keeps uploads light enough not to stutter the GL thread.
                if (!viewModel.uiState.value.isFollowModeActive) {
                    TileCache.gate.pause()
                }
                fuelTooltipCard.visibility = View.GONE
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                    && !viewModel.uiState.value.isInTileSelectMode
                ) {
                    // User started panning — cancel any in-progress lock-ring animation.
                    cancelLockRingAnimation()
                    if (viewModel.uiState.value.isFollowModeActive) {
                        viewModel.disableFollowMode()
                    } else {
                        enterPanningMode()
                    }
                }
            }

            // Long-press on the map surface (touch) while in panning mode opens the
            // map-lock context menu.  The lock-ring animation started in the root
            // layout's ACTION_DOWN hook should be complete by the time this fires.
            m.addOnMapLongClickListener {
                if (viewModel.uiState.value.isInPanningMode
                    && !viewModel.uiState.value.isMapLockMenuOpen
                ) {
                    openMapLockMenu()
                    true   // consume — prevent default long-press behaviour
                } else {
                    false
                }
            }
            // Open the gate when the camera stops.  MapLibre will immediately
            // start filling in any missing tiles; the map is static, so
            // uploads do not drop visible frames.
            m.addOnCameraIdleListener {
                TileCache.gate.resume()
                queryFuelTooltip()
            }

            // Crosshair fade: recompute whenever the camera moves so the alpha
            // stays accurate without polling every vsync frame.
            // Also track zoom changes for OSD dirty-flag caching.
            m.addOnCameraMoveListener {
                val z = m.cameraPosition.zoom
                if (z != osdCachedZoom) { osdCachedZoom = z; osdDirty = true }
                updateCrosshairAlpha()
            }

            // ── Direct 1-finger pan ────────────────────────────────────────────
            // Returns false so MapLibre still sees every event (tap, long-press,
            // pinch-zoom, rotate, tilt all continue to work normally).  With
            // isScrollGesturesEnabled = false, MapLibre will not double-scroll.
            mapView.setOnTouchListener { _, event ->
                panVelocityTracker?.addMovement(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        panFlingAnimator?.cancel()
                        panTouchLastX    = event.x
                        panTouchLastY    = event.y
                        panTouchActive   = true
                        panModeTriggered = false
                        panVelocityTracker?.recycle()
                        panVelocityTracker = VelocityTracker.obtain()
                        panVelocityTracker?.addMovement(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (panTouchActive && event.pointerCount == 1) {
                            val dx = panTouchLastX - event.x
                            val dy = panTouchLastY - event.y
                            panTouchLastX = event.x
                            panTouchLastY = event.y
                            if (dx != 0f || dy != 0f) {
                                map?.let { m ->
                                    val cx = mapView.width  / 2f + dx
                                    val cy = mapView.height / 2f + dy
                                    m.moveCamera(CameraUpdateFactory.newLatLng(
                                        m.projection.fromScreenLocation(PointF(cx, cy))
                                    ))
                                }
                                if (!panModeTriggered && !viewModel.uiState.value.isInTileSelectMode) {
                                    panModeTriggered = true
                                    cancelLockRingAnimation()
                                    if (viewModel.uiState.value.isFollowModeActive) {
                                        viewModel.disableFollowMode()
                                    } else {
                                        enterPanningMode()
                                    }
                                }
                            }
                        } else if (event.pointerCount > 1) {
                            // Multi-finger: suspend our handling; keep last position
                            // so we can resume cleanly if a finger is lifted.
                            panTouchActive = false
                            panTouchLastX  = event.getX(0)
                            panTouchLastY  = event.getY(0)
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        panTouchActive = false
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        // Returning from 2 → 1 finger: resume single-finger panning
                        // from the remaining pointer's current position.
                        if (event.pointerCount == 2) {
                            val rem    = if (event.actionIndex == 0) 1 else 0
                            panTouchLastX  = event.getX(rem)
                            panTouchLastY  = event.getY(rem)
                            panTouchActive = true
                            panModeTriggered = true  // mode already triggered earlier
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        panTouchActive = false
                        val vt = panVelocityTracker
                        if (vt != null && panModeTriggered) {
                            vt.computeCurrentVelocity(1000)  // pixels/second
                            val vx = vt.xVelocity
                            val vy = vt.yVelocity
                            vt.recycle(); panVelocityTracker = null
                            val speed = sqrt(vx * vx + vy * vy)
                            if (speed > 500f) {
                                // Duration scales with speed: faster fling → longer coast.
                                val durMs = (speed / 5000f * 800f).toLong().coerceIn(200L, 800L)
                                var lastT = 0f
                                panFlingAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration     = durMs
                                    interpolator = DecelerateInterpolator(2f)
                                    addUpdateListener { va ->
                                        val t  = va.animatedValue as Float
                                        val dt = t - lastT; lastT = t
                                        // Total fling distance = v * t / 2 (constant-decel integral).
                                        val fdx = -vx * durMs / 2000f * dt
                                        val fdy = -vy * durMs / 2000f * dt
                                        map?.let { m ->
                                            val cx = mapView.width  / 2f + fdx
                                            val cy = mapView.height / 2f + fdy
                                            m.moveCamera(CameraUpdateFactory.newLatLng(
                                                m.projection.fromScreenLocation(PointF(cx, cy))
                                            ))
                                        }
                                    }
                                    start()
                                })
                            }
                        } else {
                            panVelocityTracker?.recycle(); panVelocityTracker = null
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        panTouchActive = false
                        panFlingAnimator?.cancel()
                        panVelocityTracker?.recycle(); panVelocityTracker = null
                    }
                }
                false  // always let MapLibre also process the event
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
                map                 = m
                style               = s
                tileGridOverlay.map = m
                addOfflineBorderLayers()
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
                LocationComponentActivationOptions.builder(this@MapActivity, s)
                    .locationEngine(syntheticEngine)
                    .build(),
            )
            isLocationComponentEnabled = true
        }

        // Subscribe to the system GPS for raw fixes.
        // rawLocationListener drives the puck, bearing EMA, and camera follow
        // directly at ~5 Hz; it also runs outlier rejection before accepting a fix.
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 200L, 0f, rawLocationListener, mainLooper,
            )
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { rawGpsLocation = it }
        } catch (_: SecurityException) { }

        rawGpsLocation?.let { loc ->
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

            is RemoteEvent.KeyDown -> {
                panController.onKeyDown(event.key)
                // Start the lock-ring animation when CONFIRM is pressed in panning mode.
                // The ring grows over 450 ms (with a 50 ms start delay) to visualise the
                // long-press charge-up; the map lock menu opens when LongPress fires.
                if (event.key == RemoteKey.CONFIRM
                    && viewModel.uiState.value.isInPanningMode
                    && !viewModel.uiState.value.isMapLockMenuOpen
                ) {
                    startLockRingAnimation()
                }
                // Close the map-lock menu immediately on BACK regardless of other state.
                if (event.key == RemoteKey.BACK && viewModel.uiState.value.isMapLockMenuOpen) {
                    closeMapLockMenu()
                }
            }

            is RemoteEvent.KeyUp -> panController.onKeyUp(event.key, m)

            is RemoteEvent.ShortPress -> when (event.key) {
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {}
                RemoteKey.ZOOM_IN, RemoteKey.ZOOM_OUT -> {}  // handled by KeyDown/KeyUp

                RemoteKey.CONFIRM -> {
                    // Short press → user released before the long-press threshold.
                    // Cancel the lock-ring animation and apply the normal short-press action.
                    cancelLockRingAnimation()
                    // In panning mode: confirm exits panning and re-locks on GPS.
                    // Outside panning mode: fly to current location directly.
                    if (viewModel.uiState.value.isInPanningMode) {
                        exitPanningMode()
                    } else {
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            flyToLocation(m, LatLng(loc.latitude, loc.longitude))
                        }
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
                        // Long-press CONFIRM while panning: open the map-lock context menu.
                        // The lock-ring animation that started on KeyDown should have
                        // completed by now (50 ms delay + 450 ms animation = 500 ms).
                        openMapLockMenu()
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
     * Recompute and apply crosshair alpha based on the screen distance between
     * the crosshair (screen centre) and the GPS puck.
     *
     * Call this on every GPS fix and on every camera move so the fade stays
     * accurate without running every vsync frame.
     * 20 dp away → fully opaque; 5 dp away → fully transparent.
     */
    private fun updateCrosshairAlpha() {
        val m = map ?: return
        if (crosshairView.visibility != View.VISIBLE) return
        if (followLastLat.isNaN()) { crosshairView.alpha = 1f; return }
        val d      = resources.displayMetrics.density
        val puck   = m.projection.toScreenLocation(LatLng(followLastLat, followLastLon))
        val dcx    = puck.x - mapView.width  / 2f
        val dcy    = puck.y - mapView.height / 2f
        val distDp = sqrt(dcx * dcx + dcy * dcy) / d
        crosshairView.alpha = ((distDp - 5f) / (20f - 5f)).coerceIn(0f, 1f)
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
            val gpsTarget = m.locationComponent.lastKnownLocation
                ?.let { LatLng(it.latitude, it.longitude) }
                ?: m.cameraPosition.target
                ?: return
            flyToLocation(
                m, gpsTarget,
                zoom    = 17.0,
                tilt    = 45.0,
                padding = doubleArrayOf(0.0, screenH * 0.5, 0.0, 0.0),
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

        // ── Phase 1: slide outgoing elements away ─────────────────────────────
        // withEndAction handles cleanup when the animation completes naturally.
        // If the animation is cancelled by a rapid mode switch, the incoming
        // Phase 2 (which starts simultaneously below) overrides all view state,
        // so stale withEndActions are harmless.
        when (from) {
            AppMode.EXPLORE -> {
                menuPanel.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { menuPanel.visibility = View.GONE; menuPanel.translationX = 0f }
                    .start()
                myLocationButton.animate().translationX(-w)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { myLocationButton.visibility = View.GONE; myLocationButton.translationX = 0f }
                    .start()
                exploreBottomBar.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { exploreBottomBar.visibility = View.GONE; exploreBottomBar.translationY = 0f }
                    .start()
            }
            AppMode.NAVIGATE -> {
                navigateBanner.animate().translationY(-h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateBanner.translationY = 0f; navigateOverlay.visibility = View.GONE }
                    .start()
                navigateStopBtn.animate().translationY(h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { navigateStopBtn.translationY = 0f }
                    .start()
            }
            AppMode.EDIT -> {
                editTopBar.animate().translationY(-h)
                    .setDuration(outDur).setInterpolator(outInterp)
                    .withEndAction { editTopBar.visibility = View.GONE; editTopBar.translationY = 0f }
                    .start()
            }
        }

        // ── Phase 2: slide incoming elements in (runs simultaneously with Phase 1) ──
        // Starting immediately rather than waiting for Phase 1 to finish eliminates
        // all timing-coordination hazards (postDelayed, AnimationLatch, withEndAction
        // chaining) that previously caused the incoming mode's UI to stay invisible.
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
            addOfflineBorderLayers()
            if (viewModel.uiState.value.isSatelliteEnabled) {
                applySatelliteLayer(enabled = true, animated = false)
            }
            if (viewModel.uiState.value.isFuelStationsEnabled) {
                applyFuelStationsLayer(enabled = true)
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
                LocationComponentActivationOptions.builder(this@MapActivity, s)
                    .locationEngine(syntheticEngine)
                    .build(),
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

    private fun applyFuelStationsLayer(enabled: Boolean) {
        val s = style ?: return
        if (enabled) {
            if (s.getLayerAs<SymbolLayer>(LAYER_FUEL) != null) return
            val sourceId = s.sources
                .filterIsInstance<VectorSource>()
                .firstOrNull()?.id ?: return
            if (s.getImage(FUEL_ICON_ID) == null) {
                s.addImage(FUEL_ICON_ID, makeFuelIcon())
            }
            val layer = SymbolLayer(LAYER_FUEL, sourceId).apply {
                sourceLayer = "poi"
                setFilter(Expression.eq(Expression.get("class"), Expression.literal("fuel")))
                setProperties(
                    PropertyFactory.iconImage(FUEL_ICON_ID),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(1.0f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                )
            }
            s.addLayer(layer)
        } else {
            s.getLayerAs<SymbolLayer>(LAYER_FUEL)?.let { s.removeLayer(it) }
            fuelTooltipCard.visibility = View.GONE
        }
    }

    /** Blue circle icon for fuel station POIs. Registered into the map style sprite at runtime. */
    private fun makeFuelIcon(): Bitmap {
        val d    = resources.displayMetrics.density
        val size = (18 * d).toInt().coerceAtLeast(18)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv   = Canvas(bmp)
        val r    = size / 2f
        cv.drawCircle(r, r, r * 0.82f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1565C0")
            style = Paint.Style.FILL
        })
        cv.drawCircle(r, r, r * 0.82f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = r * 0.28f
        })
        return bmp
    }

    /** Query rendered fuel station features at the screen centre; update the tooltip card. */
    private fun queryFuelTooltip() {
        if (!viewModel.uiState.value.isFuelStationsEnabled) return
        if (!viewModel.uiState.value.isInPanningMode) return
        val m  = map ?: return
        val cx = mapView.width  / 2f
        val cy = mapView.height / 2f
        val tol = 24f   // pixel tolerance — matches the icon radius at normal density
        val features = m.queryRenderedFeatures(
            android.graphics.RectF(cx - tol, cy - tol, cx + tol, cy + tol),
            LAYER_FUEL,
        )
        if (features.isNotEmpty()) {
            val name = features.first().getStringProperty("name") ?: "Fuel Station"
            fuelTooltipCard.text = name
            fuelTooltipCard.visibility = View.VISIBLE
        } else {
            fuelTooltipCard.visibility = View.GONE
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
        val modeChanged          = old?.mode != new.mode
        val panningChanged       = old?.isInPanningMode != new.isInPanningMode
        val followChanged        = old?.isFollowModeActive != new.isFollowModeActive
        val menuChanged          = old?.isMenuOpen != new.isMenuOpen
        val searchChanged        = old?.isSearchOpen != new.isSearchOpen
        val settingsMenuChanged  = old?.isInSettingsMenu != new.isInSettingsMenu
        val debugMenuChanged     = old?.isInDebugMenu != new.isInDebugMenu
        val offlineMapsMenuChanged    = old?.isInOfflineMapsMenu != new.isInOfflineMapsMenu
        val tileSelectModeChanged     = old?.isInTileSelectMode != new.isInTileSelectMode
        val mapStyleMenuChanged       = old?.isInMapStyleMenu != new.isInMapStyleMenu
        val mapStyleModeChanged       = old?.isInMapStyleMode != new.isInMapStyleMode
        val debugModeChanged          = old?.isDebugMode != new.isDebugMode
        val frequentUpdatesChanged    = old?.isFrequentUpdatesEnabled != new.isFrequentUpdatesEnabled
        val offlineModeChanged        = old?.isOfflineMode != new.isOfflineMode
        val mapLockMenuChanged        = old?.isMapLockMenuOpen != new.isMapLockMenuOpen

        // ── Crosshair ──────────────────────────────────────────────────────────
        // EDIT always shows the crosshair (it acts as the placement cursor).
        // Otherwise the crosshair is shown only while the user is manually panning.
        crosshairView.visibility = when {
            new.isInTileSelectMode                          -> View.GONE
            new.mode == AppMode.EDIT || new.isInPanningMode -> View.VISIBLE
            else                                            -> View.GONE
        }

        // ── Map lock menu ──────────────────────────────────────────────────────
        if (old != null && mapLockMenuChanged) {
            if (new.isMapLockMenuOpen) {
                runOpenMapLockMenuAnimation()
            } else {
                runCloseMapLockMenuAnimation()
            }
        }


        // ── Fly-to on follow mode enable ───────────────────────────────────────
        // Snap the camera to GPS immediately when follow mode is turned on so
        // there is no visible delay before the Choreographer tracking takes over.
        if (followChanged && new.isFollowModeActive) {
            // Reset last-fix state so the next GPS fix is always accepted.
            followLastLat = Double.NaN
            followLastLon = Double.NaN
            val m   = map
            val loc = rawGpsLocation ?: m?.locationComponent?.lastKnownLocation
            if (m != null && loc != null) {
                flyToLocation(m, LatLng(loc.latitude, loc.longitude))
            }
        }

        // ── Fly-to on panning exit (without follow mode) ──────────────────────
        // When panning exits and follow mode is NOT active, fly back to GPS once.
        // If follow mode IS active, the snap above (or the Choreographer loop)
        // already handles re-centering.
        if (panningChanged && !new.isInPanningMode && old?.isInPanningMode == true) {
            fuelTooltipCard.visibility = View.GONE
            if (!new.isFollowModeActive) {
                val m   = map
                val loc = m?.locationComponent?.lastKnownLocation
                if (m != null && loc != null) {
                    flyToLocation(m, LatLng(loc.latitude, loc.longitude))
                }
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
                // In tile select mode the menu stays open alongside the tile grid;
                // map taps must reach the MapView (tile selection / panning), not close the menu.
                if (new.isInTileSelectMode) menuDismissOverlay.visibility = View.GONE
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
                if (old.isInOfflineMapsMenu) {
                    offlineMapsMenuAnimator?.cancel()
                    menuPanelResult.offlineMapsGhostHeader.visibility = View.GONE
                    menuPanelResult.offlineMapsGhostHeader.alpha      = 0f
                    menuPanelResult.offlineMapsContent.visibility     = View.GONE
                    menuPanelResult.offlineMapsContent.alpha          = 0f
                    menuPanelResult.mainMenuScroll.visibility         = View.VISIBLE
                    menuPanelResult.mainMenuScroll.alpha              = 1f
                    menuPanelResult.offlineMapsRowIcon.alpha          = 1f
                    hamburgerBars.forEach { it.scaleX = 1f; it.translationX = 0f; it.translationY = 0f }
                }
                if (old.isInMapStyleMenu) {
                    mapStyleMenuAnimator?.cancel()
                    mapStyleModeAnimator?.cancel()
                    menuPanelResult.mapStyleGhostHeader.visibility = View.GONE
                    menuPanelResult.mapStyleGhostHeader.alpha      = 0f
                    menuPanelResult.mapStyleContent.visibility     = View.GONE
                    menuPanelResult.mapStyleContent.alpha          = 0f
                    menuPanelResult.mainMenuScroll.visibility      = View.VISIBLE
                    menuPanelResult.mainMenuScroll.alpha           = 1f
                    menuPanelResult.mapStyleRowIcon.alpha          = 1f
                    hamburgerBars.forEach { it.scaleX = 1f; it.translationX = 0f; it.translationY = 0f }
                    if (old.isInMapStyleMode) {
                        // Instantly restore chrome that was slid off-screen.
                        myLocationButton.visibility   = View.VISIBLE
                        myLocationButton.translationX = 0f
                        exploreBottomBar.visibility   = View.VISIBLE
                        exploreBottomBar.translationY = 0f
                        topRightContainer.visibility  = View.VISIBLE
                        topRightContainer.translationX = 0f
                        versionCardView.visibility    = View.VISIBLE
                        versionCardView.translationX  = 0f
                    }
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

        // ── Offline Maps submenu transition ────────────────────────────────────
        if (old != null && offlineMapsMenuChanged && new.isMenuOpen) {
            if (new.isInOfflineMapsMenu) {
                updateTileSelectCard()
                runEnterOfflineMapsAnimation()
            } else {
                runExitOfflineMapsAnimation()
            }
        }

        // ── Map Style submenu transition ───────────────────────────────────────
        if (old != null && mapStyleMenuChanged && new.isMenuOpen && !new.isInMapStyleMode) {
            if (new.isInMapStyleMenu) runEnterMapStyleMenuAnimation()
            else runExitMapStyleMenuAnimation()
        }

        // ── Map Style mode transition ──────────────────────────────────────────
        if (old != null && mapStyleModeChanged && new.isMenuOpen) {
            if (new.isInMapStyleMode) runEnterMapStyleMode()
            else runExitMapStyleMode()
        }

        // ── Tile select mode transition ────────────────────────────────────────
        if (old != null && tileSelectModeChanged) {
            if (new.isInTileSelectMode) {
                // Menu is already open; map taps must not close it while the grid is active.
                menuDismissOverlay.visibility = View.GONE
                setOfflineBorderVisible(false)
                runEnterTileSelectMode()
            } else {
                // Restore the dismiss overlay when the menu stays open without tile select.
                if (new.isMenuOpen) menuDismissOverlay.visibility = View.VISIBLE
                setOfflineBorderVisible(true)
                runExitTileSelectMode()
            }
        }

        // ── Debug overlay ──────────────────────────────────────────────────────
        if (debugModeChanged || old == null) {
            osdView.visibility = if (new.isDebugMode) View.VISIBLE else View.GONE
            menuPanelResult.debugToggleLabel.text =
                "Debug Mode: ${if (new.isDebugMode) "ON" else "OFF"}"
            if (debugModeChanged) prefs.edit().putBoolean("debug_mode", new.isDebugMode).apply()
        }

        // ── Frequent Updates polling ────────────────────────────────────────────
        if (frequentUpdatesChanged || old == null) {
            menuPanelResult.frequentUpdatesLabel.text =
                "Frequent Updates: ${if (new.isFrequentUpdatesEnabled) "ON" else "OFF"}"
            if (frequentUpdatesChanged) prefs.edit().putBoolean("frequent_updates", new.isFrequentUpdatesEnabled).apply()
            if (new.isFrequentUpdatesEnabled) startFrequentUpdatePolling()
            else stopFrequentUpdatePolling()
        }

        // ── Offline mode ────────────────────────────────────────────────────────
        if (offlineModeChanged || old == null) {
            TileCache.isOfflineMode = new.isOfflineMode
            menuPanelResult.offlineModeLabel.text =
                "Offline Mode: ${if (new.isOfflineMode) "ON" else "OFF"}"
            if (offlineModeChanged) {
                // Force MapLibre to re-evaluate tiles with the new cache policy.
                map?.let { m -> m.easeCamera(CameraUpdateFactory.zoomBy(0.0), 1) }
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
            }
        }

        // ── Map style toggles ──────────────────────────────────────────────────
        val satChanged  = old?.isSatelliteEnabled != new.isSatelliteEnabled
        val darkChanged = old?.isDarkMode         != new.isDarkMode
        val fuelChanged = old?.isFuelStationsEnabled != new.isFuelStationsEnabled

        if (darkChanged) {
            reloadStyle(new.isDarkMode)   // callback handles satellite and fuel re-add
        } else if (satChanged) {
            applySatelliteLayer(enabled = new.isSatelliteEnabled, animated = old != null)
        }
        if (fuelChanged) {
            applyFuelStationsLayer(enabled = new.isFuelStationsEnabled)
        }

        setToggleActive(satelliteToggleBtn,   new.isSatelliteEnabled)
        setToggleActive(darkModeToggleBtn,    new.isDarkMode)
        setToggleActive(courseUpToggleBtn,    new.isCourseUpEnabled)
        setToggleActive(followToggleBtn,      new.isFollowModeActive)
        setToggleActive(fuelStationToggleBtn, new.isFuelStationsEnabled)

        // ── Course Up → North Up transition ───────────────────────────────────
        // When Course Up is turned off, animate the map back to 0° (north at top)
        // and reset smoothed bearing state so re-enabling starts fresh.
        val courseUpChanged = old?.isCourseUpEnabled != new.isCourseUpEnabled
        if (courseUpChanged && !new.isCourseUpEnabled) {
            rotate(0.0)
            followSmoothedSin = Double.NaN
            followSmoothedCos = Double.NaN
        }
    }

    private fun toggleMenu() { viewModel.toggleMenu() }
    private fun openMenu()   { viewModel.openMenu() }
    private fun closeMenu()  { viewModel.closeMenu() }

    // ── Map lock menu ─────────────────────────────────────────────────────────

    /**
     * Starts the lock-ring arc animation on the crosshair.
     *
     * The arc grows from 0° to 360° over 450 ms after a 50 ms delay, so it
     * completes exactly when the 500 ms long-press threshold elapses.
     *
     * Idempotent — cancels any in-progress animation before starting a new one.
     */
    private fun startLockRingAnimation() {
        lockRingAnimator?.cancel()
        lockRingAnimator = animBag.add(ValueAnimator.ofFloat(0f, 360f).apply {
            duration   = 450
            startDelay = 150
            addUpdateListener { va ->
                crosshairView.lockRingSweep = va.animatedValue as Float
            }
            start()
        })
    }

    /**
     * Cancels the lock-ring animation and resets the arc to zero.
     *
     * Called when the user releases the finger / button before the long-press
     * threshold, or when the camera starts moving (pan gesture detected).
     * No-op if the map-lock menu is already open.
     */
    private fun cancelLockRingAnimation() {
        if (viewModel.uiState.value.isMapLockMenuOpen) return
        lockRingAnimator?.cancel()
        lockRingAnimator = null
        crosshairView.lockRingSweep = 0f
    }

    private fun openMapLockMenu()  { viewModel.openMapLockMenu() }
    private fun closeMapLockMenu() { viewModel.closeMapLockMenu() }

    /**
     * Expand the map-lock menu panel from ring-diameter size to full content size.
     *
     * Uses the same ValueAnimator / DecelerateInterpolator mechanism as
     * [runOpenMenuAnimation] so the expansion feels identical to the hamburger menu.
     * The panel grows from the centre of the screen (where the crosshair / ring sits).
     */
    private fun runOpenMapLockMenuAnimation() {
        mapLockMenuAnimator?.cancel()
        mapLockDismissOverlay.visibility = View.VISIBLE
        mapLockPanel.visibility          = View.VISIBLE

        val d       = resources.displayMetrics.density
        val panelW  = (280 * d).toInt()
        val panelH  = (80 * d).toInt() + 2 * (64 * d).toInt()  // ring zone + 2 items × 64 dp
        val ringRad = (80 * d).toInt() / 2
        val lp      = mapLockPanel.layoutParams as FrameLayout.LayoutParams
        val parent  = mapLockPanel.parent as View

        // Snap to actual view centre before becoming visible (displayMetrics may include nav bar).
        lp.topMargin  = parent.height / 2 - ringRad
        lp.leftMargin = parent.width  / 2 - ringRad
        mapLockPanel.layoutParams = lp

        val startW = lp.width
        val startH = lp.height

        mapLockMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (panelW - startW) * t).toInt()
                lp.height = (startH + (panelH - startH) * t).toInt()
                mapLockPanel.layoutParams = lp
                mapLockPanel.alpha = t
            }
            start()
        })
    }

    /**
     * Collapse the map-lock menu panel back to ring-diameter size, then hide it.
     */
    private fun runCloseMapLockMenuAnimation() {
        mapLockMenuAnimator?.cancel()
        lockRingAnimator?.cancel()
        mapLockDismissOverlay.visibility = View.GONE

        val d          = resources.displayMetrics.density
        val ringDiamPx = (80 * d).toInt()
        val lp         = mapLockPanel.layoutParams as FrameLayout.LayoutParams
        val startW     = lp.width
        val startH     = lp.height

        // Animate the ring arc backwards (360° → 0°) in parallel with the panel shrink.
        lockRingAnimator = animBag.add(ValueAnimator.ofFloat(360f, 0f).apply {
            duration     = 180
            interpolator = AccelerateInterpolator()
            addUpdateListener { va -> crosshairView.lockRingSweep = va.animatedValue as Float }
            start()
        })

        mapLockMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 180
            interpolator = AccelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (ringDiamPx - startW) * t).toInt()
                lp.height = (startH + (ringDiamPx - startH) * t).toInt()
                mapLockPanel.layoutParams = lp
                mapLockPanel.alpha = 1f - t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mapLockPanel.visibility = View.GONE
                    // Reset to ring-diameter so next open starts from the same size.
                    val parent = mapLockPanel.parent as View
                    lp.width      = ringDiamPx
                    lp.height     = ringDiamPx
                    lp.topMargin  = parent.height / 2 - ringDiamPx / 2
                    lp.leftMargin = parent.width  / 2 - ringDiamPx / 2
                    mapLockPanel.layoutParams = lp
                    lockRingAnimator = null
                }
            })
            start()
        })
    }

    /**
     * "Copy Coordinates" menu action: copies the crosshair LatLng to the clipboard.
     */
    private fun onMapLockCopyCoordinates() {
        closeMapLockMenu()
        val m = map ?: return
        val screenCenter = PointF(mapView.width / 2f, mapView.height / 2f)
        val target = m.projection.fromScreenLocation(screenCenter)
        val text = "%.6f,%.6f".format(target.latitude, target.longitude)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
    }

    /**
     * "Place Drag Line" menu action: anchors the drag line at the crosshair position.
     *
     * Identical to what the old direct long-press used to do, now surfaced as a
     * menu entry so it coexists with the other map-lock actions.
     */
    private fun onMapLockPlaceDragLine() {
        closeMapLockMenu()
        val m = map ?: return
        val screenCenter = PointF(mapView.width / 2f, mapView.height / 2f)
        val target = m.projection.fromScreenLocation(screenCenter)
        val prevAnchor = dragLineAnchor
        if (prevAnchor != null) {
            // Slide the existing anchor to the new position.  If a slide is already
            // in progress, start the new slide from the current mid-point so it
            // doesn't snap.
            anchorSlideFrom    = slideAnchor() ?: prevAnchor
            anchorSlideStartNs = System.nanoTime()
        }
        // Always restart the wave so it animates during the slide and settles after.
        dragLineAnimator.reset()
        anchorSetTimeNs  = System.nanoTime()
        dragLineSettled  = false
        dragLineAnchor   = target
        m.locationComponent.lastKnownLocation?.let { loc ->
            setDragLine(LatLng(loc.latitude, loc.longitude), slideAnchor() ?: target, System.nanoTime() / 1_000_000_000.0)
        }
    }

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

        flyToLocation(m, newCameraTarget)
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
     * Returns the settings panel (width, height) in pixels.
     *
     * Width = max(280dp, widest item measured at UNSPECIFIED).
     * Height = 64dp header + settings items.
     * Cached after first measurement.
     */
    private fun getOrMeasureSettingsSize(): Pair<Int, Int> {
        if (settingsMenuWidth > 0 && settingsMenuHeight > 0)
            return Pair(settingsMenuWidth, settingsMenuHeight)
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val minW   = (280 * d).toInt()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val content = menuPanelResult.settingsContent
        var maxW = minW
        for (i in 0 until content.childCount) {
            content.getChildAt(i).measure(unspec, unspec)
            maxW = maxOf(maxW, content.getChildAt(i).measuredWidth)
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        content.measure(wSpec, hSpec)
        settingsMenuWidth  = maxW
        settingsMenuHeight = itemH + content.measuredHeight
        return Pair(settingsMenuWidth, settingsMenuHeight)
    }

    /**
     * Returns the debug panel (width, height) in pixels.
     *
     * Width = max(280dp, widest item measured at UNSPECIFIED).
     * Height = 64dp header + debug items.
     * Cached after first measurement.
     */
    private fun getOrMeasureDebugSize(): Pair<Int, Int> {
        if (debugMenuWidth > 0 && debugMenuHeight > 0)
            return Pair(debugMenuWidth, debugMenuHeight)
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val minW   = (280 * d).toInt()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val content = menuPanelResult.debugContent
        var maxW = minW
        for (i in 0 until content.childCount) {
            content.getChildAt(i).measure(unspec, unspec)
            maxW = maxOf(maxW, content.getChildAt(i).measuredWidth)
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        content.measure(wSpec, hSpec)
        debugMenuWidth  = maxW
        debugMenuHeight = itemH + content.measuredHeight
        return Pair(debugMenuWidth, debugMenuHeight)
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

        val (targetW, targetH) = getOrMeasureDebugSize()
        val lp      = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW  = lp.width
        val startH  = lp.height

        debugMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
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

        val (targetW, targetH) = getOrMeasureSettingsSize()
        val lp      = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW  = lp.width
        val startH  = lp.height

        debugMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
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

        val ghostStartY = (7 * itemH).toFloat()
        ghost.translationY = ghostStartY
        ghost.alpha        = 0f
        ghost.visibility   = View.VISIBLE

        settingsContent.visibility = View.VISIBLE
        settingsContent.alpha      = 0f

        val (targetW, targetH) = getOrMeasureSettingsSize()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
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
                lp.width  = (startW + (targetW - startW) * t).toInt()
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

        val ghostEndY = (7 * itemH).toFloat()
        scroll.alpha      = 0f
        scroll.visibility = View.VISIBLE

        val targetH   = getOrMeasurePanelHeight()
        val targetW   = (280 * d).toInt()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
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
                lp.width  = (startW + (targetW - startW) * t).toInt()
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

    // ── Offline Maps submenu ──────────────────────────────────────────────────

    /**
     * Returns the offline maps panel (width, height) in pixels.
     * Cached after first measurement.
     */
    private fun getOrMeasureOfflineMapsSize(): Pair<Int, Int> {
        if (offlineMapsMenuWidth > 0 && offlineMapsMenuHeight > 0)
            return Pair(offlineMapsMenuWidth, offlineMapsMenuHeight)
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val minW   = (420 * d).toInt()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val content = menuPanelResult.offlineMapsContent
        var maxW = minW
        for (i in 0 until content.childCount) {
            content.getChildAt(i).measure(unspec, unspec)
            maxW = maxOf(maxW, content.getChildAt(i).measuredWidth)
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        content.measure(wSpec, hSpec)
        offlineMapsMenuWidth  = maxW
        offlineMapsMenuHeight = itemH + content.measuredHeight
        return Pair(offlineMapsMenuWidth, offlineMapsMenuHeight)
    }

    private fun runEnterOfflineMapsAnimation() {
        offlineMapsMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost           = menuPanelResult.offlineMapsGhostHeader
        val scroll          = menuPanelResult.mainMenuScroll
        val offlineContent  = menuPanelResult.offlineMapsContent
        val rowIcon         = menuPanelResult.offlineMapsRowIcon

        val ghostStartY = (6 * itemH).toFloat()
        ghost.translationY = ghostStartY
        ghost.alpha        = 0f
        ghost.visibility   = View.VISIBLE

        offlineContent.visibility = View.VISIBLE
        offlineContent.alpha      = 0f

        val (targetW, targetH) = getOrMeasureOfflineMapsSize()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
        val startH    = lp.height

        val barTargetRot    = floatArrayOf(-45f, 0f, +45f)
        val barTargetScale  = floatArrayOf(0.5f, 1.0f, 0.5f)
        val barTargetTransX = floatArrayOf(-1.5f * d, +2f * d, -1.5f * d)
        val barTargetTransY = floatArrayOf(+1.5f * d,  0f,     -1.5f * d)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha  = rowIcon.alpha

        offlineMapsMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY = ghostStartY * (1f - t)
                ghost.alpha        = t
                scroll.alpha       = 1f - t
                offlineContent.alpha = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
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

    private fun runExitOfflineMapsAnimation() {
        offlineMapsMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost           = menuPanelResult.offlineMapsGhostHeader
        val scroll          = menuPanelResult.mainMenuScroll
        val offlineContent  = menuPanelResult.offlineMapsContent
        val rowIcon         = menuPanelResult.offlineMapsRowIcon

        val ghostEndY = (6 * itemH).toFloat()
        scroll.alpha      = 0f
        scroll.visibility = View.VISIBLE

        val targetH   = getOrMeasurePanelHeight()
        val targetW   = (280 * d).toInt()
        val lp        = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW    = lp.width
        val startH    = lp.height

        val barTargetRot    = floatArrayOf(90f, 90f, 90f)
        val barTargetScale  = floatArrayOf(1.0f, 1.0f, 1.0f)
        val barTargetTransX = floatArrayOf(0f, 0f, 0f)
        val barTargetTransY = floatArrayOf(0f, 0f, 0f)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha  = rowIcon.alpha

        offlineMapsMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY     = ghostEndY * t
                ghost.alpha            = 1f - t
                scroll.alpha           = t
                offlineContent.alpha   = (1f - t / 0.7f).coerceIn(0f, 1f)
                rowIcon.alpha          = iconStartAlpha + (1f - iconStartAlpha) * t
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
                    offlineContent.visibility  = View.GONE
                    offlineContent.alpha       = 0f
                    rowIcon.alpha              = 1f
                }
            })
            start()
        })
    }

    // ── Map Style submenu + mode ──────────────────────────────────────────────

    private fun getOrMeasureMapStyleSize(): Pair<Int, Int> {
        if (mapStyleMenuWidth > 0 && mapStyleMenuHeight > 0)
            return Pair(mapStyleMenuWidth, mapStyleMenuHeight)
        val d      = resources.displayMetrics.density
        val itemH  = (64 * d).toInt()
        val minW   = (420 * d).toInt()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val content = menuPanelResult.mapStyleContent
        var maxW = minW
        for (i in 0 until content.childCount) {
            content.getChildAt(i).measure(unspec, unspec)
            maxW = maxOf(maxW, content.getChildAt(i).measuredWidth)
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        content.measure(wSpec, hSpec)
        mapStyleMenuWidth  = maxW
        mapStyleMenuHeight = itemH + content.measuredHeight
        return Pair(mapStyleMenuWidth, mapStyleMenuHeight)
    }

    private fun runEnterMapStyleMenuAnimation() {
        mapStyleMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost       = menuPanelResult.mapStyleGhostHeader
        val scroll      = menuPanelResult.mainMenuScroll
        val content     = menuPanelResult.mapStyleContent
        val rowIcon     = menuPanelResult.mapStyleRowIcon

        // Map Style is at list index 4 (0-based); absolute Y = (4+1)*itemH
        val ghostStartY = (5 * itemH).toFloat()
        ghost.translationY = ghostStartY
        ghost.alpha        = 0f
        ghost.visibility   = View.VISIBLE

        content.visibility = View.VISIBLE
        content.alpha      = 0f

        val (targetW, targetH) = getOrMeasureMapStyleSize()
        val lp     = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW = lp.width
        val startH = lp.height

        val barTargetRot    = floatArrayOf(-45f, 0f, +45f)
        val barTargetScale  = floatArrayOf(0.5f, 1.0f, 0.5f)
        val barTargetTransX = floatArrayOf(-1.5f * d, +2f * d, -1.5f * d)
        val barTargetTransY = floatArrayOf(+1.5f * d,  0f,     -1.5f * d)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha  = rowIcon.alpha

        mapStyleMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY  = ghostStartY * (1f - t)
                ghost.alpha         = t
                scroll.alpha        = 1f - t
                content.alpha       = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                rowIcon.alpha       = iconStartAlpha * (1f - t)
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

    private fun runExitMapStyleMenuAnimation() {
        mapStyleMenuAnimator?.cancel()
        val d     = resources.displayMetrics.density
        val itemH = (64 * d).toInt()

        val ghost   = menuPanelResult.mapStyleGhostHeader
        val scroll  = menuPanelResult.mainMenuScroll
        val content = menuPanelResult.mapStyleContent
        val rowIcon = menuPanelResult.mapStyleRowIcon

        val ghostEndY = (5 * itemH).toFloat()
        scroll.alpha      = 0f
        scroll.visibility = View.VISIBLE

        val targetH = getOrMeasurePanelHeight()
        val targetW = (280 * d).toInt()
        val lp      = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW  = lp.width
        val startH  = lp.height

        val barTargetRot    = floatArrayOf(90f, 90f, 90f)
        val barTargetScale  = floatArrayOf(1.0f, 1.0f, 1.0f)
        val barTargetTransX = floatArrayOf(0f, 0f, 0f)
        val barTargetTransY = floatArrayOf(0f, 0f, 0f)
        val barStartRot     = FloatArray(3) { hamburgerBars[it].rotation }
        val barStartScale   = FloatArray(3) { hamburgerBars[it].scaleX }
        val barStartTransX  = FloatArray(3) { hamburgerBars[it].translationX }
        val barStartTransY  = FloatArray(3) { hamburgerBars[it].translationY }
        val iconStartAlpha  = rowIcon.alpha

        mapStyleMenuAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
                ghost.translationY = ghostEndY * t
                ghost.alpha        = 1f - t
                scroll.alpha       = t
                content.alpha      = (1f - t / 0.7f).coerceIn(0f, 1f)
                rowIcon.alpha      = iconStartAlpha + (1f - iconStartAlpha) * t
                hamburgerBars.forEachIndexed { i, bar ->
                    bar.rotation     = barStartRot[i]    + (barTargetRot[i]    - barStartRot[i])    * t
                    bar.scaleX       = barStartScale[i]  + (barTargetScale[i]  - barStartScale[i])  * t
                    bar.translationX = barStartTransX[i] + (barTargetTransX[i] - barStartTransX[i]) * t
                    bar.translationY = barStartTransY[i] + (barTargetTransY[i] - barStartTransY[i]) * t
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ghost.visibility = View.GONE
                    ghost.alpha      = 0f
                    content.visibility = View.GONE
                    content.alpha      = 0f
                    rowIcon.alpha      = 1f
                }
            })
            start()
        })
    }

    /**
     * Expands the menu panel to 50% screen width × full screen height (minus margins),
     * and slides all non-menu Explore chrome off screen.
     *
     * Called when [MapUiState.isInMapStyleMode] transitions to true.
     */
    private fun runEnterMapStyleMode() {
        mapStyleModeAnimator?.cancel()
        val d         = resources.displayMetrics.density
        val btnMargin = (16 * d).toInt()
        val screenW   = resources.displayMetrics.widthPixels
        val screenH   = resources.displayMetrics.heightPixels
        val targetW   = screenW / 2
        val targetH   = screenH - 2 * btnMargin

        val lp     = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW = lp.width
        val startH = lp.height

        val w    = screenW.toFloat()
        val h    = screenH.toFloat()
        val dur  = 350L
        val intr = DecelerateInterpolator()

        // Slide chrome off screen.
        myLocationButton.animate().translationX(-w).setDuration(dur).setInterpolator(intr)
            .withEndAction { myLocationButton.visibility = View.GONE; myLocationButton.translationX = 0f }
            .start()
        exploreBottomBar.animate().translationY(h).setDuration(dur).setInterpolator(intr)
            .withEndAction { exploreBottomBar.visibility = View.GONE; exploreBottomBar.translationY = 0f }
            .start()
        topRightContainer.animate().translationX(w).setDuration(dur).setInterpolator(intr)
            .withEndAction { topRightContainer.visibility = View.GONE; topRightContainer.translationX = 0f }
            .start()
        versionCardView.animate().translationX(w).setDuration(dur).setInterpolator(intr)
            .withEndAction { versionCardView.visibility = View.GONE; versionCardView.translationX = 0f }
            .start()

        // Expand panel.
        mapStyleModeAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = dur
            interpolator = intr
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
            }
            start()
        })
    }

    /**
     * Collapses the menu panel back to the map style submenu size and
     * slides Explore chrome back on screen.
     *
     * Called when [MapUiState.isInMapStyleMode] transitions to false.
     */
    private fun runExitMapStyleMode() {
        mapStyleModeAnimator?.cancel()
        val (targetW, targetH) = getOrMeasureMapStyleSize()

        val lp     = menuPanel.layoutParams as FrameLayout.LayoutParams
        val startW = lp.width
        val startH = lp.height

        val w    = resources.displayMetrics.widthPixels.toFloat()
        val h    = resources.displayMetrics.heightPixels.toFloat()
        val dur  = 350L
        val intr = DecelerateInterpolator()

        // Slide chrome back in (only from EXPLORE mode).
        if (viewModel.uiState.value.mode == AppMode.EXPLORE) {
            myLocationButton.translationX = -w
            myLocationButton.visibility   = View.VISIBLE
            myLocationButton.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()

            exploreBottomBar.translationY = h
            exploreBottomBar.visibility   = View.VISIBLE
            exploreBottomBar.animate().translationY(0f).setDuration(dur).setInterpolator(intr).start()

            topRightContainer.translationX = w
            topRightContainer.visibility   = View.VISIBLE
            topRightContainer.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()

            versionCardView.translationX = w
            versionCardView.visibility   = View.VISIBLE
            versionCardView.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()
        }

        // Collapse panel.
        mapStyleModeAnimator = animBag.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = dur
            interpolator = intr
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width  = (startW + (targetW - startW) * t).toInt()
                lp.height = (startH + (targetH - startH) * t).toInt()
                menuPanel.layoutParams = lp
            }
            start()
        })
    }

    // ── Tile select mode ──────────────────────────────────────────────────────

    /**
     * Slide non-menu Explore chrome to the screen edges and reveal the tile grid.
     *
     * Called when [MapUiState.isInTileSelectMode] transitions to true.
     * The menu panel is NOT moved — it stays open and acts as the control surface
     * for the offline maps submenu (Download button lives there).
     */
    private fun runEnterTileSelectMode() {
        val w    = resources.displayMetrics.widthPixels.toFloat()
        val h    = resources.displayMetrics.heightPixels.toFloat()
        val dur  = 300L
        val intr = DecelerateInterpolator()

        // ── Camera: save current position, fly to overview zoom, lock zoom ─────
        map?.let { m ->
            tileSelectSavedCamera = m.cameraPosition
            m.uiSettings.isZoomGesturesEnabled      = false
            m.uiSettings.isDoubleTapGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled    = false
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(m.cameraPosition.target)
                        .zoom(5.8)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                800,
            )
        }

        // ── Slide Explore chrome off screen (menu panel stays put) ────────────
        myLocationButton.animate().translationX(-w).setDuration(dur).setInterpolator(intr)
            .withEndAction { myLocationButton.visibility = View.GONE; myLocationButton.translationX = 0f }
            .start()

        exploreBottomBar.animate().translationY(h).setDuration(dur).setInterpolator(intr)
            .withEndAction { exploreBottomBar.visibility = View.GONE; exploreBottomBar.translationY = 0f }
            .start()

        topRightContainer.animate().translationX(w).setDuration(dur).setInterpolator(intr)
            .withEndAction { topRightContainer.visibility = View.GONE; topRightContainer.translationX = 0f }
            .start()

        versionCardView.animate().translationX(w).setDuration(dur).setInterpolator(intr)
            .withEndAction { versionCardView.visibility = View.GONE; versionCardView.translationX = 0f }
            .start()

        // ── Add tile grid MapLibre layers ──────────────────────────────────────
        addTileGridLayers()

        // ── Slide in tile info card ────────────────────────────────────────────
        updateTileSelectCard()
        tileSelectCard.translationX = w
        tileSelectCard.alpha = 1f
        tileSelectCard.visibility = View.VISIBLE
        tileSelectCard.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()
    }

    /**
     * Slide Explore chrome back on screen and hide the tile grid.
     *
     * Called when [MapUiState.isInTileSelectMode] transitions to false.
     * If a download is running the tile card remains visible as a progress indicator.
     */
    private fun runExitTileSelectMode() {
        val w    = resources.displayMetrics.widthPixels.toFloat()
        val h    = resources.displayMetrics.heightPixels.toFloat()
        val dur  = 300L
        val intr = DecelerateInterpolator()

        // ── Camera: unlock zoom and restore saved position ─────────────────────
        map?.let { m ->
            m.uiSettings.isZoomGesturesEnabled      = true
            m.uiSettings.isDoubleTapGesturesEnabled = true
            m.uiSettings.isRotateGesturesEnabled    = true
            tileSelectSavedCamera?.let { saved ->
                m.animateCamera(CameraUpdateFactory.newCameraPosition(saved), 600)
            }
            tileSelectSavedCamera = null
        }

        // ── Slide Explore chrome back in (only from EXPLORE mode) ─────────────
        if (viewModel.uiState.value.mode == AppMode.EXPLORE) {
            myLocationButton.translationX = -w
            myLocationButton.visibility = View.VISIBLE
            myLocationButton.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()

            exploreBottomBar.translationY = h
            exploreBottomBar.visibility = View.VISIBLE
            exploreBottomBar.animate().translationY(0f).setDuration(dur).setInterpolator(intr).start()

            topRightContainer.translationX = w
            topRightContainer.visibility = View.VISIBLE
            topRightContainer.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()

            versionCardView.translationX = w
            versionCardView.visibility = View.VISIBLE
            versionCardView.animate().translationX(0f).setDuration(dur).setInterpolator(intr).start()
        }

        // ── Remove tile grid MapLibre layers ──────────────────────────────────
        removeTileGridLayers()

        // ── Hide tile info card — unless a download is running ─────────────────
        if (tileDownloadJob?.isActive != true) {
            tileSelectCard.animate().translationX(w).setDuration(dur)
                .withEndAction { tileSelectCard.visibility = View.GONE; tileSelectCard.translationX = 0f }
                .start()
        }
    }

    // ── Tile grid MapLibre layer management ────────────────────────────────────

    /** Build polygon features for all currently visible z8 tiles (grid lines source). */
    private fun buildGridLineFeatures(): FeatureCollection {
        val m = map ?: return FeatureCollection.fromFeatures(emptyList())
        val bounds = m.projection.visibleRegion.latLngBounds
        val z = tileGridOverlay.gridZoom
        val xMin = tileGridOverlay.lonToTile(bounds.longitudeWest,  z)
        val xMax = tileGridOverlay.lonToTile(bounds.longitudeEast,  z)
        val yMin = tileGridOverlay.latToTile(bounds.latitudeNorth,  z)
        val yMax = tileGridOverlay.latToTile(bounds.latitudeSouth,  z)
        val features = mutableListOf<Feature>()
        for (x in xMin..xMax) {
            for (y in yMin..yMax) {
                val west  = tileGridOverlay.tileToLon(x,     z)
                val east  = tileGridOverlay.tileToLon(x + 1, z)
                val north = tileGridOverlay.tileToLat(y,     z)
                val south = tileGridOverlay.tileToLat(y + 1, z)
                features.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(listOf(
                    Point.fromLngLat(west, north), Point.fromLngLat(east, north),
                    Point.fromLngLat(east, south), Point.fromLngLat(west, south),
                    Point.fromLngLat(west, north),
                )))))
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /** Add the tile grid MapLibre line layer + selection fill View. */
    private fun addTileGridLayers() {
        val s = style ?: return
        s.addSource(GeoJsonSource(SOURCE_TILE_GRID_LINES, buildGridLineFeatures()))
        val lineLayer = LineLayer(LAYER_TILE_GRID_LINE, SOURCE_TILE_GRID_LINES).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.argb(77, 0, 0, 0)),
            PropertyFactory.lineWidth(1f),
        )
        val firstSymbol = s.layers.firstOrNull { it is SymbolLayer }
        if (firstSymbol != null) s.addLayerBelow(lineLayer, firstSymbol.id)
        else s.addLayer(lineLayer)

        tileGridOverlay.visibility = View.VISIBLE
        tileGridOverlay.invalidate()
    }

    /** Remove the tile grid line layer + hide selection fill View. */
    private fun removeTileGridLayers() {
        style?.removeLayer(LAYER_TILE_GRID_LINE)
        style?.removeSource(SOURCE_TILE_GRID_LINES)
        tileGridOverlay.visibility = View.GONE
    }

    /** On camera idle: refresh grid lines for newly visible tiles; redraw fills. */
    private fun refreshTileGrid() {
        style?.getSourceAs<GeoJsonSource>(SOURCE_TILE_GRID_LINES)
            ?.setGeoJson(buildGridLineFeatures())
    }

    // ───────────────────────────────────────────────────────────────────────────

    /** Update the tile selection info card and the submenu Download label. */
    private fun updateTileSelectCard() {
        if (tileDownloadJob?.isActive == true) {
            // Apply running — card is already showing progress; just sync the label.
            val newLabel = "Cancel"
            if (menuPanelResult.offlineDownloadLabel.text != newLabel) {
                menuPanelResult.offlineDownloadLabel.text = newLabel
                offlineMapsMenuWidth  = -1
                offlineMapsMenuHeight = -1
            }
            return
        }
        val count = tileGridOverlay.countDownloadTiles()
        val d = resources.displayMetrics.density
        tileSelectCard.background = GradientDrawable().apply {
            setColor(Color.argb(200, 0, 0, 0))
            cornerRadius = 16 * d
        }
        val mb = count * 20.0 / 1024.0
        tileSelectCard.text = if (count == 0) {
            "No tiles selected"
        } else {
            "%d tiles · ~%.1f MB".format(count, mb)
        }
        // Delta: compare current selection against the saved (already-cached) selection.
        val savedRaw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_TILE_SELECTION, null)
        val savedCount = if (savedRaw.isNullOrEmpty()) 0
            else savedRaw.split(",").count { it.toIntOrNull() != null } * 5461
        val deltaMb = (count - savedCount) * 20.0 / 1024.0
        val newLabel = if (count == 0 && savedCount == 0) {
            "Apply"
        } else {
            "Apply (%+.1fMB)".format(deltaMb)
        }
        if (menuPanelResult.offlineDownloadLabel.text != newLabel) {
            menuPanelResult.offlineDownloadLabel.text = newLabel
            offlineMapsMenuWidth  = -1
            offlineMapsMenuHeight = -1
        }
    }

    /**
     * Start downloading all tiles covered by the current selection (z8–z14).
     *
     * The tile select card becomes a progress bar (same ClipDrawable technique as
     * the version card during APK download).  Tile select mode is exited immediately
     * so the map chrome slides back in while the download continues in the background.
     */
    private fun saveTileSelection() {
        val value = tileGridOverlay.selectedTiles.joinToString(",")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_TILE_SELECTION, value).apply()
    }

    private fun restoreTileSelection() {
        val value = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TILE_SELECTION, null)
        if (!value.isNullOrEmpty()) {
            tileGridOverlay.selectedTiles.clear()
            value.split(",").forEach { it.toIntOrNull()?.let { k -> tileGridOverlay.selectedTiles.add(k) } }
        }
    }

    /**
     * Apply the current tile selection: evict cached tiles outside the selection,
     * then download any missing tiles inside the selection.
     *
     * Progress is driven by the Choreographer frame callback which reads the
     * volatile [tileDownloadDone] / [tileDownloadBytes] fields every vsync.
     *
     * The apply is persisted via [PREF_APPLY_PENDING] so it resumes automatically
     * if the app is killed mid-download.
     */
    private fun applyTileSelection() {
        // Cancel any in-flight job; restart includes the updated selection.
        tileDownloadJob?.cancel()

        val keys  = tileGridOverlay.selectedTiles.toSet()   // snapshot
        val total = keys.size * 5461                        // exact, no set needed
        tileDownloadTotal = total
        tileDownloadDone  = 0
        tileDownloadBytes = 0L
        tileDownloadStartNs = System.nanoTime()
        osdLastTilePct    = -1

        // Mark as pending so we resume on app restart.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_APPLY_PENDING, true).apply()

        // Show progress card immediately.
        tileSelectCard.visibility = View.VISIBLE
        tileSelectCard.text = "Cleaning…"
        val d = resources.displayMetrics.density
        tileSelectCard.background = GradientDrawable().apply {
            setColor(Color.argb(200, 0, 0, 0))
            cornerRadius = 16 * d
        }

        tileDownloadJob = lifecycleScope.launch {
            // Phase 1 — evict tiles outside the selection.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                TileCache.evictTilesNotIn(keys)
            }

            // Phase 2 — download missing tiles inside the selection.
            if (keys.isEmpty()) {
                finishApply("Done")
                return@launch
            }

            // Set up the progress bar drawable; the Choreographer loop updates
            // the clip level and text every frame from volatile fields.
            val r      = 16f * d
            val base   = GradientDrawable().apply { setColor(Color.argb(200, 0, 0, 0)); cornerRadius = r }
            val accent = GradientDrawable().apply { setColor(Color.argb(220, 0, 80, 160)); cornerRadius = r }
            val clip   = ClipDrawable(accent, Gravity.START, ClipDrawable.HORIZONTAL)
            tileSelectCard.background = LayerDrawable(arrayOf(base, clip))
            tileDownloadClip = clip

            var done = 0
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (url in tileUrlSequence(keys)) {
                    if (!isActive) break
                    try {
                        val cacheResp = TileCache.httpClient.newCall(
                            Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build()
                        ).execute()
                        val cached = cacheResp.isSuccessful
                        cacheResp.close()
                        if (!cached) {
                            val resp = TileCache.httpClient.newCall(
                                Request.Builder().url(url).build()
                            ).execute()
                            val len = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                            resp.close()
                            tileDownloadBytes += len
                        }
                    } catch (_: Exception) { /* skip unreachable tile */ }
                    tileDownloadDone = ++done
                    // In offline mode, periodically nudge the camera so MapLibre
                    // re-requests tiles that are now available in cache.
                    if (TileCache.isOfflineMode && done % 100 == 0) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            map?.let { m -> m.easeCamera(CameraUpdateFactory.zoomBy(0.0), 1) }
                        }
                    }
                }
            }
            finishApply(if (isActive) "Done ($done tiles)" else "Cancelled")
        }

        viewModel.closeMenu()
    }

    /** Common completion for [applyTileSelection]: update card, clear pending flag. */
    private suspend fun finishApply(message: String) {
        saveTileSelection()
        updateOfflineBorder()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_APPLY_PENDING, false).apply()
        val d = resources.displayMetrics.density
        tileSelectCard.text = message
        tileSelectCard.background = GradientDrawable().apply {
            setColor(Color.argb(200, 0, 0, 0))
            cornerRadius = 16 * d
        }
        tileDownloadClip  = null
        tileDownloadJob   = null
        tileDownloadDone  = 0
        tileDownloadTotal = 0
        tileDownloadBytes = 0L
        osdLastTilePct    = -1
        delay(3_000)
        tileSelectCard.animate().alpha(0f).setDuration(300)
            .withEndAction { tileSelectCard.visibility = View.GONE; tileSelectCard.alpha = 1f }
            .start()
    }

    // ── Offline border ────────────────────────────────────────────────────────

    /**
     * Build border lines and label points from the current tile selection.
     *
     * For each selected z8 tile, each edge whose neighbour is NOT selected is a
     * border edge.  Edges are emitted as individual LineString features so
     * MapLibre can render them without a separate polygon-dissolve step.
     *
     * Label points are placed just outside the border, one per cardinal direction
     * (N/S/E/W) that has at least one border edge.  The average position of all
     * edges in that direction is used so the label ends up near the centre of
     * each face.
     *
     * Returns (borderLines, labelPoints) as two separate FeatureCollections so
     * they can be stored in separate GeoJSON sources and driven by different layer
     * types without any per-feature filtering.
     */
    private fun buildOfflineBorderData(): Pair<FeatureCollection, FeatureCollection> {
        val sel = tileGridOverlay.selectedTiles
        if (sel.isEmpty()) {
            val empty = FeatureCollection.fromFeatures(emptyList())
            return Pair(empty, empty)
        }
        val z = tileGridOverlay.gridZoom

        val lines = mutableListOf<Feature>()
        // Accumulate midpoints per cardinal direction for label placement.
        var nLon = 0.0; var nLat = 0.0; var nN = 0
        var sLon = 0.0; var sLat = 0.0; var sN = 0
        var wLon = 0.0; var wLat = 0.0; var wN = 0
        var eLon = 0.0; var eLat = 0.0; var eN = 0

        for (key in sel) {
            val x     = key / 256
            val y     = key % 256
            val west  = tileGridOverlay.tileToLon(x,     z)
            val east  = tileGridOverlay.tileToLon(x + 1, z)
            val north = tileGridOverlay.tileToLat(y,     z)
            val south = tileGridOverlay.tileToLat(y + 1, z)
            val midLon = (west + east)   / 2.0
            val midLat = (north + south) / 2.0

            // North neighbour absent → top edge is a border
            if (!sel.contains(x * 256 + (y - 1))) {
                lines.add(Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(west, north), Point.fromLngLat(east, north)))))
                nLon += midLon; nLat += north; nN++
            }
            // South neighbour absent → bottom edge is a border
            if (!sel.contains(x * 256 + (y + 1))) {
                lines.add(Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(west, south), Point.fromLngLat(east, south)))))
                sLon += midLon; sLat += south; sN++
            }
            // West neighbour absent → left edge is a border
            if (!sel.contains((x - 1) * 256 + y)) {
                lines.add(Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(west, north), Point.fromLngLat(west, south)))))
                wLon += west; wLat += midLat; wN++
            }
            // East neighbour absent → right edge is a border
            if (!sel.contains((x + 1) * 256 + y)) {
                lines.add(Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(east, north), Point.fromLngLat(east, south)))))
                eLon += east; eLat += midLat; eN++
            }
        }

        // Offset labels by ~40 % of a tile width/height outside the border.
        val tileW = 360.0 / (1 shl z)          // ~1.406° at z8
        val tileH = run {
            // Use average tile height at the median latitude of selected tiles.
            val ys = sel.map { it % 256 }
            val midY = ys.average().toInt().coerceIn(0, (1 shl z) - 1)
            tileGridOverlay.tileToLat(midY, z) - tileGridOverlay.tileToLat(midY + 1, z)
        }
        val offLon = tileW * 0.4
        val offLat = tileH * 0.4

        val labels = mutableListOf<Feature>()
        fun labelFeature(lon: Double, lat: Double) =
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).also {
                it.addStringProperty("label", "online map")
            }
        if (nN > 0) labels.add(labelFeature(nLon / nN, nLat / nN + offLat))
        if (sN > 0) labels.add(labelFeature(sLon / sN, sLat / sN - offLat))
        if (wN > 0) labels.add(labelFeature(wLon / wN - offLon, wLat / wN))
        if (eN > 0) labels.add(labelFeature(eLon / eN + offLon, eLat / eN))

        return Pair(
            FeatureCollection.fromFeatures(lines),
            FeatureCollection.fromFeatures(labels),
        )
    }

    /** Add offline border LineLayer + SymbolLayer to the current style. */
    private fun addOfflineBorderLayers() {
        val s = style ?: return
        val (borderFc, labelFc) = buildOfflineBorderData()
        val visible = !viewModel.uiState.value.isInTileSelectMode

        s.addSource(GeoJsonSource(SOURCE_OFFLINE_BORDER, borderFc))
        s.addSource(GeoJsonSource(SOURCE_OFFLINE_LABEL,  labelFc))

        val borderLayer = LineLayer(LAYER_OFFLINE_BORDER, SOURCE_OFFLINE_BORDER).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.argb(220, 255, 160, 0)),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
        )
        val labelLayer = SymbolLayer(LAYER_OFFLINE_LABEL, SOURCE_OFFLINE_LABEL).withProperties(
            PropertyFactory.textField("{label}"),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(android.graphics.Color.argb(255, 255, 160, 0)),
            PropertyFactory.textHaloColor(android.graphics.Color.argb(200, 0, 0, 0)),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textFont(arrayOf("Open Sans Semibold", "Arial Unicode MS Bold")),
            PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
        )

        // Insert below the first symbol layer so labels don't cover road names.
        val firstSymbol = s.layers.firstOrNull { it is SymbolLayer }
        if (firstSymbol != null) {
            s.addLayerBelow(borderLayer, firstSymbol.id)
            s.addLayerBelow(labelLayer,  firstSymbol.id)
        } else {
            s.addLayer(borderLayer)
            s.addLayer(labelLayer)
        }
    }

    /** Refresh the offline border GeoJSON sources from the current tile selection. */
    private fun updateOfflineBorder() {
        val s = style ?: return
        val (borderFc, labelFc) = buildOfflineBorderData()
        s.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_BORDER)?.setGeoJson(borderFc)
        s.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_LABEL)?.setGeoJson(labelFc)
    }

    /** Show or hide the offline border layers (e.g. hide during tile-select editing). */
    private fun setOfflineBorderVisible(visible: Boolean) {
        val vis = if (visible) Property.VISIBLE else Property.NONE
        style?.getLayerAs<LineLayer>(LAYER_OFFLINE_BORDER)
            ?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayerAs<SymbolLayer>(LAYER_OFFLINE_LABEL)
            ?.setProperties(PropertyFactory.visibility(vis))
    }

    /** Lazy sequence of all z8–z14 tile URLs for the given z8 key set. No upfront allocation. */
    private fun tileUrlSequence(keys: Set<Int>): Sequence<String> = sequence {
        val apiKey = BuildConfig.MAPTILER_KEY
        for (k in keys) {
            val x8 = k / 256
            val y8 = k % 256
            for (dz in 0..6) {
                val scale = 1 shl dz
                for (dx in 0 until scale) for (dy in 0 until scale) {
                    yield("https://api.maptiler.com/tiles/v3/${8 + dz}/${x8 * scale + dx}/${y8 * scale + dy}.pbf?key=$apiKey")
                }
            }
        }
    }

    private fun lonToTile(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    private fun latToTile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.051129, 85.051129))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom))
            .toInt().coerceIn(0, (1 shl zoom) - 1)
    }

    // ── Self-update ───────────────────────────────────────────────────────────

    /**
     * Silently checks for an update at most once every 24 hours on resume.
     * If an update is found, begins downloading it immediately.
     * If the APK is already downloaded, restores the green "update" card.
     */
    private fun checkUpdateIfDue() {
        downloadedApk?.let { setCardReady(); return }
        if (downloadJob?.isActive == true) return
        val lastCheck = prefs.getLong("last_update_check_ms", 0L)
        if (System.currentTimeMillis() - lastCheck < 24 * 60 * 60 * 1_000L) return
        if (!ConnectivityChecker.isStableOnline(this)) return
        lifecycleScope.launch {
            prefs.edit().putLong("last_update_check_ms", System.currentTimeMillis()).apply()
            val url = appUpdater.checkForUpdate() ?: return@launch
            appUpdater.cleanupStaleFiles(url)
            startDownload(url)
        }
    }

    /**
     * Tap handler for the version card.
     *
     * - Green card (download ready): opens the package installer.
     * - Download in progress: no-op (card already shows progress).
     * - Otherwise: checks GitHub and starts a download if a new version is found.
     */
    private fun onVersionCardTapped() {
        downloadedApk?.let { apk ->
            // On Android 8+, "Install unknown apps" must be enabled for this app
            // in Settings before the package installer will accept the intent.
            // If the permission is missing, open the settings page and let the
            // user enable it; they can tap the card again once they return.
            if (!packageManager.canRequestPackageInstalls()) {
                versionCardView.text = "allow in settings"
                versionCardView.isClickable = false
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
                return
            }
            versionCardView.text = "installing…"
            versionCardView.isClickable = false
            try {
                appUpdater.installApk(apk)
            } catch (_: Exception) {
                versionCardView.isClickable = true
                versionCardView.text = "error"
            }
            return
        }
        if (downloadJob?.isActive == true) return
        if (versionCardView.text.toString() != BuildConfig.GIT_COMMIT) return
        versionCardView.text = "checking…"
        lifecycleScope.launch {
            val url = appUpdater.checkForUpdate()
            if (url == null) {
                versionCardView.text = "up to date"
                delay(2_000)
                resetVersionCard()
                return@launch
            }
            appUpdater.cleanupStaleFiles(url)
            startDownload(url)
        }
    }

    /**
     * Downloads the APK at [url], showing progress on the version card.
     * On success the card turns green and shows "update".
     * On error the card shows "error" for 2 s then resets.
     */
    private fun startDownload(url: String) {
        if (downloadJob?.isActive == true) return
        downloadJob = lifecycleScope.launch {
            try {
                val apk = appUpdater.downloadApk(url) { p ->
                    val sizeText = "${formatMb(p.bytesDownloaded)}/${formatMb(p.totalBytes)} MB"
                    val pct = if (p.totalBytes > 0) (p.bytesDownloaded * 100 / p.totalBytes).toInt() else 0
                    versionCardView.text = sizeText
                    updateVersionCardProgress(pct)
                }
                downloadedApk = apk
                setCardReady()
            } catch (_: CancellationException) {
                resetVersionCard()
            } catch (_: Exception) {
                versionCardView.text = "error"
                delay(2_000)
                resetVersionCard()
            }
        }
    }

    /** Sets the version card to the green "ready to install" state. */
    private fun setCardReady() {
        val d = resources.displayMetrics.density
        versionCardView.isClickable = true   // re-enable in case we're returning from installer
        versionCardView.text = "update"
        val bg = GradientDrawable().apply {
            setColor(Color.argb(220, 0, 140, 60))
            cornerRadius = 16f * d
        }
        versionCardView.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            bg,
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE); cornerRadius = 16f * d },
        )
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
        versionCardView.background = LayerDrawable(arrayOf(base, clip))
        clip.level = pct * 100   // ClipDrawable level is 0–10000
    }

    private fun resetVersionCard() {
        val d = resources.displayMetrics.density
        versionCardView.text = BuildConfig.GIT_COMMIT
        versionCardView.background = GradientDrawable().apply {
            setColor(Color.argb(160, 0, 0, 0))
            cornerRadius = 16f * d
        }
    }

    private fun formatMb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

    /** Starts a coroutine that checks for updates every 5 minutes while the screen is on. */
    private fun startFrequentUpdatePolling() {
        stopFrequentUpdatePolling()
        frequentUpdateJob = lifecycleScope.launch {
            while (isActive) {
                if (downloadedApk == null && downloadJob?.isActive != true &&
                    ConnectivityChecker.isStableOnline(this@MapActivity)) {
                    val url = appUpdater.checkForUpdate()
                    if (url != null) {
                        appUpdater.cleanupStaleFiles(url)
                        startDownload(url)
                    }
                }
                delay(5 * 60 * 1_000L)
            }
        }
    }

    private fun stopFrequentUpdatePolling() {
        frequentUpdateJob?.cancel()
        frequentUpdateJob = null
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
        onFinish: (() -> Unit)? = null,
    ) {
        val resolvedPadding = padding ?: m.cameraPosition.padding ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val pos = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .tilt(tilt)
            .bearing(m.cameraPosition.bearing)
            .padding(resolvedPadding)
            .build()
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(pos),
            FLYTO_DURATION_MS,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() { onFinish?.invoke() }
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
     * Returns the current interpolated anchor position while a slide animation is in
     * progress, or `null` when the slide has finished (or was never started).
     *
     * Uses a smoothstep ease-in-out curve so the anchor accelerates away from the old
     * position and decelerates into the new one.  Clears [anchorSlideFrom] once the
     * animation is complete so subsequent calls are cheap.
     */
    private fun slideAnchor(): LatLng? {
        val from = anchorSlideFrom ?: return null
        val to   = dragLineAnchor  ?: return null
        val elapsedMs = (System.nanoTime() - anchorSlideStartNs) / 1_000_000.0
        if (elapsedMs >= ANCHOR_SLIDE_MS) {
            anchorSlideFrom = null
            return null
        }
        val t  = elapsedMs / ANCHOR_SLIDE_MS
        val st = t * t * (3.0 - 2.0 * t)   // smoothstep
        return LatLng(
            from.latitude  + (to.latitude  - from.latitude)  * st,
            from.longitude + (to.longitude - from.longitude) * st,
        )
    }

    /**
     * Draw (or update) the construction-tape drag line from [from] (puck) to [to] (anchor).
     *
     * The [DragLineAnimator] generates the animated geometry (sinusoidal wave that
     * decays to a straight line).
     *
     * Visual: yellow/black diagonal-stripe pattern (construction tape), dark outline.
     * The distance label is centred on the line and rotated to follow it.
     * A small pin marks the anchor point.
     *
     * Sources and layers are created on the first call; subsequent calls only push
     * new GeoJSON data.
     */
    private fun setDragLine(from: LatLng, to: LatLng, timeSec: Double) {
        val s = style ?: return

        val dx  = to.longitude - from.longitude
        val dy  = to.latitude  - from.latitude
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-10) return   // puck and anchor at the same spot

        val distKm = from.distanceTo(to) / 1000.0
        val label  = "${"%.1f".format(distKm).replace('.', ',')}km"

        // ── Animated line geometry ──────────────────────────────────────────
        // Perpendicular unit vector (in lat/lon space) for lateral displacement.
        val px = -dy / len
        val py =  dx / len

        val elapsedSec = (System.nanoTime() - anchorSetTimeNs) / 1_000_000_000.0
        val samples = dragLineAnimator.generate(elapsedSec)

        val linePoints: List<Point> = if (samples == null) {
            // Animation settled — use cheap 2-point straight line.
            dragLineSettled = true
            listOf(
                Point.fromLngLat(from.longitude, from.latitude),
                Point.fromLngLat(to.longitude,   to.latitude),
            )
        } else {
            samples.map { sample ->
                Point.fromLngLat(
                    from.longitude + sample.t * dx + sample.offset * len * px,
                    from.latitude  + sample.t * dy + sample.offset * len * py,
                )
            }
        }

        // ── Auxiliary features (anchor pin) ───────────────────────────────────
        val pinFeature = Feature.fromGeometry(Point.fromLngLat(to.longitude, to.latitude)).also {
            it.addBooleanProperty("is_pin", true)
        }

        // Label is embedded on the line feature so symbol-placement:line-center
        // renders it centered along the line, following its curvature.
        val lineFeature = Feature.fromGeometry(LineString.fromLngLats(linePoints)).also {
            it.addStringProperty("label", label)
        }
        val lineCollection = FeatureCollection.fromFeatures(listOf(lineFeature))
        val auxCollection  = FeatureCollection.fromFeatures(listOf(pinFeature))

        // Update existing sources (fast path — layers stay in place).
        val existingLine = s.getSourceAs<GeoJsonSource>(SOURCE_DRAG_LINE)
        if (existingLine != null) {
            existingLine.setGeoJson(lineCollection)
            s.getSourceAs<GeoJsonSource>(SOURCE_DRAG_LINE_AUX)?.setGeoJson(auxCollection)
            return
        }

        // ── First-time setup: register images, create sources and layers ──────
        val d = resources.displayMetrics.density
        s.addImage(IMAGE_TAPE_PATTERN, createTapePatternBitmap((20 * d).toInt()))

        s.addSource(GeoJsonSource(SOURCE_DRAG_LINE,     lineCollection, GeoJsonOptions().withSynchronousUpdate(true)))
        s.addSource(GeoJsonSource(SOURCE_DRAG_LINE_AUX, auxCollection,  GeoJsonOptions().withSynchronousUpdate(true)))

        // Insert drag-line layers below the location puck so the line's starting
        // point appears under the puck rather than flying in front of it.
        // The puck is rendered via several "maplibre-location-*" layers; we find
        // the bottom-most one and stack every drag-line layer just below it.
        val puckLayerIds = listOf(
            "maplibre-location-shadow-layer",
            "maplibre-location-accuracy-layer",
            "maplibre-location-background-layer",
            "maplibre-location-foreground-layer",
            "maplibre-location-bearing-layer",
        )
        val styleLayerIds = s.layers.map { it.id }.toHashSet()
        val belowId = puckLayerIds.firstOrNull { it in styleLayerIds }

        fun addDragLayer(layer: Layer) {
            if (belowId != null) s.addLayerBelow(layer, belowId) else s.addLayer(layer)
        }

        // ── Shadow layers (below casing) ─────────────────────────────────────
        // Two-layer umbra/penumbra model (research: cartographic shadows are
        // cool-tinted and semi-transparent; a rope on the ground casts a tight,
        // moderately-blurred shadow — not a large floating-object shadow).
        // Light source at upper-left (NW) = cartographic convention → shadow SE.
        // lineTranslateAnchor VIEWPORT keeps the SE direction fixed regardless
        // of map bearing.

        // Penumbra — wide, soft, very low opacity.
        addDragLayer(
            LineLayer(LAYER_DRAG_LINE_SHADOW_OUTER, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.lineColor("#141428"),
                    PropertyFactory.lineWidth(22f),
                    PropertyFactory.lineBlur(9f),
                    PropertyFactory.lineOpacity(0.13f),
                    PropertyFactory.lineTranslate(arrayOf(3f, 3f)),
                    PropertyFactory.lineTranslateAnchor(Property.LINE_TRANSLATE_ANCHOR_VIEWPORT),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Umbra — narrower, sharper, higher opacity.
        addDragLayer(
            LineLayer(LAYER_DRAG_LINE_SHADOW_INNER, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.lineColor("#141428"),
                    PropertyFactory.lineWidth(15f),
                    PropertyFactory.lineBlur(3f),
                    PropertyFactory.lineOpacity(0.27f),
                    PropertyFactory.lineTranslate(arrayOf(2f, 2f)),
                    PropertyFactory.lineTranslateAnchor(Property.LINE_TRANSLATE_ANCHOR_VIEWPORT),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Casing — dark border, 1.5dp wider on each side than the tape fill.
        addDragLayer(
            LineLayer(LAYER_DRAG_LINE_CASING, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.lineColor("#1A1A1A"),
                    PropertyFactory.lineWidth(13f),
                    PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Tape — yellow/black diagonal-stripe pattern (construction tape look).
        addDragLayer(
            LineLayer(LAYER_DRAG_LINE_FILL, SOURCE_DRAG_LINE).apply {
                setProperties(
                    PropertyFactory.linePattern(IMAGE_TAPE_PATTERN),
                    PropertyFactory.lineWidth(10f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            }
        )

        // Distance label — placed at the geometric centre of the line and rotated
        // to follow it, so it reads as part of the tape rather than floating above.
        addDragLayer(
            SymbolLayer(LAYER_DRAG_LINE_LABEL, SOURCE_DRAG_LINE).apply {
                setFilter(Expression.has("label"))
                setProperties(
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textSize(16f),
                    PropertyFactory.textColor("#FFCC00"),
                    PropertyFactory.textHaloColor("#1A1A1A"),
                    PropertyFactory.textHaloWidth(3f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textKeepUpright(true),
                    PropertyFactory.textMaxAngle(25f),
                )
            }
        )

        // Anchor pin — outer dark ring with yellow stroke.
        addDragLayer(
            CircleLayer(LAYER_DRAG_LINE_PIN_OUTER, SOURCE_DRAG_LINE_AUX).apply {
                setFilter(Expression.has("is_pin"))
                setProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor("#1A1A1A"),
                    PropertyFactory.circleOpacity(0.92f),
                    PropertyFactory.circleStrokeColor("#FFCC00"),
                    PropertyFactory.circleStrokeWidth(3f),
                )
            }
        )

        // Anchor pin — yellow inner dot (completes the pin look).
        addDragLayer(
            CircleLayer(LAYER_DRAG_LINE_PIN_INNER, SOURCE_DRAG_LINE_AUX).apply {
                setFilter(Expression.has("is_pin"))
                setProperties(
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleColor("#FFCC00"),
                    PropertyFactory.circleOpacity(1f),
                )
            }
        )
    }

    /**
     * Creates the diagonal-stripe tile used by the construction-tape line pattern.
     *
     * The tile is [tileSizePx]×[tileSizePx] pixels.  Each pixel's colour is
     * determined by `(x - y) mod tileSizePx`: yellow in the first half, dark in
     * the second half.  This produces 45° stripes that tile seamlessly along the
     * horizontal axis (= along the line direction in MapLibre's pattern space).
     */
    private fun createTapePatternBitmap(tileSizePx: Int): Bitmap {
        val half = tileSizePx / 2
        val bmp  = Bitmap.createBitmap(tileSizePx, tileSizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until tileSizePx) {
            for (y in 0 until tileSizePx) {
                val pos = ((x - y) % tileSizePx + tileSizePx) % tileSizePx
                bmp.setPixel(x, y,
                    if (pos < half) 0xFFFFCC00.toInt()   // yellow
                    else            0xFF1A1A1A.toInt()    // near-black
                )
            }
        }
        return bmp
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
     * Closes the menu and runs render benchmarks at zoom levels 8, 10, 14, 16.
     *
     * Uses central Tokyo (Shinjuku) as the benchmark location.
     *
     * For each zoom level:
     *  1. Pre-warms the tile cache by visiting 5 checkpoints across the movement
     *     area and waiting for the map to become idle (all tiles loaded) at each.
     *  2. Returns to the start position and waits for idle again.
     *  3. Runs a 5-second choreographed camera sequence — spin, NE/SW transit,
     *     orbital sweep, oscillating drift — with continuous bearing and tilt
     *     changes to maximise GPU and CPU load.
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
            val center = LatLng(35.6896, 139.7006)   // Shinjuku, Tokyo
            val checkpoints = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

            for (zoom in listOf(8, 10, 14, 16)) {

                // ── Pre-warm: visit key positions so tiles are in cache ────────
                for ((idx, tp) in checkpoints.withIndex()) {
                    updateBenchmarkStatus("Loading zoom $zoom… (${idx + 1}/${checkpoints.size})")
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(
                        benchmarkCamera(tp, zoom, center)
                    ))
                    delay(200)          // let the map register the move and start fetching
                    waitForMapIdle(20_000L)
                }

                // Return to t=0 and wait for full settle before measuring.
                updateBenchmarkStatus("Zoom $zoom ready…")
                m.moveCamera(CameraUpdateFactory.newCameraPosition(
                    benchmarkCamera(0.0, zoom, center)
                ))
                delay(200)
                waitForMapIdle(10_000L)

                // ── Timed benchmark run ───────────────────────────────────────
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

                val benchDurMs = 5_000L
                val benchStartMs = System.currentTimeMillis()
                while (true) {
                    val elapsedMs = System.currentTimeMillis() - benchStartMs
                    if (elapsedMs >= benchDurMs) break
                    val t = elapsedMs / benchDurMs.toDouble()
                    val secsLeft = ((benchDurMs - elapsedMs) / 1000L) + 1L
                    updateBenchmarkStatus("Zoom $zoom — ${secsLeft}s")
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(benchmarkCamera(t, zoom, center)),
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

                results.add(BenchmarkResult(
                    zoom    = zoom,
                    glStats = computeFpsStats(glNanos),
                    mainAvg = mainAvg, mainMin = mainMin, mainMax = mainMax,
                    dtAvgMs = dtAvg,  dtMinMs = dtMin,  dtMaxMs = dtMax,
                ))
            }

            dismissBenchmarkProgressOverlay()
            showBenchmarkResultsOverlay(results)
        }
    }

    /**
     * Returns a [CameraPosition] for the benchmark at fractional time [t] ∈ [0,1].
     *
     * Five phases, each covering 20% of the run:
     *   0.0–0.2  Spin in place: full 360° bearing rotation, tilt ramps 45°→60°.
     *   0.2–0.4  Drive NE: bearing sweeps 0°→180°, tilt holds 60°.
     *   0.4–0.6  Return SW: bearing sweeps 180°→360°, tilt eases back 60°→45°.
     *   0.6–0.8  Orbital circle: camera traces a half-radius circle, bearing tracks.
     *   0.8–1.0  SE drift with oscillating bearing ±90° and tilt ±15°.
     *
     * Movement radius is scaled per zoom level so each run crosses into new tile
     * territory without covering an unloadably large area.
     */
    private fun benchmarkCamera(t: Double, zoom: Int, center: LatLng): CameraPosition {
        val tau = 2.0 * Math.PI
        // Radii chosen so the path covers several tile-widths at each zoom level.
        val radius = when {
            zoom <= 8  -> 0.30   // ~33 km — covers a few zoom-8 tiles (156 km each)
            zoom <= 10 -> 0.10   // ~11 km — several zoom-10 tiles (39 km each)
            zoom <= 14 -> 0.008  //  ~900 m — several zoom-14 tiles (2.4 km each)
            else       -> 0.002  //  ~220 m — a few zoom-16 tiles (611 m each)
        }

        val lat: Double; val lng: Double; val bearing: Double; val tilt: Double
        when {
            t < 0.2 -> {   // spin in place, ramp tilt
                val s = t / 0.2
                lat = center.latitude;  lng = center.longitude
                bearing = s * 360.0;   tilt = 45.0 + s * 15.0
            }
            t < 0.4 -> {   // drive NE, bearing sweeps 0→180
                val s = (t - 0.2) / 0.2
                lat = center.latitude  + radius * s
                lng = center.longitude + radius * s
                bearing = s * 180.0;   tilt = 60.0
            }
            t < 0.6 -> {   // return SW, bearing sweeps 180→360, tilt eases back
                val s = (t - 0.4) / 0.2
                lat = center.latitude  + radius * (1.0 - s)
                lng = center.longitude + radius * (1.0 - s)
                bearing = 180.0 + s * 180.0;   tilt = 60.0 - s * 15.0
            }
            t < 0.8 -> {   // full orbital circle, bearing tracks orbit angle
                val s = (t - 0.6) / 0.2
                lat = center.latitude  + radius * 0.5 * sin(s * tau)
                lng = center.longitude + radius * 0.5 * cos(s * tau)
                bearing = s * 360.0;   tilt = 45.0
            }
            else    -> {   // SE drift with oscillating bearing and tilt
                val s = (t - 0.8) / 0.2
                lat = center.latitude  + radius * 0.5 * sin(s * tau * 2.0)
                lng = center.longitude + radius * s
                bearing = 180.0 + sin(s * tau * 1.5) * 90.0
                tilt = 45.0 + sin(s * tau * 2.0) * 15.0
            }
        }

        return CameraPosition.Builder()
            .target(LatLng(lat, lng))
            .zoom(zoom.toDouble())
            .bearing(bearing)
            .tilt(tilt.coerceIn(0.0, 60.0))
            .build()
    }

    /**
     * Suspends until MapLibre reports the map is idle (all tile loading complete
     * and no animations in progress), or until [timeoutMs] elapses.
     */
    private suspend fun waitForMapIdle(timeoutMs: Long = 20_000L) {
        val done = CompletableDeferred<Unit>()
        val listener = MapView.OnDidBecomeIdleListener { done.complete(Unit) }
        mapView.addOnDidBecomeIdleListener(listener)
        withTimeoutOrNull(timeoutMs) { done.await() }
        mapView.removeOnDidBecomeIdleListener(listener)
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
        val dm = resources.displayMetrics
        val sb = StringBuilder()

        sb.appendLine("aWayToGo Benchmark Results")
        sb.appendLine("=".repeat(44))
        sb.appendLine()

        sb.appendLine("Build")
        sb.appendLine("  commit   ${BuildConfig.GIT_COMMIT}")
        sb.appendLine("  built    ${BuildConfig.BUILD_TIME}")
        sb.appendLine()

        sb.appendLine("Device")
        sb.appendLine("  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("  Android ${android.os.Build.VERSION.RELEASE}  (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("  screen   ${dm.widthPixels}×${dm.heightPixels} px  ${dm.densityDpi} dpi  density ${dm.density}")
        sb.appendLine()

        sb.appendLine("MapLibre configuration")
        sb.appendLine("  sdk              android-sdk-opengl 13.0.0")
        sb.appendLine("  tile cache       200 MB disk  (maplibre_tiles/)")
        sb.appendLine("  max req/host     2  (OkHttp Dispatcher)")
        sb.appendLine("  tile gate        network interceptor — blocks new fetches during camera motion")
        sb.appendLine("  gate formats     .pbf  .jpg  .png  .webp")
        sb.appendLine("  gate timeout     2000 ms safety fallback")
        sb.appendLine()

        sb.appendLine("Benchmark setup")
        sb.appendLine("  location         Shinjuku, Tokyo  (35.6896 N  139.7006 E)")
        sb.appendLine("  zoom levels      8  10  14  16")
        sb.appendLine("  preload          5 checkpoints × onDidBecomeIdle  (≤ 20 s each)")
        sb.appendLine("  timed run        5 s per level")
        sb.appendLine("  camera sequence  spin → NE transit → SW return → orbit → SE drift")
        sb.appendLine("  tilt range       45°–60°  bearing: continuous rotation + reversals")
        sb.appendLine()

        sb.appendLine("Results")
        sb.appendLine("-".repeat(44))
        for (r in results) {
            sb.appendLine("Zoom ${r.zoom}")
            sb.appendLine("  GL:   avg ${"%.0f".format(r.glStats.avg)}  min ${"%.0f".format(r.glStats.min)}  max ${"%.0f".format(r.glStats.max)} fps")
            sb.appendLine("  Main: avg ${"%.0f".format(r.mainAvg)}  min ${"%.0f".format(r.mainMin)}  max ${"%.0f".format(r.mainMax)} fps")
            sb.appendLine("  dt:   avg ${r.dtAvgMs}  min ${r.dtMinMs}  max ${r.dtMaxMs} ms")
        }

        return sb.toString()
    }

    private fun saveBenchmarkResults(text: String) {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val filename = "bench_$ts.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(
            MediaStore.Downloads.getContentUri("external"), values
        )
        if (uri == null) {
            android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        android.widget.Toast.makeText(this, "Saved: $filename", android.widget.Toast.LENGTH_LONG).show()
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

        val saveBtn = TextView(this).apply {
            text = "Save to Downloads"
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

    /**
     * Handles the [PackageInstaller] status callback delivered via the PendingIntent
     * passed to [Session.commit].
     *
     * When the system is ready to show the install confirmation it sends
     * [PackageInstaller.STATUS_PENDING_USER_ACTION] back to this Activity with the
     * real installer intent in [Intent.EXTRA_INTENT].  We must call [startActivity]
     * on that intent to make the system confirmation dialog appear.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
                == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmIntent != null) startActivity(confirmIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        remoteControl.register()
        Choreographer.getInstance().postFrameCallback(frameCallback)
        // Re-register location listeners removed in onPause(); the map-ready
        // callback only registers them once, so after the first pause/resume
        // cycle updates would stop.
        val lm = locationManager
        if (lm != null) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 200L, 0f, rawLocationListener, mainLooper,
                )
            } catch (_: SecurityException) { }
        }
        // Re-register compass sensor for Course Up bearing fallback.
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { s ->
            sm.registerListener(compassListener, s, SensorManager.SENSOR_DELAY_GAME)
        }
        checkUpdateIfDue()
        if (viewModel.uiState.value.isFrequentUpdatesEnabled) startFrequentUpdatePolling()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        remoteControl.unregister()
        locationManager?.removeUpdates(rawLocationListener)
        sensorManager?.unregisterListener(compassListener)
        sensorManager = null
        mapView.onPause()
        stopFrequentUpdatePolling()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        animBag.cancelAll()
        downloadJob?.cancel()
        frequentUpdateJob?.cancel()
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

        // Invalidate the panel size caches — screen dimensions changed.
        panelFullHeight = -1
        settingsMenuHeight = -1; settingsMenuWidth = -1
        debugMenuHeight = -1;    debugMenuWidth = -1

        // Recalculate map lock panel margins so it stays centred after rotation.
        (mapLockPanel.parent as View).post {
            val d       = resources.displayMetrics.density
            val ringRad = (80 * d).toInt() / 2
            val parent  = mapLockPanel.parent as View
            val lp      = mapLockPanel.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = parent.width  / 2 - ringRad
            lp.topMargin  = parent.height / 2 - ringRad
            mapLockPanel.layoutParams = lp
        }

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

