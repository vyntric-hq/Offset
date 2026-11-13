/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great circle maths, computed on device so no position ever leaves the phone. */
object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088
    const val KM_PER_MILE = 1.609344

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Sixteen point compass label for a bearing, for example "NNE". */
    fun compass(bearing: Double): String {
        val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return points[(((bearing % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }
}

/** Shared filtering for every provider: service, band, mode, area, text, distance and order. */
object RepeaterFilter {
    fun apply(all: List<Repeater>, query: RepeaterQuery): List<Repeater> {
        val accepted = all.filter { query.filters.accepts(it) }
        val origin = query.origin
        fun located(list: List<Repeater>) =
            if (origin == null) list else list.map { r -> withGeometry(r, origin.first, origin.second) ?: r }
        return when (query) {
            is RepeaterQuery.Near -> {
                val placed = accepted.mapNotNull { r -> withGeometry(r, query.latitude, query.longitude) }.sortedWith(order)
                val inRadius = query.radiusKm?.let { km -> placed.filter { it.distanceKm!! <= km } } ?: placed
                query.limit?.let { inRadius.take(it) } ?: inRadius
            }
            is RepeaterQuery.ByState -> located(
                accepted.filter { it.state.equals(query.state.abbr, true) }
                    .filter { r -> query.county.isNullOrBlank() || r.county.equals(query.county, true) }
            ).sortedWith(order)
            is RepeaterQuery.Text -> located(accepted.filter { matches(it, query.text) }).sortedWith(order).take(TEXT_LIMIT)
            is RepeaterQuery.All -> located(accepted)
        }
    }

    /** Text search returns at most this many rows; refine the query for more. */
    const val TEXT_LIMIT = 500

    /** Every word must match the callsign, place, state or frequency. */
    fun matches(r: Repeater, text: String): Boolean {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val mhz = String.format(java.util.Locale.US, "%.4f", r.outputHz / 1_000_000.0)
        val stateName = r.state?.let { UsState.fromAbbr(it)?.displayName }
        val hay = listOfNotNull(r.callsign, r.city, r.county, r.state, stateName).joinToString(" ").lowercase()
        return words.all { w ->
            hay.contains(w) || (w.first().isDigit() && (mhz.startsWith(w) || mhz.replace(".", "").startsWith(w.replace(".", ""))))
        }
    }

    private val order = compareBy<Repeater>({ it.distanceKm ?: Double.MAX_VALUE }, { it.callsign }, { it.outputHz })

    private fun withGeometry(r: Repeater, lat: Double, lon: Double): Repeater? {
        val rl = r.latitude ?: return null
        val rn = r.longitude ?: return null
        return r.copy(distanceKm = Geo.distanceKm(lat, lon, rl, rn), bearingDeg = Geo.bearingDeg(lat, lon, rl, rn))
    }
}
