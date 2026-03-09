package de.codevoid.aWayToGo.diagnostic

import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.codevoid.aWayToGo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Diagnostic activity: OpenMapTiles / MapTiler 3D building style.
 *
 * Style source: https://github.com/openmaptiles/maptiler-3d-gl-style
 *
 * The style JSON uses {key} placeholders for the MapTiler API key, so it
 * cannot be used as a plain URL.  This activity fetches the raw JSON at
 * startup, substitutes the API key, and passes the modified JSON string
 * directly to MapLibre's setStyle().
 *
 * Compare with [DiagnosticActivity] (outdoor-v2) to see whether a heavier
 * 3D style (building extrusions, more layers) has a measurable fps/dt impact.
 *
 * Launch:
 *   adb shell am start -n de.codevoid.aWayToGo/.diagnostic.Diagnostic3dStyleActivity
 */
class Diagnostic3dStyleActivity : ComponentActivity() {

    companion object {
        private const val STYLE_URL =
            "https://raw.githubusercontent.com/openmaptiles/maptiler-3d-gl-style/master/style.json"
    }

    private lateinit var mapView: MapView
    private lateinit var osdView: TextView

    private var lastFrameNs = 0L
    private var frameCount = 0
    private var windowStartNs = 0L
    private var lastFps = 0
    private var lastDtMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) {
                lastDtMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
            }
            lastFrameNs = frameTimeNanos

            frameCount++
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            val windowNs = frameTimeNanos - windowStartNs
            if (windowNs >= 1_000_000_000L) {
                lastFps = (frameCount * 1_000_000_000L / windowNs).toInt()
                frameCount = 0
                windowStartNs = frameTimeNanos
            }

            osdView.text = "3D Style\nfps  $lastFps  dt:${lastDtMs}ms"

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val root = FrameLayout(this)

        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        osdView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            text = "3D Style\nloading style…"
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) })

        setContentView(root)

        mapView.onCreate(savedInstanceState)

        // Fetch the style JSON on an IO thread, substitute the API key, then
        // load it into the map on the main thread.
        lifecycleScope.launch {
            val styleJson = fetchAndPatchStyle()
            if (styleJson == null) {
                osdView.text = "3D Style\nERROR: style fetch failed"
                return@launch
            }
            mapView.getMapAsync { map ->
                map.setStyle(styleJson) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), 14.0)
                    )
                }
            }
        }
    }

    /**
     * Downloads the raw style JSON and replaces every `{key}` placeholder
     * with the MapTiler API key from [BuildConfig].
     * Returns null on network error.
     */
    private suspend fun fetchAndPatchStyle(): String? = withContext(Dispatchers.IO) {
        try {
            val client   = OkHttpClient()
            val request  = Request.Builder().url(STYLE_URL).build()
            val body     = client.newCall(request).execute().use { it.body?.string() }
                           ?: return@withContext null
            body.replace("{key}", BuildConfig.MAPTILER_KEY)
        } catch (e: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
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
