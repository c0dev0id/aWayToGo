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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.maplibre.android.maps.MapLibreMapOptions
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
// withFrameMillis gives us the actual frame delta so this stays
// constant regardless of display refresh rate (60/90/120 Hz).
private const val PAN_SPEED_PX_PER_SEC = 350f
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

    // TextureView mode: MapLibre renders into a GL texture that Compose composites
    // in its own hardware layer, rather than using a separate SurfaceView layer.
    // This removes the per-frame system-compositor blend between two independent
    // hardware layers, which helps on slower devices.
    val mapView = remember {
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }

    // Coroutine scope for pan loops — tied to composition lifetime,
    // main dispatcher so MapLibre camera calls stay on the UI thread.
    val coroutineScope = rememberCoroutineScope()
    // One active Job per directional key; cancelled on key release.
    val panJobs = remember { mutableMapOf<RemoteKey, Job>() }

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

    // Handle remote control events
    LaunchedEffect(Unit) {
        remoteEvents.collect { event ->
            val m = map ?: return@collect
            when (event) {

                // Directional keys: start a frame-locked pan loop on press,
                // cancel it on release.
                is RemoteEvent.KeyDown -> when (event.key) {
                    RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                        val key = event.key
                        panJobs[key]?.cancel()
                        panJobs[key] = coroutineScope.launch {
                            // withFrameMillis suspends until the next vsync frame and
                            // provides the monotonic frame timestamp in milliseconds.
                            // We use the real frame delta to keep speed constant across
                            // 60/90/120 Hz displays.
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
                                    val px = speed * dtMs / 1000f

                                    val currentMap = map ?: return@withFrameMillis
                                    when (key) {
                                        RemoteKey.UP    -> currentMap.panByInstant(0f,  -px)
                                        RemoteKey.DOWN  -> currentMap.panByInstant(0f,   px)
                                        RemoteKey.LEFT  -> currentMap.panByInstant(-px,  0f)
                                        RemoteKey.RIGHT -> currentMap.panByInstant( px,  0f)
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                    else -> {} // other keys don't use KeyDown
                }

                is RemoteEvent.KeyUp -> {
                    panJobs.remove(event.key)?.cancel()
                }

                is RemoteEvent.ShortPress -> when (event.key) {
                    // Directional panning is handled via KeyDown/KeyUp above
                    RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {}

                    RemoteKey.ZOOM_IN ->
                        m.animateCamera(CameraUpdateFactory.zoomIn())
                    RemoteKey.ZOOM_OUT ->
                        m.animateCamera(CameraUpdateFactory.zoomOut())

                    RemoteKey.CONFIRM ->
                        // Re-centre on user and resume tracking
                        m.locationComponent.lastKnownLocation?.let { loc ->
                            m.locationComponent.cameraMode = CameraMode.TRACKING
                            m.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(loc.latitude, loc.longitude), 14.0
                                )
                            )
                        }

                    RemoteKey.BACK ->
                        // Reset bearing to north, keep current position and zoom
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
                        // Toggle tracking on/off
                        m.locationComponent.cameraMode =
                            if (m.locationComponent.cameraMode == CameraMode.NONE)
                                CameraMode.TRACKING
                            else
                                CameraMode.NONE
                    RemoteKey.BACK -> {
                        // Toggle 3D tilt mode
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
}

/**
 * Instantly reposition the map centre by [xPixels]/[yPixels] screen pixels.
 *
 * Uses moveCamera (no animation) so it can be called every vsync frame
 * without competing easing curves causing stutter.
 *
 * The geographic delta is computed directly from the Web Mercator scale at the
 * current zoom level — no projection.toScreenLocation / fromScreenLocation
 * round-trip required, saving two matrix operations per frame.
 *
 * Web Mercator ground resolution (metres/px) at zoom z:
 *   R = 156543.03392 * cos(lat) / 2^z
 * 1 degree of latitude  ≈ 111 320 m
 * 1 degree of longitude ≈ 111 320 * cos(lat) m
 */
private fun MapLibreMap.panByInstant(xPixels: Float, yPixels: Float) {
    val pos = cameraPosition
    val target = pos.target ?: return
    val latRad = Math.toRadians(target.latitude)
    val metersPerPx = 156543.03392 * cos(latRad) / Math.pow(2.0, pos.zoom)
    val latDelta  = -(yPixels * metersPerPx) / 111320.0
    val lngDelta  =  (xPixels * metersPerPx) / (111320.0 * cos(latRad))
    moveCamera(
        CameraUpdateFactory.newLatLng(
            LatLng(target.latitude + latDelta, target.longitude + lngDelta)
        )
    )
}
