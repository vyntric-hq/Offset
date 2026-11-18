/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A slippy map tile: zoom, column and row in the Web Mercator grid. */
data class TileId(val z: Int, val x: Int, val y: Int) {
    /** Unique within one zoom, for map keys. */
    val key: Long get() = (x.toLong() shl 32) or y.toLong()
}

/** Web Mercator tile arithmetic (the scheme every slippy map uses). */
object Tiles {
    const val SIZE = 256

    /** Global pixel coordinates of a point at [z]: x east from 180 W, y south from 85 N. */
    fun pixel(latitude: Double, longitude: Double, z: Int): Pair<Double, Double> {
        val n = SIZE.toDouble() * (1 shl z)
        val lat = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
        val x = (longitude + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * n
        return x to y
    }

    fun latitudeOfPixelY(y: Double, z: Int): Double {
        val n = SIZE.toDouble() * (1 shl z)
        return Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
    }

    /** Ground metres per pixel at [latitude] and zoom [z]. */
    fun metresPerPixel(latitude: Double, z: Int): Double =
        2 * PI * Earth.RADIUS_M * cos(Math.toRadians(latitude)) / (SIZE.toDouble() * (1 shl z))

    /** Every tile at [z] touching the square of half side [radiusM] around [center]. */
    fun covering(center: GeoPoint, radiusM: Double, z: Int): List<TileId> {
        val nw = Earth.destination(Earth.destination(center, 0.0, radiusM), 270.0, radiusM)
        val se = Earth.destination(Earth.destination(center, 180.0, radiusM), 90.0, radiusM)
        val (x0, y0) = pixel(nw.latitude, nw.longitude, z)
        val (x1, y1) = pixel(se.latitude, se.longitude, z)
        val max = (1 shl z) - 1
        val left = floor(x0 / SIZE).toInt().coerceIn(0, max)
        val right = floor(x1 / SIZE).toInt().coerceIn(0, max)
        // An area across the antimeridian (the western Aleutians) wraps from the last column to the first.
        val xs = if (left <= right) (left..right).toList() else (left..max) + (0..right)
        val ys = (floor(y0 / SIZE).toInt().coerceIn(0, max))..(floor(y1 / SIZE).toInt().coerceIn(0, max))
        return xs.flatMap { x -> ys.map { y -> TileId(z, x, y) } }
    }

    /**
     * The most detailed zoom (up to [maxZoom]) at which the area around [center] needs at most
     * [maxTilesAcross] tiles across, so a coverage map downloads a few MB, not hundreds.
     */
    fun zoomFor(center: GeoPoint, radiusM: Double, maxTilesAcross: Int = 10, maxZoom: Int = 12, minZoom: Int = 8): Int {
        for (z in maxZoom downTo minZoom) {
            val across = 2 * radiusM / (metresPerPixel(center.latitude, z) * SIZE)
            if (across <= maxTilesAcross - 1) return z
        }
        return minZoom
    }
}

/** Mapzen's Terrarium encoding: elevation = R x 256 + G + B / 256 - 32768 metres. */
object Terrarium {
    /** Decodes ARGB pixels into whole metres. */
    fun decode(argb: IntArray): ShortArray = ShortArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (r * 256 + g + b / 256.0 - 32768.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/**
 * Terrain from decoded tiles at one zoom, bilinearly interpolated. Points on tiles that are
 * missing read as sea level; [missing] says whether any were. Read only after construction, so
 * safe to share between threads.
 */
class TiledTerrain(val zoom: Int, tiles: Map<TileId, ShortArray>, val missing: Int = 0) : Terrain {
    private val grid: Map<Long, ShortArray> = tiles.filterKeys { it.z == zoom }.mapKeys { it.key.key }

    val tileCount: Int get() = grid.size

    /** Ground metres per pixel near [latitude]: the finest detail this terrain holds. */
    fun resolutionM(latitude: Double): Double = Tiles.metresPerPixel(latitude, zoom)

    override fun elevationM(latitude: Double, longitude: Double): Double {
        val (px, py) = Tiles.pixel(latitude, longitude, zoom)
        val x0 = floor(px - 0.5).toLong()
        val y0 = floor(py - 0.5).toLong()
        val tx = px - 0.5 - x0
        val ty = py - 0.5 - y0
        val e00 = at(x0, y0); val e10 = at(x0 + 1, y0); val e01 = at(x0, y0 + 1); val e11 = at(x0 + 1, y0 + 1)
        val top = e00 + (e10 - e00) * tx
        val bottom = e01 + (e11 - e01) * tx
        return top + (bottom - top) * ty
    }

    private fun at(gx: Long, gy: Long): Double {
        val world = Tiles.SIZE.toLong() shl zoom
        val x = Math.floorMod(gx, world) // east and west wrap round at the antimeridian
        val y = gy.coerceIn(0, world - 1)
        val tile = grid[((x / Tiles.SIZE) shl 32) or (y / Tiles.SIZE)] ?: return 0.0
        return tile[((y % Tiles.SIZE) * Tiles.SIZE + (x % Tiles.SIZE)).toInt()].toDouble()
    }
}
