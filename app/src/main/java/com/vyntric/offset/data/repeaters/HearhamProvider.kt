/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Field mapping follows Repeater-START (github.com/programmin1/Repeater-START), GPL.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.domain.DataSummary
import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilter
import com.vyntric.offset.domain.RepeaterProvider
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.RepeaterResult
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.Band
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.time.Clock

/**
 * Hearham's public repeater list (no token). The whole list is fetched once, filtered to analog
 * FM repeaters on 2m and 70cm, and cached on disk for [CACHE_TTL_MS]. Searches run locally.
 */
class HearhamProvider(
    private val client: OkHttpClient,
    cacheFile: File,
    private val userAgent: String,
    private val clock: Clock,
    private val url: HttpUrl = DEFAULT_URL.toHttpUrl(),
    /** Snapshot shipped in the APK, so search works on first launch with no network. */
    private val bundle: Bundle? = null,
) : RepeaterProvider {
    override val source = RepeaterSource.HEARHAM
    override val supportsCounty = false

    /** A Hearham API response (gzipped when [gzipped]) and when it was taken. */
    class Bundle(val fetchedAtMillis: Long, val gzipped: Boolean = true, val open: () -> InputStream)

    private val cache = RepeaterCache(cacheFile)
    private val mutex = Mutex()
    private var memory: RepeaterCache.Entry? = null
    private var bundled: RepeaterCache.Entry? = null
    private var bundleFailed = false

    /**
     * Offline first: answers from the newest data on the phone (a download or the bundled
     * snapshot), whatever its age, and flags it stale after [CACHE_TTL_MS]. The network is used
     * only for [forceRefresh] or when nothing is on the phone at all.
     */
    override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean): RepeaterResult {
        val (entry, fromCache) = load(forceRefresh)
        val stale = clock.millis() - entry.fetchedAtMillis >= CACHE_TTL_MS
        return RepeaterResult(RepeaterFilter.apply(entry.repeaters, query), source, entry.fetchedAtMillis, fromCache, stale)
    }

    override suspend fun summary(): DataSummary? = mutex.withLock { local()?.summary() }

    override suspend fun cachedAtMillis(): Long? = mutex.withLock { local()?.fetchedAtMillis }

    /** Removes the downloaded copy. The bundled snapshot stays. */
    override suspend fun clearCache() = mutex.withLock {
        memory = null
        withContext(Dispatchers.IO) { cache.clear() }
    }

    /** The newest of the downloaded cache and the bundled snapshot. Call with [mutex] held. */
    private suspend fun local(): RepeaterCache.Entry? {
        val downloaded = memory ?: withContext(Dispatchers.IO) { cache.read() }?.also { memory = it }
        val snapshot = bundled ?: bundle?.takeIf { !bundleFailed }?.let { b ->
            withContext(Dispatchers.IO) {
                runCatching {
                    b.open().use { raw -> RepeaterCache.Entry(b.fetchedAtMillis, parse(if (b.gzipped) GZIPInputStream(raw) else raw), bundled = true) }
                }.onFailure {
                    // Logged once; the app still works from downloads.
                    bundleFailed = true
                    android.util.Log.w("Offset", "Bundled repeater data unreadable: ${it.javaClass.simpleName}")
                }.getOrNull()
            }
        }?.also { bundled = it }
        return listOfNotNull(downloaded, snapshot).maxByOrNull { it.fetchedAtMillis }
    }

    private suspend fun load(force: Boolean): Pair<RepeaterCache.Entry, Boolean> = mutex.withLock {
        val local = local()
        if (!force && local != null) return local to true
        try {
            val fresh = RepeaterCache.Entry(clock.millis(), fetch())
            withContext(Dispatchers.IO) { cache.write(fresh.fetchedAtMillis, fresh.repeaters) }
            memory = fresh
            fresh to false
        } catch (e: RepeaterException) {
            if (local != null && !force) local to true else throw e
        }
    }

    private suspend fun fetch(): List<Repeater> {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        return try {
            Http.execute(client, request) { response ->
                if (!response.isSuccessful) throw RepeaterException.Network("Hearham returned HTTP ${response.code}.")
                val body = response.body ?: throw RepeaterException.BadData("Hearham returned an empty response.")
                body.byteStream().use { parse(it) }
            }
        } catch (e: IOException) {
            throw Http.networkError(e, "hearham.com")
        }
    }

    @Serializable
    internal data class Dto(
        val id: Long,
        val callsign: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val city: String? = null,
        val mode: String? = null,
        val encode: String? = null,
        val decode: String? = null,
        val frequency: Long = 0,
        val offset: Long = 0,
        val description: String? = null,
        val operational: Int? = null,
    )

    companion object {
        const val DEFAULT_URL = "https://hearham.com/api/repeaters/v1"
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        /** Parses the API response and keeps operational analog FM repeaters on 2m and 70cm. */
        @OptIn(ExperimentalSerializationApi::class)
        fun parse(stream: InputStream): List<Repeater> {
            val all = try {
                json.decodeFromStream<List<Dto>>(stream)
            } catch (e: IllegalArgumentException) {
                throw RepeaterException.BadData("Hearham's data could not be read.", e)
            }
            return all.mapNotNull { it.toRepeater() }
        }

        private fun modeTokens(mode: String?) =
            mode.orEmpty().uppercase().split('/', ',', ' ', '+').map { it.trim() }.filter { it.isNotEmpty() }

        internal fun isAnalogFm(mode: String?): Boolean = modeTokens(mode).any { it == "FM" || it == "NFM" }

        /** FM plus a digital mode, for example "YSF/FM" or "DMR/FM". */
        internal fun isMixedMode(mode: String?): Boolean = modeTokens(mode).any { it != "FM" && it != "NFM" }

        private fun Dto.toRepeater(): Repeater? {
            if (operational == 0) return null
            if (!isAnalogFm(mode)) return null
            if (Band.of(frequency) == null && !Gmrs.isGmrs(frequency)) return null
            val call = callsign?.trim().orEmpty().ifEmpty { return null }
            val cityText = city?.trim()
            return Repeater(
                id = "hearham:$id",
                callsign = call.uppercase(),
                outputHz = frequency,
                inputHz = frequency + offset,
                uplinkTone = ToneText.parse(encode),
                downlinkTone = ToneText.parse(decode),
                city = cityText?.substringBefore(',')?.trim(),
                county = null,
                state = UsState.fromLocationText(cityText)?.abbr,
                latitude = latitude?.takeIf { it in -90.0..90.0 && it != 0.0 },
                longitude = longitude?.takeIf { it in -180.0..180.0 && it != 0.0 },
                source = RepeaterSource.HEARHAM,
                detailUrl = "https://hearham.com/repeaters/$id",
                notes = description?.take(500),
                mixedMode = isMixedMode(mode),
            )
        }
    }
}
