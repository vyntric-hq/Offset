/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Band
import com.vyntric.offset.protocol.Squelch
import com.vyntric.offset.protocol.ToneMode

/** Where repeater data came from, with the attribution each source requires. */
enum class RepeaterSource(val displayName: String, val attribution: String, val url: String) {
    HEARHAM("Hearham", "Repeater data from Hearham.com", "https://hearham.com"),
    REPEATERBOOK("RepeaterBook", "Data courtesy of RepeaterBook.com", "https://www.repeaterbook.com"),
    CSV("Imported CSV", "Imported from a CHIRP CSV file", ""),
}

/** One repeater, in the same shape whichever provider supplied it. */
data class Repeater(
    val id: String,
    val callsign: String,
    val outputHz: Long,
    /** Repeater input (what the radio transmits on). Null for simplex entries. */
    val inputHz: Long?,
    /** Tone the radio must transmit to open the repeater. */
    val uplinkTone: Squelch,
    /** Tone the repeater transmits, usable for tone squelch. */
    val downlinkTone: Squelch,
    val city: String?,
    val county: String?,
    val state: String?,
    val latitude: Double?,
    val longitude: Double?,
    val source: RepeaterSource,
    val lastUpdated: String? = null,
    val detailUrl: String? = null,
    val notes: String? = null,
    val distanceKm: Double? = null,
    val bearingDeg: Double? = null,
    val service: Service = Gmrs.serviceOf(outputHz),
    /** Also carries a digital mode (DMR, Fusion, D-STAR, P25, NXDN) besides analog FM. */
    val mixedMode: Boolean = false,
) {
    val offsetHz: Long? get() = inputHz?.let { it - outputHz }
    val band: Band? get() = Band.of(outputHz)

    /** Map and legend category: "2m", "70cm" or "GMRS". */
    val kind: String get() = if (service == Service.GMRS) "GMRS" else band?.label ?: "other"
    val toneMode: ToneMode get() = ToneMode.of(uplinkTone, downlinkTone)
}

/** Filters shared by every query. Bands apply to amateur repeaters only. */
data class RepeaterFilters(
    val services: Set<Service> = setOf(Service.AMATEUR),
    val bands: Set<Band> = setOf(Band.TWO_METER, Band.SEVENTY_CM),
    val hideMixedMode: Boolean = false,
) {
    fun accepts(r: Repeater): Boolean = when (r.service) {
        Service.GMRS -> Service.GMRS in services
        Service.AMATEUR -> Service.AMATEUR in services && r.band != null && r.band in bands
    } && !(hideMixedMode && r.mixedMode)
}

/** What the user asked for. Distances are always kilometres internally. */
sealed interface RepeaterQuery {
    val filters: RepeaterFilters

    /** Phone position, when known, so results show distance and bearing. */
    val origin: Pair<Double, Double>?

    /**
     * Repeaters around a point: within [radiusKm], or when that is null the [limit] closest.
     */
    data class Near(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double?,
        override val filters: RepeaterFilters,
        val limit: Int? = null,
    ) : RepeaterQuery {
        override val origin get() = latitude to longitude
    }

    data class ByState(
        val state: UsState,
        val county: String?,
        override val filters: RepeaterFilters,
        override val origin: Pair<Double, Double>?,
    ) : RepeaterQuery

    /** Free text: callsign, city, county, state or frequency. Searches data already on the phone. */
    data class Text(val text: String, override val filters: RepeaterFilters, override val origin: Pair<Double, Double>?) : RepeaterQuery

    /** Everything the provider has on the phone, for the all-USA map. */
    data class All(override val filters: RepeaterFilters, override val origin: Pair<Double, Double>?) : RepeaterQuery
}

/** Counts for the "N amateur + M GMRS, updated <date>" line. */
data class DataSummary(val amateur: Int, val gmrs: Int, val fetchedAtMillis: Long?, val bundled: Boolean = false)

data class RepeaterResult(
    val repeaters: List<Repeater>,
    val source: RepeaterSource,
    /** When the underlying data was fetched from the network; null for imported files. */
    val fetchedAtMillis: Long?,
    val fromCache: Boolean,
    /** Older than the provider's refresh interval; the screen may refresh quietly. */
    val stale: Boolean = false,
)

/** Provider failures with messages written for the user. */
sealed class RepeaterException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : RepeaterException(message, cause)
    class NotConfigured(message: String) : RepeaterException(message)
    class Auth(message: String) : RepeaterException(message)
    class RateLimited(val retryAfterMillis: Long) : RepeaterException(
        "RepeaterBook asked us to slow down. Try again in ${(retryAfterMillis / 1000).coerceAtLeast(1)} seconds."
    )
    class Redirected(location: String?) : RepeaterException(
        "RepeaterBook answered with a redirect${location?.let { " to $it" } ?: ""}. " +
            "Offset never forwards your token, so the request was stopped."
    )
    class BadData(message: String, cause: Throwable? = null) : RepeaterException(message, cause)
}

/** A source of repeaters. Three implementations: Hearham, RepeaterBook and imported CSV. */
interface RepeaterProvider {
    val source: RepeaterSource

    /** Counties can only be offered when the source records them. */
    val supportsCounty: Boolean

    suspend fun search(query: RepeaterQuery, forceRefresh: Boolean = false): RepeaterResult

    /** When cached data was fetched, or null if nothing is cached. */
    suspend fun cachedAtMillis(): Long?

    /** What is on the phone right now, without any network request. Null when nothing is. */
    suspend fun summary(): DataSummary?

    suspend fun clearCache()
}
