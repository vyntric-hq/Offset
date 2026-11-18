/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.propagation.GeoPoint
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TerrainRepositoryTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private val here = GeoPoint(38.25, -85.76)

    /** A PNG signature and a byte: the fake decoder needs nothing more. */
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)

    /** Each "PNG" is just the zoom number repeated; the fake decoder turns it into a flat tile at 100 m. */
    private val decode: (ByteArray) -> IntArray? = { bytes ->
        if (bytes.isEmpty()) null else IntArray(65536) { (0xFF shl 24) or ((32768 + 100) shr 8 shl 16) or (((32768 + 100) and 0xFF) shl 8) }
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("terrain").toFile()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun repo() = TerrainRepository(Http.publicClient(), dir, "Offset/test", decode, server.url("/terrarium").toString().trimEnd('/'))

    private fun serveAll() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(PNG))
        }
    }

    @Test fun `loads every tile for the area, saves them, and reports progress`() = runBlocking {
        serveAll()
        var last = 0 to 0
        val loaded = repo().load(here, 40_000.0) { d, t -> last = d to t }
        assertTrue(loaded.complete)
        assertTrue(loaded.downloaded > 0)
        assertEquals(loaded.downloaded, last.second)
        assertEquals(last.first, last.second)
        assertEquals(100.0, loaded.terrain.elevationM(here.latitude, here.longitude), 1e-9)
        assertTrue(server.takeRequest().getHeader("User-Agent") == "Offset/test")
        assertTrue(repo().sizeBytes() > 0)
    }

    @Test fun `a second load uses the tiles on the phone and makes no requests`() = runBlocking {
        serveAll()
        repo().load(here, 40_000.0)
        val before = server.requestCount
        val again = repo().load(here, 40_000.0)
        assertEquals(0, again.downloaded)
        assertEquals(before, server.requestCount)
        assertTrue(again.complete)
    }

    @Test fun `offline with nothing saved returns flat terrain and says what is missing`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        val loaded = repo().load(here, 40_000.0)
        assertTrue(loaded.missing > 0)
        assertEquals(0.0, loaded.terrain.elevationM(here.latitude, here.longitude), 1e-9)
    }

    @Test fun `offline falls back to a coarser zoom already on the phone`() = runBlocking {
        serveAll()
        val r = repo()
        val box = r.tilesInBox(38.6, -86.3, 37.9, -85.2)
        r.download(38.6, -86.3, 37.9, -85.2) { _, _ -> }
        // Now the server is gone; a larger radius needs a zoom not saved at full detail everywhere.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        assertTrue(box.isNotEmpty())
        val loaded = r.load(here, 25_000.0)
        assertTrue(loaded.complete)
        assertEquals(100.0, loaded.terrain.elevationM(here.latitude, here.longitude), 1e-9)
    }

    @Test fun `clear removes everything`() = runBlocking {
        serveAll()
        val r = repo()
        r.load(here, 40_000.0)
        r.clear()
        assertEquals(0L, r.sizeBytes())
    }

    @Test fun `after the first network failure a load stops asking the network`() = runBlocking {
        // Every request is dropped at the socket: an IOException, as offline or on a dead connection.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val tiles = com.vyntric.offset.propagation.Tiles.covering(here, 40_000.0, com.vyntric.offset.propagation.Tiles.zoomFor(here, 40_000.0).coerceAtMost(11)).size
        val loaded = repo().load(here, 40_000.0)
        assertEquals(tiles, loaded.missing)
        // Only the fetches already under way when the first failed reach the server, not one per tile.
        assertTrue("${server.requestCount} of $tiles tiles were requested", server.requestCount <= 12 && tiles > 20)
    }

    @Test fun `an offline state download stops at the first network failure`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val r = repo()
        val total = r.tilesInBox(38.6, -86.3, 37.9, -85.2).size
        val failed = r.download(38.6, -86.3, 37.9, -85.2) { _, _ -> }
        assertEquals(total, failed)
        assertTrue("${server.requestCount} of $total tiles were requested", server.requestCount < 60 && total > 200)
    }

    @Test fun `a state download reaches past the border by the coverage margin`() {
        val r = repo()
        val inside = r.tilesInBox(38.6, -86.3, 37.9, -85.2)
        // A point 80 km north of the box's north edge is inside the margin at every zoom.
        val north = com.vyntric.offset.propagation.Earth.destination(GeoPoint(38.6, -85.75), 0.0, 80_000.0)
        for (z in TerrainRepository.MIN_FALLBACK_ZOOM..TerrainRepository.OFFLINE_ZOOM) {
            val (x, y) = com.vyntric.offset.propagation.Tiles.pixel(north.latitude, north.longitude, z)
            assertTrue(inside.contains(com.vyntric.offset.propagation.TileId(z, (x / 256).toInt(), (y / 256).toInt())))
        }
    }

    @Test fun `a response that is not a PNG is never saved as terrain`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>captive portal</html>")
        }
        val r = repo()
        val failed = r.download(38.3, -85.8, 38.2, -85.7) { _, _ -> }
        assertTrue(failed > 0)
        assertEquals(0L, r.sizeBytes())
    }

    @Test fun `saving Alaska's margin wraps across the antimeridian`() {
        val ak = com.vyntric.offset.domain.UsState.AK
        val ids = repo().tilesInBox(ak.maxLat, ak.minLon, ak.minLat, ak.maxLon)
        // Alaska's box ends at 179.15 W; its 150 km margin reaches past 180 to 178.5 E.
        val (x, y) = com.vyntric.offset.propagation.Tiles.pixel(52.0, 178.5, 9)
        assertTrue(ids.contains(com.vyntric.offset.propagation.TileId(9, (x / 256).toInt(), (y / 256).toInt())))
    }

    @Test fun `a damaged tile on the phone is fetched again`() = runBlocking {
        serveAll()
        val r = repo()
        r.load(here, 40_000.0)
        // Damage every saved tile: still PNG named, but the decoder rejects empty files.
        dir.walkTopDown().filter { it.isFile }.forEach { it.writeBytes(ByteArray(0)) }
        val before = server.requestCount
        val again = TerrainRepository(Http.publicClient(), dir, "Offset/test", decode, server.url("/terrarium").toString().trimEnd('/')).load(here, 40_000.0)
        assertTrue(again.complete)
        assertTrue(server.requestCount > before)
    }

    @Test fun `a server answering 503 stops the download like a network failure`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        val r = repo()
        val total = r.tilesInBox(38.6, -86.3, 37.9, -85.2).size
        assertEquals(total, r.download(38.6, -86.3, 37.9, -85.2) { _, _ -> })
        assertTrue("${server.requestCount} of $total", server.requestCount < 60)
    }
}
