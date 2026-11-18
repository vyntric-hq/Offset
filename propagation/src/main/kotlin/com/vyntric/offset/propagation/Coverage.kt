/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * What to sweep: path loss from a station at [origin] with its antenna [myHeightM] up, to far
 * stations [farHeightM] up, on [frequencyMhz]. Power, gains and direction are not part of it:
 * they only turn loss into margin, so changing them redraws without recomputing.
 */
data class CoverageRequest(
    val origin: GeoPoint,
    val myHeightM: Double,
    val farHeightM: Double,
    val frequencyMhz: Double,
    val radiusM: Double,
    val stepM: Double,
    val radials: Int = 360,
) {
    init {
        require(radials >= 8 && radiusM > stepM && stepM > 0) { "Bad coverage request" }
    }

    val samples: Int get() = ceil(radiusM / stepM).toInt()

    companion object {
        /**
         * How far to look: well past the combined radio horizon, where diffraction can still
         * carry a strong signal, and never more than 150 km (VHF/UHF rarely goes further).
         */
        fun radiusFor(myHeightM: Double, farHeightM: Double): Double =
            ((Earth.horizonM(myHeightM) + Earth.horizonM(farHeightM)) * 1.8 + 15_000.0).coerceIn(30_000.0, 150_000.0)

        /**
         * Sample spacing: terrain detail without more than about 320 samples on a radial, which
         * keeps a full sweep to a few seconds on a phone (ITM is run once per sample).
         */
        fun stepFor(radiusM: Double, terrainResolutionM: Double): Double = maxOf(terrainResolutionM, radiusM / 320.0)
    }
}

/** Turns a path loss toward [bearingDeg] into a margin in dB; see [LinkBudget]. */
fun interface MarginFunction {
    fun marginDb(bearingDeg: Double, lossDb: Double): Double

    companion object {
        fun of(direction: Direction, me: RfStation, far: RfStation) =
            MarginFunction { bearing, loss -> LinkBudget.of(direction, me, far, bearing, loss).marginDb }
    }
}

/**
 * Path losses on a polar grid around the origin: [radials] directions (radial 0 due north,
 * clockwise) by [samples] distances ([stepM] apart, the first one step out). NaN marks samples
 * not computed yet. Written by several threads (one radial each) while being read for drawing.
 */
class CoverageGrid(
    val origin: GeoPoint,
    val radials: Int,
    val samples: Int,
    val stepM: Double,
    val lossesDb: FloatArray = FloatArray(radials * samples) { Float.NaN },
) {
    val radiusM: Double get() = samples * stepM

    fun loss(radial: Int, sample: Int): Float = lossesDb[radial * samples + sample]

    /**
     * Margin at a point [eastM] and [northM] from the origin, from the loss interpolated between
     * the four nearest samples. Null outside the grid or where nothing is computed yet. Uses a
     * local flat-earth approximation, under half a percent off at 150 km.
     */
    fun marginAt(eastM: Double, northM: Double, margin: MarginFunction): Double? {
        val r = hypot(eastM, northM) / stepM - 1.0
        if (r > samples - 1) return null
        val bearing = (Math.toDegrees(atan2(eastM, northM)) + 360.0) % 360.0
        val a = bearing / 360.0 * radials
        val a0 = floor(a).toInt() % radials
        val a1 = (a0 + 1) % radials
        val ta = a - floor(a)
        val s = r.coerceAtLeast(0.0)
        val s0 = floor(s).toInt().coerceAtMost(samples - 1)
        val s1 = (s0 + 1).coerceAtMost(samples - 1)
        val ts = s - s0
        val l00 = loss(a0, s0); val l01 = loss(a0, s1); val l10 = loss(a1, s0); val l11 = loss(a1, s1)
        if (l00.isNaN() || l01.isNaN() || l10.isNaN() || l11.isNaN()) return null
        val near = l00 + (l10 - l00) * ta
        val far = l01 + (l11 - l01) * ta
        return margin.marginDb(bearing, near + (far - near) * ts)
    }

    /** Fraction of the samples that are computed. */
    fun completeness(): Float = lossesDb.count { !it.isNaN() }.toFloat() / lossesDb.size

    /**
     * Margins sampled on a [width] x [height] grid covering the square of side 2 x radius centred
     * on the origin, north up, row by row. NaN outside the coverage circle or where nothing is
     * computed yet. Drawing code colours these and traces contours between them.
     */
    fun sample(width: Int, height: Int, margin: MarginFunction): FloatArray {
        val out = FloatArray(width * height)
        val side = 2 * radiusM
        for (y in 0 until height) {
            val north = radiusM - (y + 0.5) * side / height
            for (x in 0 until width) {
                val east = -radiusM + (x + 0.5) * side / width
                out[y * width + x] = marginAt(east, north, margin)?.toFloat() ?: Float.NaN
            }
        }
        return out
    }

    /** [sample] mapped through [color]; transparent where there is no margin. */
    fun rasterize(width: Int, height: Int, margin: MarginFunction, color: (Double) -> Int): IntArray =
        sample(width, height, margin).let { m -> IntArray(m.size) { i -> if (m[i].isNaN()) 0 else color(m[i].toDouble()) } }

    /**
     * How far out the margin still reaches [thresholdDb] in the best direction: the furthest
     * computed sample at or above it. Used to frame the map on the coverage, not the whole grid.
     */
    fun reachM(margin: MarginFunction, thresholdDb: Double = 0.0): Double {
        var best = 0.0
        for (r in 0 until radials) {
            val bearing = r * 360.0 / radials
            for (i in samples - 1 downTo 0) {
                val l = loss(r, i)
                if (!l.isNaN() && margin.marginDb(bearing, l.toDouble()) >= thresholdDb) {
                    best = maxOf(best, (i + 1) * stepM)
                    break
                }
            }
        }
        return best
    }

    /**
     * Where the [rasterize] square lies on the map: its north west and south east corners, each
     * edge [radiusM] from the origin.
     */
    fun bounds(): Pair<GeoPoint, GeoPoint> {
        val dLat = Math.toDegrees(radiusM / Earth.RADIUS_M)
        val dLon = Math.toDegrees(radiusM / (Earth.RADIUS_M * cos(Math.toRadians(origin.latitude))))
        return GeoPoint((origin.latitude + dLat).coerceAtMost(90.0), (origin.longitude - dLon).coerceAtLeast(-180.0)) to
            GeoPoint((origin.latitude - dLat).coerceAtLeast(-90.0), (origin.longitude + dLon).coerceAtMost(180.0))
    }
}

/**
 * Sweeps radials out from the station. On each, the terrain is sampled once and the path loss
 * is worked out at every sample from the profile up to it.
 */
class CoverageEngine(private val terrain: Terrain, private val model: PathLossModel) {

    fun newGrid(request: CoverageRequest) = CoverageGrid(request.origin, request.radials, request.samples, request.stepM)

    /**
     * Fills radial [radial] of [grid]. Radials are independent: callers run them on several
     * threads and redraw as they land, so coverage sweeps in like a radar.
     */
    fun computeRadial(request: CoverageRequest, grid: CoverageGrid, radial: Int) {
        val n = grid.samples
        val bearing = radial * 360.0 / grid.radials
        val profile = DoubleArray(n + 3)
        profile[1] = grid.stepM
        profile[2] = terrain.elevationM(request.origin.latitude, request.origin.longitude)
        for (i in 1..n) {
            val p = Earth.destination(request.origin, bearing, i * grid.stepM)
            profile[i + 2] = terrain.elevationM(p.latitude, p.longitude)
        }
        for (i in 1..n) {
            profile[0] = i.toDouble()
            grid.lossesDb[radial * n + (i - 1)] = model.loss(profile, request.myHeightM, request.farHeightM, request.frequencyMhz).lossDb.toFloat()
        }
    }

    /** Computes every radial on the calling thread. Tests and small maps use this. */
    fun compute(request: CoverageRequest): CoverageGrid = newGrid(request).also { g ->
        for (r in 0 until g.radials) computeRadial(request, g, r)
    }
}
