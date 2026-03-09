package de.codevoid.aWayToGo.diagnostic

import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import de.codevoid.aWayToGo.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Diagnostic activity: MapView in TextureView mode.
 *
 * Identical to DiagnosticActivity except MapView is created with
 * MapLibreMapOptions.textureMode(true).  TextureView renders into the
 * View hierarchy via a SurfaceTexture instead of an independent Surface;
 * this synchronises it with the standard Choreographer/Vsync pipeline and
 * avoids the extra HWC compositing step that SurfaceView requires.
 *
 * Compare the OSD numbers with DiagnosticActivity (SurfaceView mode):
 *   - Higher fps / lower dt here  → TextureView compositing latency is lower
 *     on this device; consider switching MapActivity to textureMode.
 *   - Similar numbers             → compositing mode makes no measurable
 *     difference; look elsewhere for the perceived fluency gap.
 *
 * Trade-off to keep in mind: TextureView copies the GL frame into a
 * SurfaceTexture on every vsync, adding a small extra GPU blit.  On most
 * modern hardware the copy is negligible; on a heavily loaded GPU it is not.
 *
 * Launch:
 *   adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticTextureActivity
 */
class DiagnosticTextureActivity : ComponentActivity() {

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

            osdView.text = "TextureView\nfps  $lastFps  dt:${lastDtMs}ms"

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json" +
                       "?key=${BuildConfig.MAPTILER_KEY}"

        val root = FrameLayout(this)

        // TextureView mode: pass MapLibreMapOptions with textureMode(true).
        // This makes MapLibre render through a SurfaceTexture that is
        // composited as part of the normal View hierarchy rather than as a
        // separate hardware layer.
        val options = MapLibreMapOptions.createFromAttributes(this).textureMode(true)
        mapView = MapView(this, options)
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
            text = "TextureView\nfps  --  dt:--ms"
        }
        val osdParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 48, 16, 0) }
        root.addView(osdView, osdParams)

        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.setStyle(styleUrl) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(48.1351, 11.5820), 12.0)
                )
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
