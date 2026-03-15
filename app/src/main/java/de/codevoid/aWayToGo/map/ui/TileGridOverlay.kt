package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Transparent overlay that draws the MapTiler v3 tile grid (z8–z14) over the map.
 *
 * - Selected tiles are highlighted with a 20 % blue accent.
 * - Grid alpha fades naturally when the fractional zoom is between two integers
 *   (tiles appear to cross-fade as the user zooms in or out).
 * - Touch events are NOT consumed here; tile selection is handled by the map's
 *   OnMapClickListener in MapActivity, which converts LatLng → tile coordinates
 *   and calls [toggleTile].
 *
 * Selections are stored at the z12 canonical zoom to allow zoom-invariant lookup:
 * each key = `x12 * 4096L + y12`.  Higher-zoom tiles (z13, z14) map to their
 * z12 ancestor; lower-zoom tiles (z8–z11) are "selected" if any of their z12
 * descendants are in the set.
 */
class TileGridOverlay(context: Context) : View(context) {

    var map: MapLibreMap? = null

    /** z12-canonical selected tiles. Key = x12 * 4096L + y12. */
    val selectedTiles: MutableSet<Long> = mutableSetOf()

    private val d = context.resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1f * d
        color       = Color.argb(140, 255, 255, 255)
    }
    private val selectedPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(51, 33, 150, 243)   // 20 % opacity #2196F3
    }

    init {
        isClickable  = false
        isFocusable  = false
    }

    override fun onDraw(canvas: Canvas) {
        val m    = map ?: return
        val proj = m.projection

        // Clamp display zoom to the range we download (z8–z14).
        val fracZoom  = m.cameraPosition.zoom.coerceIn(8.0, 14.0)
        val roundZoom = fracZoom.roundToInt()

        // Grid fades toward transparency mid-zoom so the cross-zoom switch looks smooth.
        val gridAlpha     = (1f - abs(fracZoom - roundZoom).toFloat() * 2.5f).coerceIn(0.2f, 1f)
        gridPaint.alpha     = (gridAlpha * 140).toInt()
        selectedPaint.alpha = (gridAlpha * 51).toInt()

        val bounds = proj.visibleRegion.latLngBounds
        val xMin = lonToTile(bounds.longitudeWest,  roundZoom)
        val xMax = lonToTile(bounds.longitudeEast,  roundZoom)
        val yMin = latToTile(bounds.latitudeNorth,  roundZoom)
        val yMax = latToTile(bounds.latitudeSouth,  roundZoom)

        for (x in xMin..xMax) {
            for (y in yMin..yMax) {
                val nw = proj.toScreenLocation(LatLng(tileToLat(y,     roundZoom), tileToLon(x,     roundZoom)))
                val se = proj.toScreenLocation(LatLng(tileToLat(y + 1, roundZoom), tileToLon(x + 1, roundZoom)))
                val rect = RectF(nw.x, nw.y, se.x, se.y)
                if (isTileSelected(roundZoom, x, y)) {
                    canvas.drawRect(rect, selectedPaint)
                }
                canvas.drawRect(rect, gridPaint)
            }
        }
    }

    fun isTileSelected(z: Int, x: Int, y: Int): Boolean {
        if (z >= 12) {
            val x12 = x shr (z - 12)
            val y12 = y shr (z - 12)
            return selectedTiles.contains(x12.toLong() * 4096L + y12)
        }
        // z < 12: any z12 descendant selected?
        val scale  = 1 shl (12 - z)
        val x12Min = x * scale
        val y12Min = y * scale
        for (dx in 0 until scale) {
            for (dy in 0 until scale) {
                if (selectedTiles.contains((x12Min + dx).toLong() * 4096L + (y12Min + dy))) return true
            }
        }
        return false
    }

    /** Toggle selection of the tile at display zoom (z, x, y). */
    fun toggleTile(z: Int, x: Int, y: Int) {
        if (z >= 12) {
            val x12 = x shr (z - 12)
            val y12 = y shr (z - 12)
            val key = x12.toLong() * 4096L + y12
            if (!selectedTiles.remove(key)) selectedTiles.add(key)
        } else {
            val scale   = 1 shl (12 - z)
            val x12Min  = x * scale
            val y12Min  = y * scale
            val addMode = !isTileSelected(z, x, y)
            for (dx in 0 until scale) {
                for (dy in 0 until scale) {
                    val key = (x12Min + dx).toLong() * 4096L + (y12Min + dy)
                    if (addMode) selectedTiles.add(key) else selectedTiles.remove(key)
                }
            }
        }
    }

    /**
     * Total tiles that would be downloaded for the current selection (z8–z14),
     * with ancestor tiles deduplicated (many z12 tiles share the same z8 ancestor).
     */
    fun countDownloadTiles(): Int {
        if (selectedTiles.isEmpty()) return 0
        val packed = mutableSetOf<Long>()
        for (key in selectedTiles) {
            val x12 = (key / 4096L).toInt()
            val y12 = (key % 4096L).toInt()
            // z12 itself
            packed.add(packTile(12, x12, y12))
            // z13, z14 descendants
            for (dz in 1..2) {
                val s = 1 shl dz
                for (dx in 0 until s) for (dy in 0 until s)
                    packed.add(packTile(12 + dz, x12 * s + dx, y12 * s + dy))
            }
            // z8–z11 ancestors (deduplicated via set)
            for (dz in 1..4) packed.add(packTile(12 - dz, x12 shr dz, y12 shr dz))
        }
        return packed.size
    }

    // ── Tile math ──────────────────────────────────────────────────────────────

    /** Pack (z, x, y) into a single Long for deduplication sets. */
    private fun packTile(z: Int, x: Int, y: Int): Long =
        z.toLong() * 100_000_000L + x.toLong() * 16_384L + y

    private fun tileToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    private fun tileToLon(x: Int, z: Int): Double =
        x.toDouble() / (1 shl z) * 360.0 - 180.0

    private fun lonToTile(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    private fun latToTile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.051129, 85.051129))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom))
            .toInt().coerceIn(0, (1 shl zoom) - 1)
    }
}
