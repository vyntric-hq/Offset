/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Squelch

/**
 * How well a repeater's listing is backed up: by how many independent directories, whether they
 * agree on the access tone, and when the station itself last reported in (IRLP nodes do).
 */
data class Verification(
    /** Distinct directories that list the repeater, in listing order. */
    val sources: List<RepeaterSource>,
    /** Distinct original sources behind those listings; a copy of another directory's entry adds none. */
    val independentSources: Int,
    val lastReportMillis: Long?,
    /** Two directories give different access tones; check on the air before relying on either. */
    val tonesDisagree: Boolean,
) {
    val corroborated: Boolean get() = independentSources >= 2

    companion object {
        fun of(listings: List<Listing>) = Verification(
            sources = listings.map { it.source }.distinct(),
            independentSources = listings.map { it.origin }.distinct().size,
            lastReportMillis = listings.mapNotNull { it.lastReportMillis }.maxOrNull(),
            tonesDisagree = listings.map { it.uplinkTone }.filter { it != Squelch.None }.distinct().size > 1,
        )
    }
}

/**
 * Folds listings from secondary directories into a primary list. A secondary record that
 * describes a repeater already in the list adds its [Listing] to it (and its coordinates, when the
 * primary has none); one that matches nothing is added as a repeater of its own, unless its input
 * frequency is unknown: such a record cannot be programmed, so it only corroborates.
 *
 * Two records are the same repeater when their output frequencies agree within
 * [FREQUENCY_TOLERANCE_HZ], their input frequencies do too (when both are known), and either the callsigns match (within [SAME_CALL_KM] when both are
 * placed, or with no coordinates to compare) or both are placed within [SAME_SITE_KM]. Frequency
 * coordination keeps two machines on one output far apart, so a close match on frequency and
 * place is the same station even when a directory lists the club call instead of the trustee's.
 */
object DirectoryMerge {
    const val FREQUENCY_TOLERANCE_HZ = 2_500L
    const val SAME_SITE_KM = 25.0
    const val SAME_CALL_KM = 100.0
    private const val BUCKET_HZ = 5_000L

    fun merge(primary: List<Repeater>, secondary: List<Repeater>): List<Repeater> {
        if (secondary.isEmpty()) return primary
        val out = primary.toMutableList()
        val buckets = HashMap<Long, MutableList<Int>>()
        fun index(i: Int) = buckets.getOrPut(out[i].outputHz / BUCKET_HZ) { mutableListOf() }.add(i)
        out.indices.forEach(::index)
        for (other in secondary) {
            val bucket = other.outputHz / BUCKET_HZ
            val match = (bucket - 1..bucket + 1).asSequence()
                .flatMap { buckets[it].orEmpty().asSequence() }
                .filter { sameRepeater(out[it], other) }
                // Nearest located match first; one without coordinates only when nothing is placed.
                .minWithOrNull(compareBy({ distanceKm(out[it], other) == null }, { distanceKm(out[it], other) }))
            if (match == null) {
                if (other.inputHz == null) continue
                out += other
                index(out.lastIndex)
            } else {
                out[match] = absorb(out[match], other)
            }
        }
        return out
    }

    internal fun sameRepeater(a: Repeater, b: Repeater): Boolean {
        if (kotlin.math.abs(a.outputHz - b.outputHz) > FREQUENCY_TOLERANCE_HZ) return false
        val (ia, ib) = a.inputHz to b.inputHz
        if (ia != null && ib != null && kotlin.math.abs(ia - ib) > FREQUENCY_TOLERANCE_HZ) return false
        val km = distanceKm(a, b)
        val sameCall = a.callsign.equals(b.callsign, ignoreCase = true)
        return when {
            km == null -> sameCall
            sameCall -> km <= SAME_CALL_KM
            else -> km <= SAME_SITE_KM
        }
    }

    private fun absorb(into: Repeater, other: Repeater): Repeater {
        val place = if (into.latitude == null || into.longitude == null) other else into
        return into.copy(
            latitude = place.latitude,
            longitude = place.longitude,
            listings = into.listings + other.listings,
        )
    }

    private fun distanceKm(a: Repeater, b: Repeater): Double? {
        val (la, lo) = (a.latitude ?: return null) to (a.longitude ?: return null)
        val (lb, ob) = (b.latitude ?: return null) to (b.longitude ?: return null)
        return Geo.distanceKm(la, lo, lb, ob)
    }
}
