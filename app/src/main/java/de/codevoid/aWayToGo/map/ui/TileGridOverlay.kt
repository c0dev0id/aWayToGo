package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Transparent overlay that draws the MapTiler v3 tile grid at a fixed z8 zoom
 * over the map during offline-download mode.
 *
 * The map is locked to zoom ~5.8 and north-up (bearing 0) while this overlay is
 * active, so longitude maps to a constant screen X and latitude maps to a constant
 * screen Y. This lets onDraw compute (cols+1)+(rows+1) projection calls instead of
 * 2×cols×rows, and draw straight grid lines instead of per-tile rectangles.
 *
 * - Selected tiles are highlighted with a 20 % blue accent.
 * - Touch events are NOT consumed here; tile selection is handled by the map's
 *   OnMapClickListener in MapActivity, which converts LatLng → tile coordinates
 *   and calls [toggleTile].
 *
 * Selections are stored at the z12 canonical zoom to allow zoom-invariant lookup:
 * each key = `x12 * 4096L + y12`.  z8 tiles map to their z12 descendants when
 * downloading (1 z8 tile → 16 z12 tiles).
 */
class TileGridOverlay(context: Context) : View(context) {

    var map: MapLibreMap? = null

    /** z12-canonical selected tiles. Key = x12 * 4096L + y12. */
    val selectedTiles: MutableSet<Long> = mutableSetOf()

    /** Tile zoom level used for both rendering and selection. */
    val gridZoom = 8

    private val d = context.resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1f * d
        color       = Color.argb(77, 0, 0, 0)   // black 30 %
    }
    private val selectedPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(51, 33, 150, 243)     // blue 20 %
    }

    init {
        isClickable  = false
        isFocusable  = false
    }

    override fun onDraw(canvas: Canvas) {
        val m    = map ?: return
        val proj = m.projection

        val bounds = proj.visibleRegion.latLngBounds
        val xMin = lonToTile(bounds.longitudeWest,  gridZoom)
        val xMax = lonToTile(bounds.longitudeEast,  gridZoom)
        val yMin = latToTile(bounds.latitudeNorth,  gridZoom)
        val yMax = latToTile(bounds.latitudeSouth,  gridZoom)

        // With north-up + fixed zoom, longitude → screen X and latitude → screen Y
        // are independent axes. Pre-compute one array per axis.
        val screenX = FloatArray(xMax - xMin + 2) { i ->
            proj.toScreenLocation(LatLng(0.0, tileToLon(xMin + i, gridZoom))).x
        }
        val screenY = FloatArray(yMax - yMin + 2) { i ->
            proj.toScreenLocation(LatLng(tileToLat(yMin + i, gridZoom), 0.0)).y
        }

        // Selected fills
        for (x in xMin..xMax) {
            val sx0 = screenX[x - xMin]
            val sx1 = screenX[x - xMin + 1]
            for (y in yMin..yMax) {
                if (isTileSelected(x, y)) {
                    canvas.drawRect(sx0, screenY[y - yMin], sx1, screenY[y - yMin + 1], selectedPaint)
                }
            }
        }

        // Grid lines — straight vertical and horizontal strokes
        val top    = screenY.first()
        val bottom = screenY.last()
        val left   = screenX.first()
        val right  = screenX.last()
        for (sx in screenX) canvas.drawLine(sx, top,  sx, bottom, gridPaint)
        for (sy in screenY) canvas.drawLine(left, sy, right, sy,  gridPaint)
    }

    fun isTileSelected(x: Int, y: Int): Boolean {
        val scale  = 1 shl (12 - gridZoom)
        val x12Min = x * scale
        val y12Min = y * scale
        for (dx in 0 until scale) {
            for (dy in 0 until scale) {
                if (selectedTiles.contains((x12Min + dx).toLong() * 4096L + (y12Min + dy))) return true
            }
        }
        return false
    }

    /** Toggle selection of the z8 tile at (x, y). */
    fun toggleTile(x: Int, y: Int) {
        val scale   = 1 shl (12 - gridZoom)
        val x12Min  = x * scale
        val y12Min  = y * scale
        val addMode = !isTileSelected(x, y)
        for (dx in 0 until scale) {
            for (dy in 0 until scale) {
                val key = (x12Min + dx).toLong() * 4096L + (y12Min + dy)
                if (addMode) selectedTiles.add(key) else selectedTiles.remove(key)
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
