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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.PointF
import de.codevoid.aWayToGo.remote.RemoteControlManager
import de.codevoid.aWayToGo.remote.RemoteEvent
import de.codevoid.aWayToGo.remote.RemoteKey
import kotlinx.coroutines.flow.SharedFlow
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

private const val PAN_PIXELS = 300f
private const val PAN_DURATION_MS = 200
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

    val mapView = remember { MapView(context) }

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
                is RemoteEvent.ShortPress -> when (event.key) {
                    RemoteKey.UP    -> m.panBy(0f, -PAN_PIXELS, PAN_DURATION_MS)
                    RemoteKey.DOWN  -> m.panBy(0f,  PAN_PIXELS, PAN_DURATION_MS)
                    RemoteKey.LEFT  -> m.panBy(-PAN_PIXELS, 0f, PAN_DURATION_MS)
                    RemoteKey.RIGHT -> m.panBy( PAN_PIXELS, 0f, PAN_DURATION_MS)
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
                is RemoteEvent.KeyDown, is RemoteEvent.KeyUp -> { /* unused at map level */ }
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
 * Pan the map by a given number of screen pixels with animation.
 *
 * MapLibre 11.x removed CameraUpdateFactory.scrollBy(). This extension
 * converts the current map centre to screen coordinates, offsets by the
 * requested pixel delta, then converts back to LatLng and animates there.
 */
private fun MapLibreMap.panBy(xPixels: Float, yPixels: Float, durationMs: Int) {
    // cameraPosition.target is nullable in MapLibre 11.x — bail if no camera target yet
    val target = cameraPosition.target ?: return
    val center = projection.toScreenLocation(target)
    val newCenter = PointF(center.x + xPixels, center.y + yPixels)
    animateCamera(
        CameraUpdateFactory.newLatLng(projection.fromScreenLocation(newCenter)),
        durationMs
    )
}
