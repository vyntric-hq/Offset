package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Squelch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Several sources searched as one: merging, partial failure and per-source refresh. */
class CombinedRepeaterProviderTest {
    private class FakeProvider(
        override val source: RepeaterSource,
        private val repeaters: List<Repeater> = emptyList(),
        private val failure: RepeaterException? = null,
        private val stale: Boolean = false,
    ) : RepeaterProvider {
        override val supportsCounty = source == RepeaterSource.REPEATERBOOK
        val forced = mutableListOf<Boolean>()

        override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean): RepeaterResult {
            forced += forceRefresh
            failure?.let { throw it }
            return RepeaterResult(RepeaterFilter.apply(repeaters, query), source, 1_000L, fromCache = !forceRefresh, stale = stale && !forceRefresh)
        }

        override suspend fun cachedAtMillis(): Long? = null
        override suspend fun summary(): DataSummary? = null
        override suspend fun clearCache() = Unit
    }

    private fun rep(source: RepeaterSource, call: String, mhz: Double, lat: Double, lon: Double, inputHz: Long? = 1L) = Repeater(
        "${source.name}:$call", call, Math.round(mhz * 1_000_000), inputHz?.let { Math.round(mhz * 1_000_000) - 600_000 },
        Squelch.Ctcss(1000), Squelch.None, "Town", null, "KY", lat, lon, source,
    )

    private val here = RepeaterQuery.Near(38.25, -85.76, 50.0, RepeaterFilters())

    @Test fun `a repeater both sources list appears once with both listings, the first source's details winning`() = runTest {
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK, listOf(rep(RepeaterSource.REPEATERBOOK, "W4RB", 146.940, 38.26, -85.75)))
        val hh = FakeProvider(
            RepeaterSource.HEARHAM,
            listOf(rep(RepeaterSource.HEARHAM, "W4HH", 146.940, 38.27, -85.76), rep(RepeaterSource.HEARHAM, "K4ONLY", 147.000, 38.30, -85.70)),
        )
        val r = CombinedRepeaterProvider(listOf(rb, hh)).search(here)
        assertEquals(listOf("W4RB", "K4ONLY"), r.repeaters.map { it.callsign })
        assertEquals(listOf(RepeaterSource.REPEATERBOOK, RepeaterSource.HEARHAM), r.repeaters.first().verification.sources)
        assertTrue(r.warnings.isEmpty())
        assertTrue("merged list is sorted by distance", r.repeaters.zipWithNext().all { (a, b) -> a.distanceKm!! <= b.distanceKm!! })
    }

    @Test fun `a failing source becomes a warning while the other still answers`() = runTest {
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK, failure = RepeaterException.Auth("RepeaterBook rejected the token."))
        val hh = FakeProvider(RepeaterSource.HEARHAM, listOf(rep(RepeaterSource.HEARHAM, "W4HH", 146.940, 38.27, -85.76)))
        val r = CombinedRepeaterProvider(listOf(rb, hh)).search(here)
        assertEquals(listOf("W4HH"), r.repeaters.map { it.callsign })
        assertEquals(listOf("RepeaterBook rejected the token."), r.warnings)
    }

    @Test fun `a source with nothing cached is not a warning`() = runTest {
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK, failure = RepeaterException.NotConfigured("Search first."))
        val hh = FakeProvider(RepeaterSource.HEARHAM, listOf(rep(RepeaterSource.HEARHAM, "W4HH", 146.940, 38.27, -85.76)))
        assertTrue(CombinedRepeaterProvider(listOf(rb, hh)).search(here).warnings.isEmpty())
    }

    @Test fun `when every source fails the first error is thrown`() = runTest {
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK, failure = RepeaterException.Auth("token"))
        val hh = FakeProvider(RepeaterSource.HEARHAM, failure = RepeaterException.Network("offline"))
        try {
            CombinedRepeaterProvider(listOf(rb, hh)).search(here)
            fail("expected Auth")
        } catch (e: RepeaterException.Auth) {
            assertEquals("token", e.message)
        }
    }

    @Test fun `a simplex entry from a chosen source is kept`() = runTest {
        val hh = FakeProvider(RepeaterSource.HEARHAM, listOf(rep(RepeaterSource.HEARHAM, "W4HH", 146.940, 38.27, -85.76)))
        val csv = FakeProvider(RepeaterSource.CSV, listOf(rep(RepeaterSource.CSV, "SIMPLEX", 146.520, 38.25, -85.76, inputHz = null)))
        assertEquals(2, CombinedRepeaterProvider(listOf(hh, csv)).search(here).repeaters.size)
    }

    @Test fun `an imported entry without coordinates survives a merge with another source`() = runTest {
        val hh = FakeProvider(RepeaterSource.HEARHAM, listOf(rep(RepeaterSource.HEARHAM, "W4HH", 146.940, 38.27, -85.76)))
        val noPlace = rep(RepeaterSource.CSV, "CLUB", 147.300, 0.0, 0.0).copy(latitude = null, longitude = null)
        // The CSV provider keeps coordinate-free entries in a nearby search; the merge must too.
        val csv = object : RepeaterProvider by FakeProvider(RepeaterSource.CSV) {
            override suspend fun search(query: RepeaterQuery, forceRefresh: Boolean) =
                RepeaterResult(listOf(noPlace), RepeaterSource.CSV, null, fromCache = true)
        }
        val r = CombinedRepeaterProvider(listOf(hh, csv)).search(here)
        assertEquals(listOf("W4HH", "CLUB"), r.repeaters.map { it.callsign })
    }

    @Test fun `refreshing fetches only the stale sources`() = runTest {
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK)
        val hh = FakeProvider(RepeaterSource.HEARHAM, stale = true)
        val combined = CombinedRepeaterProvider(listOf(rb, hh))
        val first = combined.search(here)
        assertEquals(setOf(RepeaterSource.HEARHAM), first.staleSources)
        combined.search(here, refresh = first.staleSources)
        assertEquals(listOf(false, false), rb.forced)
        assertEquals(listOf(false, true), hh.forced)
    }

    @Test fun `closest N is applied to the merged list`() = runTest {
        val q = RepeaterQuery.Near(38.25, -85.76, null, RepeaterFilters(), limit = 2)
        val rb = FakeProvider(RepeaterSource.REPEATERBOOK, listOf(rep(RepeaterSource.REPEATERBOOK, "A", 146.700, 38.26, -85.76), rep(RepeaterSource.REPEATERBOOK, "C", 146.800, 38.60, -85.76)))
        val hh = FakeProvider(RepeaterSource.HEARHAM, listOf(rep(RepeaterSource.HEARHAM, "B", 147.100, 38.30, -85.76), rep(RepeaterSource.HEARHAM, "D", 147.200, 38.70, -85.76)))
        assertEquals(listOf("A", "B"), CombinedRepeaterProvider(listOf(rb, hh)).search(q).repeaters.map { it.callsign })
    }
}
