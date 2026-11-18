/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.sqrt

/** How much of the path's first Fresnel zone the terrain leaves open. */
enum class Clearance {
    /** At least 60% of the first Fresnel zone is clear everywhere: close to free space. */
    CLEAR,

    /** The line of sight is clear but the terrain intrudes into the Fresnel zone. */
    GRAZING,

    /** The terrain cuts the line of sight: the signal must bend over it. */
    BLOCKED,
}

/**
 * The geometry a terrain profile chart draws. Heights are metres above sea level, with the
 * terrain raised by the 4/3 earth bulge so a straight line of sight can be drawn over it.
 */
class PathGeometry(
    val profile: TerrainProfile,
    /** Terrain plus earth bulge at each sample. */
    val apparentTerrainM: DoubleArray,
    val startAntennaM: Double,
    val endAntennaM: Double,
    /** First Fresnel zone radius at each sample. */
    val fresnelM: DoubleArray,
    /** Sample where the terrain comes closest to (or furthest through) the line of sight. */
    val worstIndex: Int,
    /** Clearance at [worstIndex] as a fraction of the first Fresnel zone (negative: blocked). */
    val worstFresnelRatio: Double,
) {
    val clearance: Clearance
        get() = when {
            worstFresnelRatio >= 0.6 -> Clearance.CLEAR
            worstFresnelRatio >= 0.0 -> Clearance.GRAZING
            else -> Clearance.BLOCKED
        }

    fun lineOfSightM(i: Int): Double = startAntennaM + (endAntennaM - startAntennaM) * i / profile.intervals

    /** Distance from the start to the worst point, in metres. */
    val worstDistanceM: Double get() = worstIndex * profile.spacingM

    /** How far the terrain at the worst point rises above (positive) or sits below the line of sight. */
    val worstIntrusionM: Double get() = apparentTerrainM[worstIndex] - lineOfSightM(worstIndex)

    companion object {
        fun of(profile: TerrainProfile, startHeightM: Double, endHeightM: Double, frequencyMhz: Double): PathGeometry {
            val e = profile.elevationsM
            val n = profile.intervals
            // A zero-length path (a place on top of the station) still gets finite geometry.
            val d = profile.lengthM.coerceAtLeast(1.0)
            val lambda = 299.792458 / frequencyMhz
            val apparent = DoubleArray(n + 1) { i -> e[i] + Earth.bulgeM((i * profile.spacingM).coerceAtMost(d), d) }
            val fresnel = DoubleArray(n + 1) { i ->
                val x = (i * profile.spacingM).coerceAtMost(d)
                sqrt((lambda * x * (d - x) / d).coerceAtLeast(0.0))
            }
            val a = e[0] + startHeightM
            val b = e[n] + endHeightM
            var worst = -1
            var worstRatio = Double.POSITIVE_INFINITY
            for (i in 1 until n) {
                val los = a + (b - a) * i / n
                val ratio = (los - apparent[i]) / fresnel[i].coerceAtLeast(1e-6)
                if (ratio < worstRatio) { worstRatio = ratio; worst = i }
            }
            if (worst < 0) { worst = n / 2; worstRatio = Double.POSITIVE_INFINITY }
            return PathGeometry(profile, apparent, a, b, fresnel, worst, worstRatio)
        }
    }
}

/** A single link between two stations, worked out both ways. */
class PathCheck(
    val from: GeoPoint,
    val to: GeoPoint,
    val distanceM: Double,
    val bearingDeg: Double,
    val pathLoss: PathLoss,
    val talk: LinkBudget,
    val listen: LinkBudget,
    val geometry: PathGeometry,
) {
    companion object {
        /** [me] at [from], [them] at [to], on [frequencyMhz], terrain sampled about every [stepM] metres. */
        fun of(
            terrain: Terrain, model: PathLossModel,
            from: GeoPoint, me: RfStation, to: GeoPoint, them: RfStation,
            frequencyMhz: Double, stepM: Double,
        ): PathCheck {
            val profile = TerrainProfile.sample(terrain, from, to, stepM)
            val loss = model.loss(profile.toItm(), me.heightM, them.heightM, frequencyMhz)
            val bearing = Earth.bearingDeg(from, to)
            return PathCheck(
                from, to, Earth.distanceM(from, to), bearing, loss,
                talk = LinkBudget.of(Direction.TALK, me, them, bearing, loss.lossDb),
                listen = LinkBudget.of(Direction.LISTEN, me, them, bearing, loss.lossDb),
                geometry = PathGeometry.of(profile, me.heightM, them.heightM, frequencyMhz),
            )
        }
    }
}
