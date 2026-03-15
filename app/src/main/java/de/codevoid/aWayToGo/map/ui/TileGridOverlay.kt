package de.codevoid.aWayToGo.map.ui

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Holds tile-selection state for offline-download mode and provides tile math
 * helpers used by MapActivity to build the MapLibre GeoJSON grid layers.
 *
 * Rendering is handled entirely by MapLibre (a GeoJSON source + FillLayer +
 * LineLayer), so this class carries no View or Canvas dependencies.
 *
 * Selections are stored at the z12 canonical zoom:
 * each key = `x12 * 4096L + y12`.  z8 tiles map to their z12 descendants when
 * downloading (1 z8 tile → 16 z12 tiles).
 */
class TileGridOverlay {

    /** z12-canonical selected tiles. Key = x12 * 4096L + y12. */
    val selectedTiles: MutableSet<Long> = mutableSetOf()

    /** Tile zoom level used for both grid rendering and selection. */
    val gridZoom = 8

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
            packed.add(packTile(12, x12, y12))
            for (dz in 1..2) {
                val s = 1 shl dz
                for (dx in 0 until s) for (dy in 0 until s)
                    packed.add(packTile(12 + dz, x12 * s + dx, y12 * s + dy))
            }
            for (dz in 1..4) packed.add(packTile(12 - dz, x12 shr dz, y12 shr dz))
        }
        return packed.size
    }

    // ── Tile math (used by MapActivity for grid GeoJSON + URL building) ────────

    private fun packTile(z: Int, x: Int, y: Int): Long =
        z.toLong() * 100_000_000L + x.toLong() * 16_384L + y

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
