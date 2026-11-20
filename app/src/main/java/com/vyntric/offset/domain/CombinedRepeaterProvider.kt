/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Several repeater providers searched as one. Each is asked at the same time; their results are
 * merged with [DirectoryMerge], so a repeater two directories list appears once with both
 * listings, earlier providers first. A provider that fails does not hide the others: its error
 * becomes a [RepeaterResult.warnings] entry. Only when every provider fails is the first error
 * thrown.
 */
class CombinedRepeaterProvider(private val providers: List<RepeaterProvider>) : RepeaterProvider {
    init {
        require(providers.isNotEmpty()) { "At least one provider" }
    }

    val sources: List<RepeaterSource> get() = providers.map { it.source }
    override val source: RepeaterSource get() = providers.first().source
    override val supportsCounty: Boolean get() = providers.any { it.supportsCounty }

    override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean): RepeaterResult =
        search(query, if (forceRefresh) sources.toSet() else emptySet())

    /** Searches every provider, fetching fresh data only from those in [refresh]. */
    suspend fun search(query: RepeaterQuery, refresh: Set<RepeaterSource>): RepeaterResult {
        val outcomes = coroutineScope {
            providers.map { p ->
                async {
                    try {
                        Result.success(p.search(query, p.source in refresh))
                    } catch (e: RepeaterException) {
                        Result.failure<RepeaterResult>(e)
                    }
                }
            }.awaitAll()
        }
        val results = outcomes.mapNotNull { it.getOrNull() }
        if (results.isEmpty()) throw outcomes.first().exceptionOrNull()!!
        val merged = results.map { it.repeaters }.reduce { acc, next -> DirectoryMerge.merge(acc, next, keepSimplex = true) }
        val warnings = providers.zip(outcomes).mapNotNull { (p, o) ->
            // "Search near you first" from a source with nothing cached is not worth a warning
            // while another source answered.
            val e = o.exceptionOrNull()?.takeUnless { it is RepeaterException.NotConfigured } ?: return@mapNotNull null
            val message = e.message.orEmpty()
            if (message.contains(p.source.displayName, ignoreCase = true)) message else "${p.source.displayName}: $message"
        }
        return RepeaterResult(
            repeaters = if (results.size == 1) merged else ordered(merged, query),
            source = results.first().source,
            fetchedAtMillis = results.mapNotNull { it.fetchedAtMillis }.minOrNull(),
            fromCache = results.all { it.fromCache },
            stale = results.any { it.stale },
            staleSources = results.flatMap { it.staleSources }.toSet(),
            warnings = warnings,
        )
    }

    /**
     * Each provider already filtered its own results (an imported list keeps entries without
     * coordinates on purpose), so the merged list is only re-sorted and cut to the query's limit.
     */
    private fun ordered(merged: List<Repeater>, query: RepeaterQuery): List<Repeater> {
        val sorted = merged.sortedWith(compareBy({ it.distanceKm ?: Double.MAX_VALUE }, { it.callsign }, { it.outputHz }))
        return when (query) {
            is RepeaterQuery.Near -> query.limit?.let { sorted.take(it) } ?: sorted
            is RepeaterQuery.Text -> sorted.take(RepeaterFilter.TEXT_LIMIT)
            else -> sorted
        }
    }

    override suspend fun cachedAtMillis(): Long? = providers.mapNotNull { it.cachedAtMillis() }.minOrNull()

    override suspend fun summary(): DataSummary? {
        val all = providers.mapNotNull { it.summary() }
        if (all.isEmpty()) return null
        return DataSummary(
            amateur = all.sumOf { it.amateur },
            gmrs = all.sumOf { it.gmrs },
            fetchedAtMillis = all.mapNotNull { it.fetchedAtMillis }.minOrNull(),
            bundled = all.size == 1 && all.single().bundled,
        )
    }

    override suspend fun clearCache() = providers.forEach { it.clearCache() }
}
