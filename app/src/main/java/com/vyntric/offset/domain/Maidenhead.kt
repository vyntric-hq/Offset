/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import java.util.Locale

/** Maidenhead grid locators ("EM78", "EM78gh", "EM78gh45") and decimal coordinates. */
object Maidenhead {
    /** Locator for a position: field, square and subsquare (6 characters) by default. */
    fun fromLatLon(lat: Double, lon: Double, length: Int = 6): String {
        require(length in setOf(2, 4, 6, 8)) { "Locator length must be 2, 4, 6 or 8" }
        var x = (lon + 180.0).coerceIn(0.0, 359.999999)
        var y = (lat + 90.0).coerceIn(0.0, 179.999999)
        val sb = StringBuilder()
        sb.append('A' + (x / 20).toInt()); sb.append('A' + (y / 10).toInt())
        x %= 20; y %= 10
        if (length >= 4) { sb.append('0' + (x / 2).toInt()); sb.append('0' + y.toInt()); x %= 2; y %= 1 }
        if (length >= 6) { sb.append('a' + (x * 12).toInt()); sb.append('a' + (y * 24).toInt()); x %= 1.0 / 12; y %= 1.0 / 24 }
        if (length >= 8) { sb.append('0' + (x * 120).toInt()); sb.append('0' + (y * 240).toInt()) }
        return sb.toString()
    }

    /** Centre of the locator's square, or null if it is not a valid 2, 4, 6 or 8 character locator. */
    fun toLatLon(locator: String): Pair<Double, Double>? {
        val g = locator.trim()
        if (g.length !in setOf(2, 4, 6, 8)) return null
        val u = g.uppercase(Locale.US)
        if (u[0] !in 'A'..'R' || u[1] !in 'A'..'R') return null
        var lon = (u[0] - 'A') * 20.0 - 180.0
        var lat = (u[1] - 'A') * 10.0 - 90.0
        var w = 20.0; var h = 10.0
        if (u.length >= 4) {
            if (!u[2].isDigit() || !u[3].isDigit()) return null
            lon += (u[2] - '0') * 2.0; lat += (u[3] - '0') * 1.0; w = 2.0; h = 1.0
        }
        if (u.length >= 6) {
            if (u[4] !in 'A'..'X' || u[5] !in 'A'..'X') return null
            lon += (u[4] - 'A') * (2.0 / 24); lat += (u[5] - 'A') * (1.0 / 24); w = 2.0 / 24; h = 1.0 / 24
        }
        if (u.length == 8) {
            if (!u[6].isDigit() || !u[7].isDigit()) return null
            lon += (u[6] - '0') * (2.0 / 240); lat += (u[7] - '0') * (1.0 / 240); w = 2.0 / 240; h = 1.0 / 240
        }
        return (lat + h / 2) to (lon + w / 2)
    }

    /**
     * Parses typed coordinates: "38.25, -85.76", "38.25 -85.76" or "38.25N 85.76W".
     * Returns null when either value is missing or out of range.
     */
    fun parseLatLon(text: String): Pair<Double, Double>? {
        val parts = text.trim().uppercase(Locale.US).split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        fun value(s: String, pos: Char, neg: Char): Double? {
            val sign = when (s.last()) { neg -> -1.0; pos -> 1.0; else -> null }
            val num = (if (sign != null) s.dropLast(1) else s).toDoubleOrNull() ?: return null
            return if (sign != null) kotlin.math.abs(num) * sign else num
        }
        val lat = value(parts[0], 'N', 'S') ?: return null
        val lon = value(parts[1], 'E', 'W') ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }
}

/** A US county (or equivalent) and its Census internal point. */
data class County(val state: String, val name: String, val latitude: Double, val longitude: Double)
