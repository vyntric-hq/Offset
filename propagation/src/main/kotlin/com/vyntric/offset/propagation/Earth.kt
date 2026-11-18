/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth in decimal degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Not a coordinate: $latitude, $longitude" }
    }
}

/** Great circle maths on a spherical earth: ample for paths of a few hundred kilometres. */
object Earth {
    const val RADIUS_M = 6_371_008.8

    /** Effective earth radius factor for VHF/UHF refraction in a standard atmosphere. */
    const val K_FACTOR = 4.0 / 3.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from true north, in 0 until 360. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dl = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [distanceM] from [from] along [bearingDeg]. */
    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / RADIUS_M
        val t = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.latitude)
        val l1 = Math.toRadians(from.longitude)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        val lon = (Math.toDegrees(l2) + 540.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(p2), lon)
    }

    /** Radio horizon in metres for an antenna [heightM] above ground, with the 4/3 earth. */
    fun horizonM(heightM: Double): Double = sqrt(2 * K_FACTOR * RADIUS_M * heightM.coerceAtLeast(0.0))

    /** How far the 4/3 earth bulges above the chord at [x] metres along a path of [d] metres. */
    fun bulgeM(x: Double, d: Double): Double = x * (d - x) / (2 * K_FACTOR * RADIUS_M)
}
