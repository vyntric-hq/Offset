/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain.reach

import com.vyntric.offset.domain.Maidenhead
import com.vyntric.offset.propagation.GeoPoint

/**
 * Reads places out of text people paste: one per line, as shared by a team in a group chat.
 * Understands decimal coordinates ("38.25, -85.76", "38.25N 85.76W"), grid squares ("EM78gh"),
 * geo: links and map links that carry coordinates (@lat,lon or q=lat,lon or ll=lat,lon). Text
 * before the location on a line becomes the name. Nothing is looked up online.
 */
object PlaceText {
    data class Parsed(val name: String?, val point: GeoPoint)

    private const val NUM = "[-+]?\\d{1,3}(?:\\.\\d+)?"
    private val linkPattern = Regex("(?:geo:|@|[?&](?:q|ll|query|center)=)($NUM)\\s*,\\s*($NUM)")
    private val hemispherePattern = Regex("(\\d{1,2}(?:\\.\\d+)?)\\s*°?\\s*([NS])[\\s,]+(\\d{1,3}(?:\\.\\d+)?)\\s*°?\\s*([EW])", RegexOption.IGNORE_CASE)
    private val decimalPattern = Regex("(?<![\\w.])($NUM)\\s*[,\\s]\\s*($NUM)(?![\\w.])")
    private val gridPattern = Regex("(?<![A-Za-z0-9])([A-Ra-r]{2}\\d{2}(?:[A-Xa-x]{2}(?:\\d{2})?)?)(?![A-Za-z0-9])")

    fun parse(text: String): List<Parsed> = text.lines().mapNotNull { parseLine(it) }

    fun parseLine(line: String): Parsed? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        linkPattern.find(trimmed)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return Parsed(nameBefore(trimmed, m.range.first), it) } }
        hemispherePattern.find(trimmed)?.let { m ->
            val lat = m.groupValues[1].toDouble() * if (m.groupValues[2].equals("S", true)) -1 else 1
            val lon = m.groupValues[3].toDouble() * if (m.groupValues[4].equals("W", true)) -1 else 1
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return Parsed(nameBefore(trimmed, m.range.first), GeoPoint(lat, lon))
        }
        decimalPattern.find(trimmed)?.let { m ->
            point(m.groupValues[1], m.groupValues[2])?.let { return Parsed(nameBefore(trimmed, m.range.first), it) }
        }
        gridPattern.findAll(trimmed).forEach { m ->
            Maidenhead.toLatLon(m.groupValues[1])?.takeIf { m.groupValues[1].length >= 4 }?.let { (lat, lon) ->
                return Parsed(nameBefore(trimmed, m.range.first), GeoPoint(lat, lon))
            }
        }
        return null
    }

    private fun point(lat: String, lon: String): GeoPoint? {
        val a = lat.toDoubleOrNull() ?: return null
        val b = lon.toDoubleOrNull() ?: return null
        // Plain integers like "10 20" are too likely to be something else.
        if (!lat.contains('.') && !lon.contains('.')) return null
        return if (a in -90.0..90.0 && b in -180.0..180.0) GeoPoint(a, b) else null
    }

    private fun nameBefore(line: String, end: Int): String? =
        line.substring(0, end).replace(Regex("https?://\\S*$"), "").trim().trimEnd(':', '-', ',', '=', '(', '@').trim().ifEmpty { null }
}
