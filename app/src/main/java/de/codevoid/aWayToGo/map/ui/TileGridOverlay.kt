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
 * Transparent overlay that draws blue selection fills for chosen z8 tiles.
 *
 * Grid lines are rendered by a MapLibre LineLayer (smooth panning). Only the
 * interactive selection highlights live here — Canvas drawRect calls are
 * effectively instant (one frame) vs MapLibre's GeoJSON pipeline which has
 * hundreds of milliseconds of fixed latency regardless of data size.
 *
 * Call [invalidate] after any selection change or camera move to redraw.
 */
class TileGridOverlay(context: Context) : View(context) {

    var map: MapLibreMap? = null

    /** z8 selected tiles. Key = x * 256 + y. */
    val selectedTiles: MutableSet<Int> = mutableSetOf()

    /** Tile zoom level used for both grid rendering and selection. */
    val gridZoom = 8

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(51, 33, 150, 243)   // blue 20 %
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val proj = map?.projection ?: return
        for (key in selectedTiles) {
            val x  = key / 256
            val y  = key % 256
            val nw = proj.toScreenLocation(LatLng(tileToLat(y,     gridZoom), tileToLon(x,     gridZoom)))
            val se = proj.toScreenLocation(LatLng(tileToLat(y + 1, gridZoom), tileToLon(x + 1, gridZoom)))
            canvas.drawRect(nw.x, nw.y, se.x, se.y, fillPaint)
        }
    }

    fun isTileSelected(x: Int, y: Int): Boolean = selectedTiles.contains(x * 256 + y)

    /** Toggle selection of the z8 tile at (x, y). */
    fun toggleTile(x: Int, y: Int) {
        val key = x * 256 + y
        if (!selectedTiles.remove(key)) selectedTiles.add(key)
    }

    /**
     * Total tiles that would be downloaded for the current selection (z8–z14).
     *
     * Each z8 tile expands to exactly 1+4+16+64+256+1024+4096 = 5461 tiles across
     * z8–z14, with zero overlap between different z8 tiles' descendant ranges.
     * No set deduplication needed — just multiply.
     */
    fun countDownloadTiles(): Int = selectedTiles.size * 5461

    // ── Tile math ──────────────────────────────────────────────────────────────

    fun tileToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    fun tileToLon(x: Int, z: Int): Double =
        x.toDouble() / (1 shl z) * 360.0 - 180.0

    fun lonToTile(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    fun latToTile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.051129, 85.051129))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom))
            .toInt().coerceIn(0, (1 shl zoom) - 1)
    }
}
