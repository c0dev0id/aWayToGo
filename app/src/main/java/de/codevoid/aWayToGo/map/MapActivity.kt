package de.codevoid.aWayToGo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import de.codevoid.aWayToGo.BuildConfig
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

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hasLocationPermission = false

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

        MapLibre.getInstance(this)
        remoteControl = RemoteControlManager(this)

        val styleUrl =
            "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"

        // Root layout — no Compose, no View binding
        val root = FrameLayout(this)

        // MapView fills the screen.
        // SurfaceView mode (the default — do NOT pass textureMode): gives MapLibre its own
        // hardware layer, so the GL thread runs at the display's native refresh rate
        // independently of the main thread.
        mapView = MapView(this)
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

        setContentView(root)

        // MapView lifecycle must be driven manually (no Compose lifecycle observer here).
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled   = true
                isCompassEnabled        = true
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
                RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT ->
                    // Record vsync-clock start time; the Choreographer loop computes ramp from here.
                    panStartNs[event.key] = System.nanoTime()
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
                    m.locationComponent.lastKnownLocation?.let { loc ->
                        m.locationComponent.cameraMode = CameraMode.TRACKING
                        m.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(loc.latitude, loc.longitude),
                                14.0,
                            ),
                        )
                    }

                RemoteKey.BACK ->
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
