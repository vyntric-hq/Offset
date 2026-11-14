/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.Listing
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.Band
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * Listings from secondary directories (IRLP, OpenStreetMap) shipped in the APK as
 * `assets/repeaters/listings.json`, written by `scripts/update_listings_bundle.py` in one shared
 * schema. They are merged into the default provider's data with [com.vyntric.offset.domain.DirectoryMerge].
 */
internal object BundledListings {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val sources = mapOf("IRLP" to RepeaterSource.IRLP, "OSM" to RepeaterSource.OPENSTREETMAP)

    @Serializable
    internal data class Dto(
        val src: String,
        val id: String,
        val call: String,
        val out: Long,
        val inp: Long? = null,
        val up: String? = null,
        val down: String? = null,
        val city: String? = null,
        val state: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        /** Epoch milliseconds of the station's last own report, when the directory tracks it. */
        val seen: Long? = null,
        val url: String? = null,
    )

    /** Parses the file and keeps well formed records on bands a UV-5R can use. */
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(stream: InputStream): List<Repeater> {
        val all = try {
            json.decodeFromStream<List<Dto>>(stream)
        } catch (e: IllegalArgumentException) {
            throw RepeaterException.BadData("Bundled listings could not be read.", e)
        }
        return all.mapNotNull { it.toRepeater() }
    }

    private fun Dto.toRepeater(): Repeater? {
        val source = sources[src] ?: return null
        if (Band.of(out) == null && !Gmrs.isGmrs(out)) return null
        // The radio transmits on the input, so it must be on a band the radio covers too.
        if (inp != null && Band.of(inp) == null && !Gmrs.isGmrs(inp)) return null
        val callsign = call.trim().uppercase().ifEmpty { return null }
        val uplink = ToneText.parse(up)
        val link = url?.takeIf { it.startsWith("https://") }
        return Repeater(
            id = "${source.name.lowercase()}:$id",
            callsign = callsign,
            outputHz = out,
            inputHz = inp,
            uplinkTone = uplink,
            downlinkTone = ToneText.parse(down),
            city = city?.trim()?.ifEmpty { null },
            county = null,
            state = UsState.fromAbbr(state.orEmpty())?.abbr,
            latitude = lat?.takeIf { it in -90.0..90.0 && it != 0.0 },
            longitude = lon?.takeIf { it in -180.0..180.0 && it != 0.0 },
            source = source,
            detailUrl = link,
            listings = listOf(Listing(source, uplink, lastReportMillis = seen, detailUrl = link)),
        )
    }
}
