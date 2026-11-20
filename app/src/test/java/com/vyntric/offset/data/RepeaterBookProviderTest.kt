package com.vyntric.offset.data

import com.vyntric.offset.AppFixtures
import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.data.repeaters.RepeaterBookProvider
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilters
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.Service
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Band
import com.vyntric.offset.protocol.Squelch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepeaterBookProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val fixture = String(AppFixtures.bytes("repeaterbook-fixture.json"))
    private var creds = RepeaterBookCredentials("rbuapp_secret", "op@example.org")
    private val both = RepeaterFilters()
    private val california = RepeaterQuery.ByState(UsState.CA, null, both, 37.33 to -121.88)

    private fun provider() = RepeaterBookProvider(
        Http.authenticatedClient(), tmp.root.resolve("rb"), { creds },
        { RepeaterBookProvider.standardUserAgent("0.1.0", "github.com/vyntric-hq/offset", it) }, fixedClock, server.url("/api/export.php"),
    )

    @After fun stop() = server.shutdown()

    @Test fun `sends the token header, the user agent with email, and the state query`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val r = provider().search(california)
        val req = server.takeRequest()
        assertEquals("rbuapp_secret", req.getHeader("X-RB-App-Token"))
        assertEquals("Offset/0.1.0 (github.com/vyntric-hq/offset; op@example.org)", req.getHeader("User-Agent"))
        assertEquals("06", req.requestUrl!!.queryParameter("state_id"))
        assertEquals("analog", req.requestUrl!!.queryParameter("mode"))
        assertEquals(null, req.getHeader("Authorization"))
        assertEquals(2, r.repeaters.size) // the off-air entry is dropped
        val first = r.repeaters.first()
        assertEquals("W0TEST", first.callsign)
        assertEquals(146_340_000L, first.inputHz)
        assertEquals(Squelch.Ctcss(1000), first.uplinkTone)
        assertEquals("Santa Clara", first.county)
        assertEquals("2026-10-01", first.lastUpdated)
        assertEquals("https://www.repeaterbook.com/repeaters/details.php?state_id=06&ID=1001", first.detailUrl)
        assertEquals(Squelch.Dcs(23), r.repeaters[1].uplinkTone)
    }

    @Test fun `GMRS uses stype=gmrs, its own cache, and GMRS detail links`() = runTest {
        server.enqueue(MockResponse().setBody(gmrsFixture))
        val p = provider()
        val r = p.search(california.copy(filters = RepeaterFilters(services = setOf(Service.GMRS))))
        val req = server.takeRequest()
        assertEquals("gmrs", req.requestUrl!!.queryParameter("stype"))
        assertEquals(null, req.requestUrl!!.queryParameter("mode"))
        assertEquals(listOf("WRXX123"), r.repeaters.map { it.callsign })
        assertEquals(Service.GMRS, r.repeaters.first().service)
        assertEquals("https://www.repeaterbook.com/gmrs/details.php?state_id=06&ID=77", r.repeaters.first().detailUrl)
        // Both services: amateur is fetched, GMRS comes from cache.
        server.enqueue(MockResponse().setBody(fixture))
        val both = p.search(california.copy(filters = RepeaterFilters(services = Service.entries.toSet())))
        assertEquals(3, both.repeaters.size)
        assertEquals(2, server.requestCount)
        assertEquals(2, p.summary()!!.amateur)
        assertEquals(1, p.summary()!!.gmrs)
    }

    @Test fun `digital flags mark mixed mode and text search never makes a request`() = runTest {
        val p = provider()
        try {
            p.search(RepeaterQuery.Text("W0TEST", both, null))
            fail("expected NotConfigured before any state is cached")
        } catch (e: RepeaterException.NotConfigured) {
            assertEquals(0, server.requestCount)
        }
        server.enqueue(MockResponse().setBody(fixture))
        p.search(california)
        val text = p.search(RepeaterQuery.Text("pleasanton", both, null))
        assertEquals(listOf("W0TEST"), text.repeaters.map { it.callsign })
        assertTrue(text.repeaters.first().mixedMode)
        assertEquals(1, p.search(RepeaterQuery.All(both.copy(hideMixedMode = true), null)).repeaters.size)
        assertEquals(1, server.requestCount)
    }

    private val gmrsFixture = """{"count":1,"results":[{"State ID":"06","Rptr ID":"77","Frequency":"462.67500","Input Freq":"467.67500",
        "PL":"141.3","TSQ":"141.3","County":"Santa Clara","State":"California","Lat":"37.30","Long":"-121.90","Callsign":"WRXX123",
        "Operational Status":"On-air","FM Analog":"Yes","Nearest City":"San Jose"}]}"""

    @Test fun `county filter and 24 hour per state cache`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val p = provider()
        p.search(california)
        val alameda = p.search(california.copy(county = "Alameda"))
        assertEquals(listOf("Alameda"), alameda.repeaters.map { it.county })
        assertTrue(alameda.fromCache)
        // A new instance (new session) still uses the disk cache.
        provider().search(california)
        assertEquals(1, server.requestCount)
        provider().clearCache()
        server.enqueue(MockResponse().setBody(fixture))
        provider().search(california)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a redirect is an error and is never followed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/steal"))
        try {
            provider().search(california)
            fail("expected Redirected")
        } catch (e: RepeaterException.Redirected) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `429 honours Retry-After and blocks further requests until it passes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val p = provider()
        try {
            p.search(california)
            fail("expected RateLimited")
        } catch (e: RepeaterException.RateLimited) {
            assertEquals(120_000, e.retryAfterMillis)
        }
        try {
            p.search(california)
            fail("expected RateLimited")
        } catch (e: RepeaterException.RateLimited) {
            assertEquals("no second request while backing off", 1, server.requestCount)
        }
    }

    @Test fun `auth failures explain themselves`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"ua_mismatch\"}"))
        try {
            provider().search(california)
            fail("expected Auth")
        } catch (e: RepeaterException.Auth) {
            assertTrue(e.message!!.contains("User-Agent"))
        }
    }

    @Test fun `RepeaterBook's error_code field is read`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            "{\"ok\":false,\"error_code\":\"ua_mismatch\",\"message\":\"Application User-Agent policy check failed.\"}"
        ))
        try {
            provider().search(california)
            fail("expected Auth")
        } catch (e: RepeaterException.Auth) {
            assertEquals("RepeaterBook rejected Offset's User-Agent.", e.message)
        }
    }

    @Test fun `the approved User-Agent is the exact text that passed RepeaterBook's check`() {
        // The value that returned HTTP 200 from export.php on 2026-11-20.
        assertEquals(
            "Offset/<version> (github.com/vyntric-hq/offset; <user's contact email>) Please approve the pattern: Offset/* " +
                "(github.com/vyntric-hq/offset; *) Example: Offset/0.3.3 (github.com/vyntric-hq/offset; user@example.com)",
            RepeaterBookProvider.APPROVED_USER_AGENT,
        )
    }

    @Test fun `the approved User-Agent goes over the wire unchanged`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        RepeaterBookProvider(
            Http.authenticatedClient(), tmp.root.resolve("rb2"), { creds },
            { RepeaterBookProvider.APPROVED_USER_AGENT }, fixedClock, server.url("/api/export.php"),
        ).search(california)
        assertEquals(RepeaterBookProvider.APPROVED_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `not configured until token and email are present`() = runTest {
        creds = RepeaterBookCredentials("", "")
        try {
            provider().search(california)
            fail("expected NotConfigured")
        } catch (e: RepeaterException.NotConfigured) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `near me queries every reachable state and filters by distance`() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val r = provider().search(RepeaterQuery.Near(37.33, -121.88, 20.0, both))
        assertEquals(listOf("W0TEST"), r.repeaters.map { it.callsign }.distinct())
        assertTrue(r.repeaters.all { it.distanceKm!! <= 20.0 })
    }
}
