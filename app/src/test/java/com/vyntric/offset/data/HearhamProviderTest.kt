package com.vyntric.offset.data

import com.vyntric.offset.AppFixtures
import com.vyntric.offset.data.repeaters.HearhamProvider
import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilters
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.Service
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.Band
import com.vyntric.offset.protocol.Squelch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HearhamProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val fixture = String(AppFixtures.bytes("hearham-fixture.json"))
    private var now = Instant.parse("2026-11-13T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val sanJose = RepeaterQuery.Near(37.33874, -121.8852525, 16.09, RepeaterFilters())

    private fun provider() = HearhamProvider(
        Http.publicClient(), tmp.root.resolve("hearham.json"), "Offset/0.1.0 (github.com/vyntric-hq/offset)", clock,
        server.url("/api/repeaters/v1"),
    )

    @After fun stop() = server.shutdown()

    @Test fun `parses the recorded fixture keeping only operational analog FM on 2m and 70cm`() {
        val all = HearhamProvider.parse(fixture.byteInputStream())
        assertEquals(20, all.size)
        assertEquals(setOf("WRPR468", "WSCR594"), all.filter { it.service == Service.GMRS }.map { it.callsign }.toSet())
        assertTrue("YSF/FM is mixed mode", all.first { it.callsign == "K5IJX" }.mixedMode)
        assertFalse(all.first { it.callsign == "KB6FEC" }.mixedMode)
        val calls = all.map { it.callsign }.toSet()
        assertFalse("DMR only", "W6TCP" in calls)
        assertFalse("off air", "WB6ZVW" in calls)
        assertFalse("220 MHz", "W6BXN" in calls)
        assertTrue("mixed YSF/FM is analog capable", "K5IJX" in calls)
        val wa6sek = all.first { it.callsign == "WA6SEK" }
        assertEquals(145_210_000, wa6sek.outputHz)
        assertEquals(144_610_000L, wa6sek.inputHz)
        assertEquals(Squelch.Ctcss(1000), wa6sek.uplinkTone)
        assertEquals(Squelch.Ctcss(1000), wa6sek.downlinkTone)
        assertEquals("CA", wa6sek.state)
        assertEquals("https://hearham.com/repeaters/17343", wa6sek.detailUrl)
        assertEquals(Squelch.Dcs(23), all.first { it.callsign == "AB7BS" && it.outputHz < 200_000_000 }.uplinkTone)
        assertEquals("TX", all.first { it.callsign == "W5MGH" }.state)
        assertEquals(null, all.first { it.callsign == "VE2REM" }.state)
    }

    @Test fun `fetches with the app User-Agent, filters by distance and caches for 7 days`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val p = provider()
        val r = p.search(sanJose)
        val req = server.takeRequest()
        assertEquals("Offset/0.1.0 (github.com/vyntric-hq/offset)", req.getHeader("User-Agent"))
        assertFalse(r.fromCache)
        assertTrue(r.repeaters.isNotEmpty())
        assertTrue(r.repeaters.all { it.distanceKm!! <= 16.09 })
        assertTrue(r.repeaters.zipWithNext().all { (a, b) -> a.distanceKm!! <= b.distanceKm!! })
        assertTrue("KB6FEC" in r.repeaters.map { it.callsign })
        assertFalse("W6LLL" in r.repeaters.map { it.callsign })

        // Cached: a new provider instance (process restart) reads from disk without a request.
        now = now.plusSeconds(6 * 24 * 3600)
        val again = provider().search(sanJose)
        assertTrue(again.fromCache)
        assertFalse(again.stale)
        assertEquals(1, server.requestCount)

        // Offline first: after 7 days the local copy is still answered at once, flagged stale.
        now = now.plusSeconds(2 * 24 * 3600)
        val old = provider().search(sanJose)
        assertTrue(old.fromCache && old.stale)
        assertEquals(1, server.requestCount)

        // A refresh fetches.
        server.enqueue(MockResponse().setBody(fixture))
        assertFalse(provider().search(sanJose, forceRefresh = true).fromCache)
        assertEquals(2, server.requestCount)
    }

    @Test fun `by state uses the location text`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val r = provider().search(RepeaterQuery.ByState(UsState.TX, null, RepeaterFilters(bands = setOf(Band.TWO_METER)), null))
        assertEquals(setOf("K5RWK", "W5MGH", "W5QGG"), r.repeaters.map { it.callsign }.toSet())
    }

    @Test fun `offline with an expired cache serves the stale copy, without a cache it reports the error`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        provider().search(sanJose)
        now = now.plusSeconds(30L * 24 * 3600)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(provider().search(sanJose).fromCache)

        tmp.root.resolve("hearham.json").delete()
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            provider().search(sanJose)
            throw AssertionError("expected failure")
        } catch (e: RepeaterException.Network) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test fun `service, mixed mode, closest and text queries work on the cached list`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val p = provider()
        val gmrs = p.search(RepeaterQuery.Near(38.98, -78.9, 50.0, RepeaterFilters(services = setOf(Service.GMRS))))
        assertEquals(listOf("WSCR594"), gmrs.repeaters.map { it.callsign })
        val closest = p.search(RepeaterQuery.Near(37.33874, -121.8852525, null, RepeaterFilters(), limit = 3))
        assertEquals(3, closest.repeaters.size)
        assertTrue(closest.repeaters.all { it.distanceKm!! < 5 })
        val text = p.search(RepeaterQuery.Text("w5", RepeaterFilters(), null))
        assertEquals(setOf("W5MGH", "W5QGG"), text.repeaters.map { it.callsign }.toSet())
        val freq = p.search(RepeaterQuery.Text("146.66", RepeaterFilters(), null))
        assertEquals(setOf("W5MGH"), freq.repeaters.map { it.callsign }.toSet())
        val all = p.search(RepeaterQuery.All(RepeaterFilters(services = Service.entries.toSet(), hideMixedMode = true), null))
        assertFalse(all.repeaters.any { it.mixedMode })
        assertEquals(19, all.repeaters.size)
        val summary = p.summary()!!
        assertEquals(18, summary.amateur)
        assertEquals(2, summary.gmrs)
        assertEquals(1, server.requestCount)
    }

    @Test fun `manual refresh always fetches`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        server.enqueue(MockResponse().setBody(fixture))
        val p = provider()
        p.search(sanJose)
        p.search(sanJose, forceRefresh = true)
        assertEquals(2, server.requestCount)
    }

    private fun bundleFrom(json: String, at: Instant) = HearhamProvider.Bundle(at.toEpochMilli()) {
        val buf = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(buf).use { it.write(json.toByteArray()) }
        buf.toByteArray().inputStream()
    }

    private fun bundledProvider(bundle: HearhamProvider.Bundle) = HearhamProvider(
        Http.publicClient(), tmp.root.resolve("hearham.json"), "Offset/0.1.0 (github.com/vyntric-hq/offset)", clock,
        server.url("/api/repeaters/v1"), bundle,
    )

    @Test fun `bundled snapshot answers on first launch with no network`() = runTest {
        val bundleDate = now.minusSeconds(30L * 24 * 3600)
        val p = bundledProvider(bundleFrom(fixture, bundleDate))
        val r = p.search(sanJose)
        assertTrue(r.repeaters.isNotEmpty())
        assertTrue(r.fromCache && r.stale)
        assertEquals(bundleDate.toEpochMilli(), r.fetchedAtMillis)
        assertEquals(0, server.requestCount)
        val summary = p.summary()!!
        assertTrue(summary.bundled)
        assertEquals(18, summary.amateur)
    }

    @Test fun `a newer download wins over the bundle and clearing it falls back to the bundle`() = runTest {
        val bundleDate = now.minusSeconds(30L * 24 * 3600)
        val p = bundledProvider(bundleFrom(fixture, bundleDate))
        server.enqueue(MockResponse().setBody(fixture))
        val fresh = p.search(sanJose, forceRefresh = true)
        assertEquals(now.toEpochMilli(), fresh.fetchedAtMillis)
        assertFalse(bundledProvider(bundleFrom(fixture, bundleDate)).summary()!!.bundled)
        p.clearCache()
        val after = p.summary()!!
        assertTrue(after.bundled)
        assertEquals(bundleDate.toEpochMilli(), after.fetchedAtMillis)
    }

    @Test fun `a failed refresh reports the error but local data stays usable`() = runTest {
        val p = bundledProvider(bundleFrom(fixture, now.minusSeconds(30L * 24 * 3600)))
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            p.search(sanJose, forceRefresh = true)
            throw AssertionError("expected failure")
        } catch (e: RepeaterException.Network) {
            assertTrue(p.search(sanJose).repeaters.isNotEmpty())
        }
    }

    /** Live call, opt in: OFFSET_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests '*HearhamProviderTest*' */
    @Test fun `live API returns parseable data`() = runTest {
        assumeTrue("live tests disabled", System.getenv("OFFSET_LIVE_TESTS") == "1")
        val p = HearhamProvider(Http.publicClient(), tmp.root.resolve("live.json"), "Offset/test (github.com/vyntric-hq/offset)", Clock.systemUTC())
        val r = p.search(sanJose)
        assertTrue("expected repeaters near San Jose", r.repeaters.size > 3)
    }
}
