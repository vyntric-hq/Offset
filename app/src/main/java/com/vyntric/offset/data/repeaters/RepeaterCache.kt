/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.data.SquelchText
import com.vyntric.offset.domain.DataSummary
import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.Service
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A provider's cached result set on disk, in app private storage that is excluded from backup.
 * Writes go to a temp file and are renamed so a crash never leaves a torn cache.
 */
internal class RepeaterCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [bundled] marks the snapshot shipped inside the APK rather than a download. */
    data class Entry(val fetchedAtMillis: Long, val repeaters: List<Repeater>, val bundled: Boolean = false) {
        fun summary() = DataSummary(
            repeaters.count { it.service == Service.AMATEUR }, repeaters.count { it.service == Service.GMRS }, fetchedAtMillis, bundled,
        )
    }

    fun read(): Entry? {
        if (!file.exists()) return null
        return try {
            val doc = json.decodeFromString<CacheDoc>(file.readText())
            Entry(doc.fetchedAt, doc.repeaters.map { it.toDomain() })
        } catch (e: Exception) {
            // A corrupt cache is discarded rather than trusted.
            file.delete()
            null
        }
    }

    fun fetchedAt(): Long? = read()?.fetchedAtMillis

    @Throws(IOException::class)
    fun write(fetchedAt: Long, repeaters: List<Repeater>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(CacheDoc(fetchedAt, repeaters.map { it.toDto() })))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not write cache ${file.name}")
        }
    }

    fun clear() {
        file.delete()
    }

    @Serializable
    private data class CacheDoc(val fetchedAt: Long, val repeaters: List<RepeaterDto>)

    @Serializable
    private data class RepeaterDto(
        val id: String, val callsign: String, val out: Long, val inp: Long? = null,
        val up: String = "", val down: String = "", val city: String? = null, val county: String? = null,
        val state: String? = null, val lat: Double? = null, val lon: Double? = null, val source: String,
        val updated: String? = null, val url: String? = null, val notes: String? = null,
        val service: String? = null, val mixed: Boolean = false,
    )

    private fun Repeater.toDto() = RepeaterDto(
        id, callsign, outputHz, inputHz, SquelchText.format(uplinkTone), SquelchText.format(downlinkTone),
        city, county, state, latitude, longitude, source.name, lastUpdated, detailUrl, notes,
        service.name, mixedMode,
    )

    private fun RepeaterDto.toDomain() = Repeater(
        id = id, callsign = callsign, outputHz = out, inputHz = inp,
        uplinkTone = SquelchText.parse(up), downlinkTone = SquelchText.parse(down),
        city = city, county = county, state = state, latitude = lat, longitude = lon,
        source = RepeaterSource.valueOf(source), lastUpdated = updated, detailUrl = url, notes = notes,
        service = service?.let { s -> Service.entries.firstOrNull { it.name == s } } ?: Gmrs.serviceOf(out),
        mixedMode = mixed,
    )
}
