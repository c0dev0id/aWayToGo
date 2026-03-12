package de.codevoid.aWayToGo.map

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File

/**
 * Configures MapLibre's OkHttp client with a disk tile cache and a
 * [TileGateInterceptor] that pauses network tile fetches during camera motion.
 *
 * ### Why this helps
 *
 * MapLibre's rendering bottleneck during map load is the GPU upload step:
 * once a tile is decoded, its geometry must be uploaded to the GL thread
 * before it can appear on screen.  If many tiles arrive from the network at
 * the same time (e.g. after a fast pan into an uncached area), the GL thread
 * spends several milliseconds uploading them and drops frames.
 *
 * Two mitigations applied here:
 *
 * 1. **Disk cache** — tiles fetched once stay on-device for 24 h (or as long
 *    as MapTiler's `Cache-Control` headers allow).  Subsequent visits to the
 *    same area serve tiles from disk, which is orders of magnitude faster than
 *    the network, so uploads arrive in smaller bursts.
 *
 * 2. **Gate** — while the camera is moving the gate blocks *new* network
 *    fetches entirely.  The GL thread is free to render the already-uploaded
 *    tiles smoothly.  The moment the camera stops, the gate opens and MapLibre
 *    fills in the missing tiles.  Because the map is now static, tile uploads
 *    no longer interrupt visible frames.
 *
 * ### Usage
 *
 * Call [init] once, **after** `MapLibre.getInstance()` but **before** any
 * [MapView] is created or style loaded, so the custom client is in place
 * before the first HTTP request.  `MapLibre.getInstance()` itself makes no
 * network calls, so there is no window where the default client is used.
 *
 * ```kotlin
 * MapLibre.getInstance(this)
 * TileCache.init(this)
 * ```
 *
 * Then close/open the gate in response to camera events:
 *
 * ```kotlin
 * map.addOnCameraMoveStartedListener { TileCache.gate.pause() }
 * map.addOnCameraIdleListener        { TileCache.gate.resume() }
 * ```
 *
 * ### TODO: surrounding-area tile warmer
 *
 * When the camera becomes idle, proactively fetch tiles for a 3× viewport
 * buffer at the current zoom ± 1 so panning into adjacent areas is instant.
 * This requires computing tile XYZ coordinates from the extended bounding box
 * and firing OkHttp GET requests (which MapLibre never makes itself for
 * off-screen tiles) so they land in the disk cache ahead of time.
 * The logic needs the MapTiler tile URL template extracted from the loaded
 * style JSON — deferred until we can inspect the style source URLs at runtime.
 */
object TileCache {

    /** Exposes the gate so [MapActivity] can pause/resume it on camera events. */
    val gate = TileGateInterceptor()

    private var initialised = false
    private var diskCache: Cache? = null

    /**
     * Build the OkHttp client and hand it to MapLibre.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (initialised) return
        initialised = true

        val cache = Cache(
            directory = File(context.cacheDir, "maplibre_tiles"),
            maxSize   = 50L * 1024 * 1024,   // 50 MB
        )
        diskCache = cache

        val client = OkHttpClient.Builder()
            .cache(cache)
            // Network interceptor: runs AFTER OkHttp's cache check, so
            // disk-cached tiles bypass the gate and return immediately.
            .addNetworkInterceptor(gate)
            .build()

        HttpRequestUtil.setOkHttpClient(client)
    }

    /** Evicts all cached tiles. Call on an IO thread (performs disk I/O). */
    fun clearCache() {
        try { diskCache?.evictAll() } catch (_: Exception) { }
    }
}
