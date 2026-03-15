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
 * Selections are stored at z8 directly (key = x * 256 + y) for O(1) lookup.
 * The z12 expansion (1 z8 tile → 256 z12 tiles) only happens at download time.
 */
class TileGridOverlay {

    /** z8 selected tiles. Key = x8 * 256 + y8. */
    val selectedTiles: MutableSet<Int> = mutableSetOf()

    /** Tile zoom level used for both grid rendering and selection. */
    val gridZoom = 8

    fun isTileSelected(x: Int, y: Int): Boolean = selectedTiles.contains(x * 256 + y)

    /** Toggle selection of the z8 tile at (x, y). */
    fun toggleTile(x: Int, y: Int) {
        val key = x * 256 + y
        if (!selectedTiles.remove(key)) selectedTiles.add(key)
    }

    /**
     * Total tiles that would be downloaded for the current selection (z8–z14),
     * with ancestor tiles deduplicated.
     */
    fun countDownloadTiles(): Int {
        if (selectedTiles.isEmpty()) return 0
        val packed = mutableSetOf<Long>()
        for (key in selectedTiles) {
            val x8 = key / 256
            val y8 = key % 256
            // z8 itself
            packed.add(packTile(8, x8, y8))
            // z9–z14 descendants
            for (dz in 1..6) {
                val s = 1 shl dz
                for (dx in 0 until s) for (dy in 0 until s)
                    packed.add(packTile(8 + dz, x8 * s + dx, y8 * s + dy))
            }
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
