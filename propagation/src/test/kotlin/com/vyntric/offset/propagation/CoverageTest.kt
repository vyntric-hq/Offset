/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import com.vyntric.offset.propagation.itm.PropagationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {
    /** Free space loss over the profile length: makes margins predictable by hand. */
    private val freeSpace = PathLossModel { p, _, _, f -> PathLoss(FreeSpace.lossDb(p[0] * p[1], f), PropagationMode.LINE_OF_SIGHT) }

    private val handheld = RfStation(heightM = 1.5, txPowerDbm = 37.0, antennaGainDbi = 0.0, lineLossDb = 0.0, sensitivityDbm = -121.0)
    private val louisville = GeoPoint(38.25, -85.76)

    @Test
    fun `distances and bearings match known values`() {
        val nyc = GeoPoint(40.7128, -74.0060)
        val la = GeoPoint(34.0522, -118.2437)
        assertEquals(3_936_000.0, Earth.distanceM(nyc, la), 5_000.0)
        assertEquals(273.7, Earth.bearingDeg(nyc, la), 0.5)
        val there = Earth.destination(louisville, 60.0, 25_000.0)
        assertEquals(25_000.0, Earth.distanceM(louisville, there), 1.0)
        assertEquals(60.0, Earth.bearingDeg(louisville, there), 0.1)
    }

    @Test
    fun `free space loss at 2 m over 10 km is about 96 dB`() {
        assertEquals(95.8, FreeSpace.lossDb(10_000.0, 146.0), 0.1)
    }

    @Test
    fun `a beam has full gain forward and the front to back ratio behind`() {
        val beam = AntennaPattern.Beam(aimDeg = 90.0, beamwidthDeg = 60.0, frontToBackDb = 15.0)
        assertEquals(0.0, beam.relativeDb(90.0), 1e-9)
        assertEquals(-3.0, beam.relativeDb(120.0), 1e-9)
        assertEquals(-3.0, beam.relativeDb(60.0), 1e-9)
        assertEquals(-15.0, beam.relativeDb(270.0), 1e-9)
        assertEquals(-3.0, AntennaPattern.Beam(aimDeg = 350.0, beamwidthDeg = 60.0).relativeDb(20.0), 1e-9)
    }

    @Test
    fun `talk uses my transmitter and their receiver, listen the reverse`() {
        val me = handheld.copy(txPowerDbm = 30.0, sensitivityDbm = -110.0)
        val repeater = RfStation(heightM = 30.0, txPowerDbm = 47.0, antennaGainDbi = 6.0, lineLossDb = 4.0, sensitivityDbm = -120.0)
        val talk = LinkBudget.of(Direction.TALK, me, repeater, 0.0, 145.0)
        assertEquals(30.0 - 145.0 + 6.0 - 4.0 - -120.0, talk.marginDb, 1e-9)
        val listen = LinkBudget.of(Direction.LISTEN, me, repeater, 0.0, 145.0)
        assertEquals(47.0 - 4.0 + 6.0 - 145.0 - -110.0, listen.marginDb, 1e-9)
        // The usual asymmetry: the repeater is heard (14 dB) better than it hears a handheld (7 dB).
        assertEquals(Verdict.LIKELY, listen.verdict)
        assertEquals(Verdict.MARGINAL, talk.verdict)
    }

    @Test
    fun `a beam aimed away from the far station costs its front to back ratio`() {
        val me = handheld.copy(antennaGainDbi = 9.0, pattern = AntennaPattern.Beam(aimDeg = 180.0, beamwidthDeg = 50.0))
        val toward = LinkBudget.of(Direction.TALK, me, handheld, 180.0, 120.0)
        val away = LinkBudget.of(Direction.TALK, me, handheld, 0.0, 120.0)
        assertEquals(15.0, toward.marginDb - away.marginDb, 1e-9)
    }

    @Test
    fun `verdict bands`() {
        assertEquals(Verdict.LIKELY, Verdict.of(10.0))
        assertEquals(Verdict.MARGINAL, Verdict.of(9.9))
        assertEquals(Verdict.MARGINAL, Verdict.of(0.0))
        assertEquals(Verdict.UNLIKELY, Verdict.of(-0.1))
    }

    private val talk = MarginFunction.of(Direction.TALK, handheld, handheld)

    @Test
    fun `coverage over flat earth falls off with distance and is the same in every direction`() {
        val request = CoverageRequest(louisville, 1.5, 1.5, 146.0, radiusM = 40_000.0, stepM = 1_000.0, radials = 36)
        val grid = CoverageEngine(Terrain.FLAT, freeSpace).compute(request)
        assertEquals(1f, grid.completeness(), 0f)
        assertEquals(FreeSpace.lossDb(1_000.0, 146.0), grid.loss(0, 0).toDouble(), 0.01)
        for (r in 0 until 36) {
            assertEquals(grid.loss(0, 20), grid.loss(r, 20), 1e-3f)
            for (s in 1 until grid.samples) assertTrue(grid.loss(r, s) > grid.loss(r, s - 1))
        }
        // 5 W into free space 5 km away: 37 - 89.7 = -52.7 dBm, 68 dB over -121 dBm.
        assertEquals(37.0 - FreeSpace.lossDb(5_000.0, 146.0) + 121.0, grid.marginAt(0.0, 5_000.0, talk)!!, 0.01)
    }

    @Test
    fun `grid interpolation returns the samples and nothing outside the radius`() {
        val request = CoverageRequest(louisville, 1.5, 1.5, 146.0, radiusM = 10_000.0, stepM = 1_000.0, radials = 8)
        val grid = CoverageEngine(Terrain.FLAT, freeSpace).compute(request)
        val lossOnly = MarginFunction { _, loss -> -loss }
        assertEquals(-grid.loss(0, 4).toDouble(), grid.marginAt(0.0, 5_000.0, lossOnly)!!, 1e-3)
        assertEquals(-grid.loss(2, 4).toDouble(), grid.marginAt(5_000.0, 0.0, lossOnly)!!, 1e-3)
        assertNull(grid.marginAt(0.0, 10_500.0, lossOnly))
        val half = -(grid.loss(0, 4) + grid.loss(0, 5)) / 2.0
        assertEquals(half, grid.marginAt(0.0, 5_500.0, lossOnly)!!, 1e-3)
    }

    @Test
    fun `a beam only changes the margin, toward where it points`() {
        val request = CoverageRequest(louisville, 10.0, 1.5, 146.0, radiusM = 20_000.0, stepM = 1_000.0, radials = 36)
        val grid = CoverageEngine(Terrain.FLAT, freeSpace).compute(request)
        val beam = handheld.copy(antennaGainDbi = 9.0, pattern = AntennaPattern.Beam(aimDeg = 90.0, beamwidthDeg = 50.0))
        val margins = MarginFunction.of(Direction.TALK, beam, handheld)
        val east = grid.marginAt(10_000.0, 0.0, margins)!!
        val west = grid.marginAt(-10_000.0, 0.0, margins)!!
        assertEquals(15.0, east - west, 1e-6)
    }

    @Test
    fun `nothing is drawn for radials not computed yet`() {
        val request = CoverageRequest(louisville, 1.5, 1.5, 146.0, radiusM = 10_000.0, stepM = 1_000.0, radials = 8)
        val engine = CoverageEngine(Terrain.FLAT, freeSpace)
        val grid = engine.newGrid(request)
        engine.computeRadial(request, grid, 0)
        assertEquals(1f / 8, grid.completeness(), 1e-6f)
        assertNull(grid.marginAt(0.0, 3_000.0, talk)) // needs radial 7 as well as radial 0
        val green = 0xFF00FF00.toInt()
        assertTrue(grid.rasterize(16, 16, talk) { green }.all { it == 0 })
        engine.computeRadial(request, grid, 7)
        assertTrue(grid.rasterize(16, 16, talk) { green }.any { it != 0 })
    }

    @Test
    fun `a ridge between two stations blocks the line of sight`() {
        val a = louisville
        val b = Earth.destination(a, 90.0, 20_000.0)
        val ridge = Terrain { _, lon -> if (lon in -85.65..-85.63) 300.0 else 150.0 }
        val check = PathCheck.of(ridge, freeSpace, a, handheld, b, handheld, 146.0, stepM = 100.0)
        assertEquals(Clearance.BLOCKED, check.geometry.clearance)
        assertTrue(check.geometry.worstIntrusionM > 100)
        // 3 km over flat ground with 30 m masts: the Fresnel zone (39 m at mid path) is 77% clear.
        val near = Earth.destination(a, 90.0, 3_000.0)
        val flat = PathCheck.of(Terrain { _, _ -> 150.0 }, freeSpace, a, handheld.copy(heightM = 30.0), near, handheld.copy(heightM = 30.0), 146.0, 100.0)
        assertEquals(Clearance.CLEAR, flat.geometry.clearance)
        // 20 km apart the bulge and the wider zone leave the line of sight clear but grazing.
        val far = PathCheck.of(Terrain { _, _ -> 150.0 }, freeSpace, a, handheld.copy(heightM = 30.0), b, handheld.copy(heightM = 30.0), 146.0, 100.0)
        assertEquals(Clearance.GRAZING, far.geometry.clearance)
    }

    @Test
    fun `radius grows with antenna height and stays within limits`() {
        val low = CoverageRequest.radiusFor(1.5, 1.5)
        val high = CoverageRequest.radiusFor(20.0, 30.0)
        assertTrue(high > low)
        assertTrue(low >= 30_000.0 && high <= 150_000.0)
    }

    @Test
    fun `with Longley-Rice a ridge costs signal that flat ground does not`() {
        val a = louisville
        val b = Earth.destination(a, 90.0, 20_000.0)
        val flat = PathCheck.of(Terrain { _, _ -> 150.0 }, ItmPathLoss, a, handheld, b, handheld, 146.0, stepM = 100.0)
        val ridge = PathCheck.of(Terrain { _, lon -> if (lon in -85.65..-85.63) 400.0 else 150.0 }, ItmPathLoss, a, handheld, b, handheld, 146.0, 100.0)
        assertTrue(flat.pathLoss.lossDb > FreeSpace.lossDb(20_000.0, 146.0))
        assertTrue(ridge.pathLoss.lossDb > flat.pathLoss.lossDb + 10)
        assertEquals(com.vyntric.offset.propagation.itm.PropagationMode.DIFFRACTION, ridge.pathLoss.mode)
    }

    @Test
    fun `a place on top of the station is a short free space path, not a crash`() {
        val check = PathCheck.of(Terrain.FLAT, ItmPathLoss, louisville, handheld, louisville, handheld, 146.0, 100.0)
        assertTrue(check.talk.marginDb > 50)
        assertTrue(check.geometry.worstFresnelRatio.isNaN().not())
    }

    @Test
    fun `a Longley-Rice coverage sweep over real-looking hills finishes with every sample set`() {
        val hills = Terrain { lat, lon -> 200.0 + 80.0 * kotlin.math.sin(lat * 400) * kotlin.math.cos(lon * 300) }
        val request = CoverageRequest(louisville, 10.0, 1.5, 146.0, radiusM = 30_000.0, stepM = 200.0, radials = 36)
        val grid = CoverageEngine(hills, ItmPathLoss).compute(request)
        assertEquals(1f, grid.completeness(), 0f)
        assertTrue(grid.lossesDb.all { it > 40f && it < 250f })
    }

    @Test
    fun `reach is the furthest sample still over the threshold`() {
        val request = CoverageRequest(louisville, 1.5, 1.5, 146.0, radiusM = 40_000.0, stepM = 1_000.0, radials = 8)
        val grid = CoverageEngine(Terrain.FLAT, freeSpace).compute(request)
        // Free space margin 37 - L(d) + 121 falls to 0 dB where L = 158 dB: far beyond 40 km, so the whole grid.
        assertEquals(40_000.0, grid.reachM(talk), 1e-9)
        // A threshold of 80 dB is met only out to where L(d) <= 78 dB: 1 km at 146 MHz (75.7 dB), not 2 km (81.7 dB).
        assertEquals(1_000.0, grid.reachM(talk, 80.0), 1e-9)
    }

    @Test
    fun `a long path the model rejects is no link, not free space`() {
        // A frequency outside ITM's 20 MHz to 20 GHz range makes it reject any path.
        val long = TerrainProfile(DoubleArray(101) { 100.0 }, 500.0).toItm()
        val rejected = ItmPathLoss.loss(long, 10.0, 10.0, 5.0)
        assertTrue(!rejected.modelled)
        assertTrue(LinkBudget.of(Direction.TALK, handheld, handheld, 0.0, rejected.lossDb).verdict == Verdict.UNLIKELY)
        val short = TerrainProfile(DoubleArray(11) { 100.0 }, 50.0).toItm()
        val near = ItmPathLoss.loss(short, 10.0, 10.0, 5.0)
        assertTrue(near.modelled)
        assertEquals(FreeSpace.lossDb(500.0, 5.0), near.lossDb, 1e-9)
    }
}
