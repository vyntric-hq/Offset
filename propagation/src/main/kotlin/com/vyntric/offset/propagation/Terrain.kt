/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.ceil

/**
 * Ground elevation above sea level. Implementations hold their data in memory: lookups are
 * synchronous and called hundreds of thousands of times per coverage map, from several threads.
 */
fun interface Terrain {
    fun elevationM(latitude: Double, longitude: Double): Double

    companion object {
        /** No terrain data: a smooth earth at sea level. Coverage is then a smooth-earth estimate. */
        val FLAT = Terrain { _, _ -> 0.0 }
    }
}

/** Elevations sampled at equal steps along a path, first point at the transmitter. */
class TerrainProfile(val elevationsM: DoubleArray, val spacingM: Double) {
    val intervals: Int get() = elevationsM.size - 1
    val lengthM: Double get() = intervals * spacingM

    /** NTIA's profile format: interval count, spacing, then the elevations. */
    fun toItm(): DoubleArray = DoubleArray(elevationsM.size + 2).also {
        it[0] = intervals.toDouble()
        it[1] = spacingM
        elevationsM.copyInto(it, 2)
    }

    companion object {
        /** At least this many intervals, so ITM always has a profile to work with. */
        const val MIN_INTERVALS = 10

        /** Samples the great circle from [from] to [to] about every [stepM] metres. */
        fun sample(terrain: Terrain, from: GeoPoint, to: GeoPoint, stepM: Double, maxIntervals: Int = 1200): TerrainProfile {
            val d = Earth.distanceM(from, to)
            val bearing = Earth.bearingDeg(from, to)
            val n = ceil(d / stepM).toInt().coerceIn(MIN_INTERVALS, maxIntervals)
            val spacing = d / n
            val elevations = DoubleArray(n + 1) { i ->
                val p = if (i == 0) from else if (i == n) to else Earth.destination(from, bearing, i * spacing)
                terrain.elevationM(p.latitude, p.longitude)
            }
            return TerrainProfile(elevations, spacing)
        }
    }
}
