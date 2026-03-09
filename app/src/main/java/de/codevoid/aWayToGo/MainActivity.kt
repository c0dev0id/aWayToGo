package de.codevoid.aWayToGo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
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

class MainActivity : ComponentActivity() {

    private lateinit var remoteControl: RemoteControlManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        remoteControl = RemoteControlManager(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MapScreen(remoteEvents = remoteControl.events)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        remoteControl.register()
    }

    override fun onPause() {
        super.onPause()
        remoteControl.unregister()
    }
}

// Desired pan speed in screen pixels per second.
private const val PAN_SPEED_PX_PER_SEC = 350f

// How far ahead (ms) each animateCamera call targets.
// The GL thread interpolates this segment smoothly at its own refresh rate.
// On a slow main thread (e.g. 20 fps = 50ms frames) this ensures the GL
// renderer always has a live animation to play between main-thread updates.
private const val PAN_LOOK_AHEAD_MS = 150

private const val TILT_3D = 60.0

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(remoteEvents: SharedFlow<RemoteEvent>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    // SurfaceView mode (default — do NOT use textureMode here).
    //
    // SurfaceView gives MapLibre its own hardware layer that the GL thread
    // drives at the display's native refresh rate, completely independent of
    // the Compose main thread. TextureView ties GL presentation to the main
    // thread's composition pass, so a 20 fps main thread caps perceived
    // rendering to 20 fps even if the GL thread is rendering faster.
    val mapView = remember { MapView(context) }

    val coroutineScope = rememberCoroutineScope()
    val panJobs = remember { mutableMapOf<RemoteKey, Job>() }

    // ── OSD state (DEBUG builds only) ────────────────────────────────────────
    // osdUiFps  : Compose frame-clock fps  → main-thread health
    // osdFrameMs: last Compose frame delta  → jitter on the main thread
    // osdZoom   : current zoom level        → tile count / GPU load
    // osdPanSpeed: active pan speed px/s    → 0 when not panning
    var osdUiFps    by remember { mutableIntStateOf(0) }
    var osdFrameMs  by remember { mutableLongStateOf(0L) }
    var osdZoom     by remember { mutableFloatStateOf(0f) }
    var osdPanSpeed by remember { mutableFloatStateOf(0f) }

    // Request location permission on first composition
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    // Enable location component once style + permission are both ready
    LaunchedEffect(map, style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect

        m.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s).build()
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.TRACKING
            renderMode = RenderMode.COMPASS
        }

        m.locationComponent.lastKnownLocation?.let { location ->
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    14.0
                )
            )
        }
    }

    // Always-on Compose frame-rate sampler.
    // Only triggers recomposition when values actually change to avoid adding
    // overhead on top of an already-busy main thread.
    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            var lastFrameMs = 0L
            var frameCount = 0
            var windowStart = 0L
            while (true) {
                withFrameMillis { frameMs ->
                    val dt = if (lastFrameMs != 0L) frameMs - lastFrameMs else 16L
                    lastFrameMs = frameMs

                    // Frame delta — only write if changed by ≥ 2 ms to limit recompositions
                    if (kotlin.math.abs(dt - osdFrameMs) >= 2L) osdFrameMs = dt

                    // FPS — one update per second
                    if (windowStart == 0L) windowStart = frameMs
                    frameCount++
                    if (frameMs - windowStart >= 1000L) {
                        osdUiFps = frameCount
                        frameCount = 0
                        windowStart = frameMs
                    }

                    // Zoom — only write if changed by ≥ 0.05
                    val newZoom = map?.cameraPosition?.zoom?.toFloat() ?: 0f
                    if (kotlin.math.abs(newZoom - osdZoom) >= 0.05f) osdZoom = newZoom
                }
            }
        }
    }

    // Handle remote control events
    LaunchedEffect(Unit) {
        remoteEvents.collect { event ->
            val m = map ?: return@collect
            when (event) {

                is RemoteEvent.KeyDown -> when (event.key) {
                    RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                        val key = event.key
                        panJobs[key]?.cancel()
                        panJobs[key] = coroutineScope.launch {
                            var lastFrameMs = 0L
                            var elapsedMs = 0L
                            while (isActive) {
                                withFrameMillis { frameMs ->
                                    val dtMs = if (lastFrameMs == 0L) 16L
                                               else (frameMs - lastFrameMs).coerceAtMost(100L)
                                    lastFrameMs = frameMs
                                    elapsedMs += dtMs

                                    // Linear ramp: 50 % speed at t=0, 100 % at t=1 s.
                                    val ramp = (elapsedMs / 1000f).coerceAtMost(1f)
                                    val speed = PAN_SPEED_PX_PER_SEC * (0.5f + 0.5f * ramp)

                                    // Pixels to cover in the look-ahead window.
                                    // animateCamera carries the GL thread through this
                                    // distance smoothly at its own refresh rate, so
                                    // perceived motion is fluid even if the main thread
                                    // only fires at ~20 fps.
                                    val px = speed * PAN_LOOK_AHEAD_MS / 1000f

                                    if (BuildConfig.DEBUG) osdPanSpeed = speed

                                    val currentMap = map ?: return@withFrameMillis
                                    when (key) {
                                        RemoteKey.UP    -> currentMap.panByAnimated(0f,  -px, PAN_LOOK_AHEAD_MS)
                                        RemoteKey.DOWN  -> currentMap.panByAnimated(0f,   px, PAN_LOOK_AHEAD_MS)
                                        RemoteKey.LEFT  -> currentMap.panByAnimated(-px,  0f, PAN_LOOK_AHEAD_MS)
                                        RemoteKey.RIGHT -> currentMap.panByAnimated( px,  0f, PAN_LOOK_AHEAD_MS)
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }

                is RemoteEvent.KeyUp -> {
                    panJobs.remove(event.key)?.cancel()
                    // Stop the in-flight animation immediately so the map doesn't
                    // coast past where the key was released.
                    m.cancelTransitions()
                    if (BuildConfig.DEBUG) osdPanSpeed = 0f
                }

                is RemoteEvent.ShortPress -> when (event.key) {
                    RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {}

                    RemoteKey.ZOOM_IN ->
                        m.animateCamera(CameraUpdateFactory.zoomIn())
                    RemoteKey.ZOOM_OUT ->
                        m.animateCamera(CameraUpdateFactory.zoomOut())

                    RemoteKey.CONFIRM ->
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            m.locationComponent.cameraMode = CameraMode.TRACKING
                            m.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(loc.latitude, loc.longitude), 14.0
                                )
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
                                    .build()
                            )
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
                                    .build()
                            )
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    // Wire MapView lifecycle to the Compose lifecycle owner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onCreate(null)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { m ->
                        m.uiSettings.apply {
                            isRotateGesturesEnabled = true
                            isTiltGesturesEnabled = true
                            isCompassEnabled = true
                        }
                        m.setStyle(styleUrl) { s ->
                            map = m
                            style = s
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Performance OSD (DEBUG builds only) ──────────────────────────────
        // UI fps — Compose frame-clock rate. If low, the main thread is the
        //          bottleneck (too much work per frame or GC pauses).
        // dt     — Compose frame delta in ms. Spikes = main-thread jitter.
        // zoom   — Current zoom. Lower zoom = more tiles = more GPU work.
        // pan    — Active pan speed px/s (only while a key is held).
        if (BuildConfig.DEBUG) {
            val panLine = if (osdPanSpeed > 0f) "\npan  ${"%.0f".format(osdPanSpeed)} px/s" else ""
            Text(
                text = "UI   ${osdUiFps} fps  dt:${osdFrameMs}ms\nzoom ${"%.1f".format(osdZoom)}$panLine",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Animate the map centre by [xPixels]/[yPixels] screen pixels over [durationMs].
 *
 * Unlike moveCamera (instant), animateCamera hands the interpolation to the
 * GL thread, which runs at the display's native refresh rate independently of
 * the Compose main thread. On a slow main thread (e.g. 20 fps), this means
 * perceived panning remains smooth even though camera targets only arrive at
 * 20 fps — the GL renderer fills in the in-between frames.
 *
 * Geographic delta is computed via Web Mercator resolution arithmetic to avoid
 * calling projection.toScreenLocation / fromScreenLocation each frame.
 */
private fun MapLibreMap.panByAnimated(xPixels: Float, yPixels: Float, durationMs: Int) {
    val pos = cameraPosition
    val target = pos.target ?: return
    val latRad = Math.toRadians(target.latitude)
    val metersPerPx = 156543.03392 * cos(latRad) / Math.pow(2.0, pos.zoom)
    val latDelta = -(yPixels * metersPerPx) / 111320.0
    val lngDelta =  (xPixels * metersPerPx) / (111320.0 * cos(latRad))
    animateCamera(
        CameraUpdateFactory.newLatLng(
            LatLng(target.latitude + latDelta, target.longitude + lngDelta)
        ),
        durationMs
    )
}
