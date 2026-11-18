/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiledTerrainTest {
    private fun terrarium(metres: Double): Int {
        val v = metres + 32768.0
        val r = (v / 256).toInt()
        val g = v.toInt() % 256
        val b = ((v - v.toInt()) * 256).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `terrarium pixels decode to metres`() {
        val decoded = Terrarium.decode(intArrayOf(terrarium(0.0), terrarium(152.0), terrarium(-80.0), terrarium(4421.5)))
        assertEquals(listOf<Short>(0, 152, -80, 4421), decoded.toList())
    }

    @Test
    fun `tile maths matches the slippy map scheme`() {
        // Louisville, KY at zoom 11 is tile 536, 788 (x = 2048 (lon + 180) / 360, y from the Mercator formula).
        val (x, y) = Tiles.pixel(38.25, -85.76, 11)
        assertEquals(536, (x / 256).toInt())
        assertEquals(788, (y / 256).toInt())
        assertEquals(38.25, Tiles.latitudeOfPixelY(y, 11), 1e-9)
        assertEquals(60.0, Tiles.metresPerPixel(38.25, 11), 1.0)
    }

    @Test
    fun `zoom drops as the area grows so the download stays small`() {
        val here = GeoPoint(38.25, -85.76)
        val small = Tiles.zoomFor(here, 30_000.0)
        val large = Tiles.zoomFor(here, 150_000.0)
        assertTrue(small > large)
        assertTrue(Tiles.covering(here, 150_000.0, large).size <= 100)
        assertTrue(Tiles.covering(here, 30_000.0, small).size <= 100)
    }

    @Test
    fun `elevations interpolate across tile edges`() {
        // Two neighbouring tiles: the west one at 100 m, the east one at 200 m.
        val west = TileId(11, 536, 780)
        val east = TileId(11, 537, 780)
        val terrain = TiledTerrain(11, mapOf(west to ShortArray(65536) { 100 }, east to ShortArray(65536) { 200 }))
        val lonEdge = (537 * 256.0) / (256.0 * 2048) * 360.0 - 180.0
        val lat = Tiles.latitudeOfPixelY(780 * 256.0 + 128, 11)
        assertEquals(100.0, terrain.elevationM(lat, lonEdge - 0.01), 1e-9)
        assertEquals(200.0, terrain.elevationM(lat, lonEdge + 0.01), 1e-9)
        assertEquals(150.0, terrain.elevationM(lat, lonEdge), 1e-6)
        // A tile that was not loaded reads as sea level.
        assertEquals(0.0, terrain.elevationM(lat + 1.0, lonEdge), 1e-9)
    }

    @Test
    fun `an area across the antimeridian covers tiles on both sides and reads them`() {
        // Adak to Attu in the Aleutians straddle 180 degrees.
        val center = GeoPoint(52.0, 179.9)
        val z = 9
        val ids = Tiles.covering(center, 40_000.0, z)
        val max = (1 shl z) - 1
        assertTrue(ids.any { it.x == max } && ids.any { it.x == 0 })
        val terrain = TiledTerrain(z, ids.associateWith { if (it.x == 0) ShortArray(65536) { 300 } else ShortArray(65536) { 100 } })
        assertEquals(300.0, terrain.elevationM(52.0, -179.8), 1e-9)
        assertEquals(100.0, terrain.elevationM(52.0, 179.8), 1e-9)
    }
}
