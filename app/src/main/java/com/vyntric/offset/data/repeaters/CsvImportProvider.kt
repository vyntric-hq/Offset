/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.domain.DataSummary
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilter
import com.vyntric.offset.domain.RepeaterProvider
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.RepeaterResult
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.ChirpCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

/**
 * A CHIRP CSV imported from the file picker (coordinator lists, personal lists).
 *
 * CHIRP CSVs carry no coordinates, so optional extra columns are read when present: Callsign,
 * Latitude/Lat, Longitude/Long/Lon, City, County, State. When a file has no coordinates at all,
 * "near me" shows every in-band entry rather than nothing.
 */
class CsvImportProvider(private val file: File, private val clock: Clock) : RepeaterProvider {
    override val source = RepeaterSource.CSV
    override val supportsCounty = true

    data class ImportSummary(val entries: Int, val errors: List<String>)

    private val mutex = Mutex()
    private var memory: RepeaterCache.Entry? = null
    private val cache = RepeaterCache(file)

    /** Parses and stores [csv], replacing any earlier import. */
    suspend fun import(csv: String, fileName: String): ImportSummary = mutex.withLock {
        val parsed = ChirpCsv.parse(csv)
        if (parsed.rows.isEmpty()) {
            throw RepeaterException.BadData(parsed.errors.firstOrNull() ?: "No channels found in $fileName.")
        }
        val repeaters = parsed.rows.mapIndexed { i, row -> toRepeater(row, i, fileName) }
        val entry = RepeaterCache.Entry(clock.millis(), repeaters)
        withContext(Dispatchers.IO) { cache.write(entry.fetchedAtMillis, entry.repeaters) }
        memory = entry
        ImportSummary(repeaters.size, parsed.errors)
    }

    override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean): RepeaterResult = mutex.withLock {
        val entry = memory ?: withContext(Dispatchers.IO) { cache.read() }?.also { memory = it }
            ?: throw RepeaterException.NotConfigured("Import a CHIRP CSV file to search it.")
        val all = entry.repeaters
        val located = all.any { it.latitude != null && it.longitude != null }
        val hasStates = all.any { it.state != null }
        val results = when {
            query is RepeaterQuery.Near && !located -> all.filter { query.filters.accepts(it) }
            query is RepeaterQuery.ByState && !hasStates -> all.filter { query.filters.accepts(it) }
            else -> RepeaterFilter.apply(all, query)
        }
        RepeaterResult(results, source, entry.fetchedAtMillis, fromCache = true)
    }

    override suspend fun summary(): DataSummary? = mutex.withLock {
        (memory ?: withContext(Dispatchers.IO) { cache.read() }?.also { memory = it })?.summary()
    }

    override suspend fun cachedAtMillis(): Long? = mutex.withLock { memory?.fetchedAtMillis ?: withContext(Dispatchers.IO) { cache.fetchedAt() } }

    override suspend fun clearCache() = mutex.withLock {
        memory = null
        withContext(Dispatchers.IO) { cache.clear() }
    }

    private fun toRepeater(row: ChirpCsv.Row, index: Int, fileName: String): Repeater {
        fun extra(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            row.extras.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }
        val ch = row.channel
        return Repeater(
            id = "csv:$index",
            callsign = (extra("Callsign", "Call") ?: ch.name).ifBlank { ChirpCsv.formatFreq(ch.rxFreqHz) }.uppercase(),
            outputHz = ch.rxFreqHz,
            inputHz = ch.txFreqHz,
            uplinkTone = ch.txSquelch,
            downlinkTone = ch.rxSquelch,
            city = extra("City", "Location Name", "Nearest City") ?: row.comment.takeIf { it.isNotBlank() },
            county = extra("County"),
            state = UsState.parse(extra("State"))?.abbr ?: UsState.fromLocationText(row.comment)?.abbr,
            latitude = extra("Latitude", "Lat")?.toDoubleOrNull(),
            longitude = extra("Longitude", "Long", "Lon")?.toDoubleOrNull(),
            source = RepeaterSource.CSV,
            notes = "From $fileName, row ${index + 1}" + (row.comment.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
        )
    }
}
