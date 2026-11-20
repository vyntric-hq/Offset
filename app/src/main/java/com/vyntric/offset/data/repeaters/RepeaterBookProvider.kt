/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.data.RepeaterBookCredentials
import com.vyntric.offset.domain.DataSummary
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilter
import com.vyntric.offset.domain.RepeaterProvider
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.RepeaterResult
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.Service
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.ChirpCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * RepeaterBook's export API with the user's own app-bound token.
 *
 * Policy, from repeaterbook.com/wiki/doku.php?id=api:
 * - the token goes only in X-RB-App-Token, and the User-Agent names the app and the user's email;
 * - redirects are never followed and a 3xx is an error;
 * - on 429 we stop and honour Retry-After (or back off exponentially when it is absent);
 * - caching is the minimum needed: the current session plus 24 hours per state on disk.
 */
class RepeaterBookProvider(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val credentials: () -> RepeaterBookCredentials,
    /** The User-Agent for a request made with the user's contact [email]. */
    private val userAgent: (email: String) -> String,
    private val clock: Clock,
    private val baseUrl: HttpUrl = DEFAULT_URL.toHttpUrl(),
) : RepeaterProvider {
    override val source = RepeaterSource.REPEATERBOOK
    override val supportsCounty = true

    private val mutex = Mutex()
    private val session = HashMap<Pair<UsState, Service>, RepeaterCache.Entry>()
    private var blockedUntil = 0L
    private var backoffMs = INITIAL_BACKOFF_MS

    override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean): RepeaterResult {
        if (query is RepeaterQuery.Text || query is RepeaterQuery.All) return searchCached(query)
        val states = when (query) {
            is RepeaterQuery.ByState -> listOf(query.state)
            is RepeaterQuery.Near -> {
                // "Closest N" has no radius; look at the states within 160 km to find them.
                val reach = query.radiusKm ?: CLOSEST_REACH_KM
                UsState.entries.filter { it.reachableFrom(query.latitude, query.longitude, reach) }
            }
            else -> emptyList()
        }
        val services = query.filters.services.sortedBy { it.ordinal }
        if (states.isEmpty() || services.isEmpty()) return RepeaterResult(emptyList(), source, null, fromCache = false)
        if (states.size > MAX_STATES_PER_SEARCH) {
            throw RepeaterException.BadData(
                "That radius reaches ${states.size} states. Choose a smaller radius or search by state."
            )
        }
        var oldest: Long? = null
        var allCached = true
        val repeaters = ArrayList<Repeater>()
        for (state in states) for (service in services) {
            val (entry, cached) = loadState(state, service, forceRefresh)
            repeaters += entry.repeaters
            oldest = minOf(oldest ?: entry.fetchedAtMillis, entry.fetchedAtMillis)
            allCached = allCached && cached
        }
        return RepeaterResult(RepeaterFilter.apply(repeaters, query), source, oldest, allCached)
    }

    /** Text search and the map of everything use only data already cached; no request is made. */
    private suspend fun searchCached(query: RepeaterQuery): RepeaterResult {
        val entries = cachedEntries()
        if (entries.isEmpty()) {
            throw RepeaterException.NotConfigured(
                "RepeaterBook data is fetched one state at a time. Search near you or by state first, then this uses it."
            )
        }
        return RepeaterResult(
            RepeaterFilter.apply(entries.flatMap { it.repeaters }, query), source,
            entries.minOf { it.fetchedAtMillis }, fromCache = true,
        )
    }

    /** One small live request (District of Columbia) to check the token and User-Agent. */
    suspend fun testConnection(): Int = mutex.withLock { fetch(UsState.DC, Service.AMATEUR).size }

    override suspend fun summary(): DataSummary? {
        val entries = cachedEntries()
        if (entries.isEmpty()) return null
        val all = entries.flatMap { it.repeaters }
        return DataSummary(all.count { it.service == Service.AMATEUR }, all.count { it.service == Service.GMRS }, entries.minOf { it.fetchedAtMillis })
    }

    override suspend fun cachedAtMillis(): Long? = cachedEntries().minOfOrNull { it.fetchedAtMillis }

    override suspend fun clearCache() = mutex.withLock {
        session.clear()
        withContext(Dispatchers.IO) { cacheDir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /** Fresh (under 24 hours) cached entries, from this session and from disk. */
    private suspend fun cachedEntries(): List<RepeaterCache.Entry> = mutex.withLock {
        val now = clock.millis()
        val disk = withContext(Dispatchers.IO) { cacheDir.listFiles()?.mapNotNull { RepeaterCache(it).read() }.orEmpty() }
        disk.filter { now - it.fetchedAtMillis < DISK_TTL_MS }
    }

    private suspend fun loadState(state: UsState, service: Service, force: Boolean): Pair<RepeaterCache.Entry, Boolean> = mutex.withLock {
        val now = clock.millis()
        val key = state to service
        if (!force) {
            session[key]?.takeIf { now - it.fetchedAtMillis < DISK_TTL_MS }?.let { return@withLock it to true }
            val disk = withContext(Dispatchers.IO) { cacheFor(state, service).read() }
            if (disk != null && now - disk.fetchedAtMillis < DISK_TTL_MS) {
                session[key] = disk
                return@withLock disk to true
            }
        }
        val entry = RepeaterCache.Entry(now, fetch(state, service))
        session[key] = entry
        withContext(Dispatchers.IO) { runCatching { cacheFor(state, service).write(entry.fetchedAtMillis, entry.repeaters) } }
        entry to false
    }

    private fun cacheFor(state: UsState, service: Service) = RepeaterCache(
        File(cacheDir, if (service == Service.GMRS) "gmrs-state-${state.fips}.json" else "state-${state.fips}.json")
    )

    private suspend fun fetch(state: UsState, service: Service): List<Repeater> {
        val creds = credentials()
        if (!creds.isComplete) {
            throw RepeaterException.NotConfigured("Add your RepeaterBook token and contact email in Settings first.")
        }
        val now = clock.millis()
        if (now < blockedUntil) throw RepeaterException.RateLimited(blockedUntil - now)
        val url = baseUrl.newBuilder().addQueryParameter("state_id", state.fips).apply {
            // GMRS needs stype=gmrs (RepeaterBook API docs); amateur asks for analog FM only.
            if (service == Service.GMRS) addQueryParameter("stype", "gmrs") else addQueryParameter("mode", "analog")
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("X-RB-App-Token", creds.token)
            .header("User-Agent", userAgent(creds.email))
            .header("Accept", "application/json")
            .build()
        return try {
            Http.execute(client, request) { response -> handle(response, state, service) }
        } catch (e: IOException) {
            throw Http.networkError(e, "repeaterbook.com")
        }
    }

    private fun handle(response: Response, state: UsState, service: Service): List<Repeater> {
        val code = response.code
        when {
            code in 300..399 -> throw RepeaterException.Redirected(response.header("Location"))
            code == 429 -> {
                val wait = retryAfterMillis(response.header("Retry-After")) ?: backoffMs.also {
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                blockedUntil = clock.millis() + wait
                throw RepeaterException.RateLimited(wait)
            }
            code == 401 || code == 403 -> throw RepeaterException.Auth(authMessage(response.body?.string()))
            !response.isSuccessful -> throw RepeaterException.Network("RepeaterBook returned HTTP $code.")
        }
        backoffMs = INITIAL_BACKOFF_MS
        val text = response.body?.string() ?: throw RepeaterException.BadData("RepeaterBook returned an empty response.")
        return parse(text, state, service)
    }

    private fun retryAfterMillis(header: String?): Long? {
        val h = header?.trim() ?: return null
        h.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(1000) }
        return runCatching {
            val at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - clock.millis()).coerceAtLeast(1000)
        }.getOrNull()
    }

    private fun authMessage(body: String?): String {
        val code = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { o -> (o["error_code"] ?: o["error"] ?: o["code"] ?: o["message"])?.let { (it as? JsonPrimitive)?.contentOrNull } }
        return when (code) {
            "auth_missing", "auth_invalid" -> "RepeaterBook did not accept the token. Check it in Settings."
            "auth_inactive", "auth_revoked" -> "This RepeaterBook token is inactive or revoked. Generate a new one."
            "auth_scope_denied" -> "This RepeaterBook token is not approved for the export API."
            "ua_mismatch" -> UA_REJECTED
            null -> "RepeaterBook refused the request. Check your token and email in Settings."
            else -> if (code.contains("User-Agent", ignoreCase = true)) UA_REJECTED else "RepeaterBook refused the request ($code)."
        }
    }

    companion object {
        const val DEFAULT_URL = "https://www.repeaterbook.com/api/export.php"

        /**
         * The User-Agent RepeaterBook stored when it approved Offset (app ID 152, 2026-11-20): the
         * whole application answer, matched as a prefix, so only this exact text passes its
         * check (tested live 2026-11-20). Used until RepeaterBook changes the approved value to
         * `Offset/`; then go back to [standardUserAgent]. Character for character; do not edit.
         */
        const val APPROVED_USER_AGENT =
            "Offset/<version> (github.com/vyntric-hq/offset; <user's contact email>) Please approve the pattern: " +
                "Offset/* (github.com/vyntric-hq/offset; *) Example: Offset/0.3.3 (github.com/vyntric-hq/offset; user@example.com)"

        /** The User-Agent RepeaterBook's policy asks for: app, version, project and contact email. */
        fun standardUserAgent(version: String, project: String, email: String) = "Offset/$version ($project; $email)"
        private const val UA_REJECTED = "RepeaterBook rejected Offset's User-Agent."
        const val DISK_TTL_MS = 24L * 60 * 60 * 1000
        const val INITIAL_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L
        const val MAX_STATES_PER_SEARCH = 4
        const val CLOSEST_REACH_KM = 160.0
        private val DIGITAL_FIELDS = listOf("DMR", "D-Star", "System Fusion", "APCO P-25", "NXDN", "M17", "Tetra")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses an export.php response. */
        fun parse(text: String, requested: UsState, service: Service = Service.AMATEUR): List<Repeater> {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw RepeaterException.BadData("RepeaterBook's data could not be read.", e)
            }
            (root["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.equals("error", true) }?.let {
                val msg = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
                throw RepeaterException.BadData("RepeaterBook reported an error: $msg")
            }
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { (it as? JsonObject)?.let { o -> toRepeater(o, requested, service) } }
        }

        private fun toRepeater(o: JsonObject, requested: UsState, service: Service): Repeater? {
            fun s(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            if (s("FM Analog")?.equals("No", true) == true) return null
            if (s("Operational Status")?.contains("off", true) == true) return null
            val out = s("Frequency")?.let { ChirpCsv.parseFreq(it) }?.takeIf { it > 0 } ?: return null
            val input = s("Input Freq")?.let { ChirpCsv.parseFreq(it) }?.takeIf { it > 0 }
            val stateId = s("State ID") ?: requested.fips
            val id = s("Rptr ID")
            return Repeater(
                id = "repeaterbook:$stateId:${id ?: out}",
                callsign = s("Callsign")?.uppercase() ?: return null,
                outputHz = out,
                inputHz = input ?: out,
                uplinkTone = ToneText.parse(s("PL")),
                downlinkTone = ToneText.parse(s("TSQ")),
                city = s("Nearest City") ?: s("Landmark"),
                county = s("County"),
                state = UsState.parse(s("State"))?.abbr ?: requested.abbr,
                latitude = s("Lat")?.toDoubleOrNull(),
                longitude = s("Long")?.toDoubleOrNull(),
                source = RepeaterSource.REPEATERBOOK,
                lastUpdated = s("Last Update"),
                detailUrl = id?.let {
                    val section = if (service == Service.GMRS) "gmrs" else "repeaters"
                    "https://www.repeaterbook.com/$section/details.php?state_id=$stateId&ID=$it"
                },
                notes = s("Notes")?.take(500),
                service = service,
                mixedMode = DIGITAL_FIELDS.any { s(it).equals("Yes", ignoreCase = true) },
            )
        }
    }
}
