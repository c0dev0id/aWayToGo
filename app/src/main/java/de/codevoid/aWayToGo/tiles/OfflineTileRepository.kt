package de.codevoid.aWayToGo.tiles

import de.codevoid.aWayToGo.BuildConfig
import de.codevoid.aWayToGo.map.TileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Domain layer for offline tile management.
 *
 * Encapsulates the three pure domain operations involved in applying an offline
 * tile selection: eviction, fetch, and URL generation.  All network I/O runs on
 * [Dispatchers.IO]; callers stay on their own coroutine context.
 *
 * ### What lives here
 * - [apply]: evict tiles outside the selection, then fetch missing tiles inside it
 * - [tileUrlSequence]: generates the full zoom-8…14 URL pyramid for a set of z8 keys
 * - [lonToTile] / [latToTile]: slippy-map tile coordinate conversions
 *
 * ### What does NOT live here
 * - Progress UI (ClipDrawable, card text) — stays in MapActivity
 * - MapLibre camera nudges — stays in MapActivity (requires a MapLibreMap reference)
 * - Choreographer-driven display — stays in MapActivity (performance-driven exception)
 */
class OfflineTileRepository {

    /**
     * Progress snapshot emitted after each tile attempt.
     *
     * [done] counts both cached hits and network fetches (tiles that failed are
     * also counted — they are skipped silently so the total always reaches [total]).
     */
    data class Progress(val done: Int, val total: Int, val bytes: Long)

    /**
     * Evicts tiles outside [keys] from the disk cache, then fetches all missing
     * tiles inside [keys] into the cache.
     *
     * [onProgress] is a **suspending** callback so the caller can switch context
     * (e.g. to [Dispatchers.Main] for a MapLibre camera nudge) without blocking
     * the IO fetch loop.
     *
     * - Network/HTTP errors per tile are silently skipped; the tile will be
     *   fetched live when the map renders it.
     * - Cancellation is honoured per tile; the cache is left in a consistent
     *   (partially-filled) state on cancellation.
     * - If [keys] is empty the function returns after eviction with no progress
     *   callbacks emitted.
     */
    suspend fun apply(keys: Set<Int>, onProgress: suspend (Progress) -> Unit) {
        withContext(Dispatchers.IO) {
            TileCache.evictTilesNotIn(keys)
            if (keys.isEmpty()) return@withContext

            var done  = 0
            var bytes = 0L
            val total = keys.size * 5461   // exact tile count across zoom 8..14

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
                        bytes += resp.header("Content-Length")?.toLongOrNull() ?: 0L
                        resp.close()
                    }
                } catch (_: Exception) { /* skip unreachable tile */ }
                done++
                onProgress(Progress(done, total, bytes))
            }
        }
    }

    /**
     * Generates the full tile URL pyramid (zoom 8 through 14, 5 461 tiles per z8
     * tile) for each z8 key in [keys].
     *
     * Keys are encoded as `x8 * 256 + y8` where (x8, y8) are the z8 tile coordinates.
     * Each dz step expands one z8 parent into (2^dz)² children using simple integer
     * arithmetic — no floating-point projection needed.
     */
    fun tileUrlSequence(keys: Set<Int>): Sequence<String> = sequence {
        val apiKey = BuildConfig.MAPTILER_KEY
        for (k in keys) {
            val x8 = k / 256
            val y8 = k % 256
            for (dz in 0..6) {
                val scale = 1 shl dz
                for (dx in 0 until scale) {
                    for (dy in 0 until scale) {
                        yield(
                            "https://api.maptiler.com/tiles/v3/" +
                            "${8 + dz}/${x8 * scale + dx}/${y8 * scale + dy}" +
                            ".pbf?key=$apiKey"
                        )
                    }
                }
            }
        }
    }

    /** Converts a longitude to a tile X coordinate at [zoom]. */
    fun lonToTile(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** Converts a latitude to a tile Y coordinate at [zoom] using the Web Mercator projection. */
    fun latToTile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.051129, 85.051129))
        return floor(
            (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)
        ).toInt().coerceIn(0, (1 shl zoom) - 1)
    }
}
