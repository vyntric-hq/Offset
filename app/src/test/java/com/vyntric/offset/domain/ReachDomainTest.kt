/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.domain.reach.Catalog
import com.vyntric.offset.domain.reach.Feedline
import com.vyntric.offset.domain.reach.PlaceText
import com.vyntric.offset.domain.reach.ReachBand
import com.vyntric.offset.domain.reach.Station
import com.vyntric.offset.domain.reach.StationSetup
import com.vyntric.offset.propagation.AntennaPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachDomainTest {
    @Test
    fun `catalog antennas have unique keys and no handheld whip beats a dipole`() {
        val keys = Catalog.antennas.map { it.catalogKey }
        assertEquals(keys.size, keys.toSet().size)
        // Makers advertise 3.5 dBi and more for handheld whips; physics allows no more than a
        // half-wave dipole's 2.15 dBi, and the catalog must not pass the claim on.
        Catalog.antennas.filter { it.kind == com.vyntric.offset.domain.reach.AntennaKind.HANDHELD_WHIP }
            .forEach { a -> a.gainDbi.forEach { (band, g) -> assertTrue("${a.name} on $band: $g dBi", g <= 2.15) } }
        // The ZBM2, Practical Comms and N9TAX lines are all there, each fitting a radio on its bands.
        val ht = Catalog.radios.first { it.catalogKey == "uv5r" }
        val gmrs = Catalog.radios.first { it.catalogKey == "gmrs-ht" }
        val thd75 = Catalog.radios.first { it.catalogKey == "thd75" }
        assertTrue(Catalog.antennasFor(ht).any { it.catalogKey == "n9tax-dual-16" })
        assertTrue(Catalog.antennasFor(ht).any { it.catalogKey == "zbm2-fww-2m70" })
        assertTrue(Catalog.antennasFor(gmrs).any { it.catalogKey == "n9tax-gmrs-10" })
        assertTrue(Catalog.antennasFor(gmrs).any { it.catalogKey == "pc-jungle-gmrs" })
        assertTrue(Catalog.antennasFor(thd75).any { it.catalogKey == "pc-jungle-220" })
        assertTrue(Catalog.antennasFor(thd75).any { it.catalogKey == "zbm2-fww-125-70" })
        // A slim jim sold with 16 ft of cable brings that cable with it; one without brings none.
        val withCable = Catalog.antennas.first { it.catalogKey == "n9tax-dual-16" }
        assertEquals(Feedline.RG58, Catalog.integratedCable(withCable)!!.first)
        assertEquals(16 * 0.3048, Catalog.integratedCable(withCable)!!.second, 1e-9)
        assertNull(Catalog.integratedCable(Catalog.antennas.first { it.catalogKey == "n9tax-dual" }))
    }

    @Test
    fun `team locations pasted from a chat become named places`() {
        val text = """
            Alex: 38.2527, -85.7585
            Sam 38.30N 85.70W
            Jo https://maps.google.com/?q=38.21,-85.81
            Pat @ EM78hf
            geo:38.4,-85.6
            see you at 10 20

        """.trimIndent()
        val places = PlaceText.parse(text)
        assertEquals(5, places.size)
        assertEquals("Alex", places[0].name)
        assertEquals(38.2527, places[0].point.latitude, 1e-9)
        assertEquals(-85.7585, places[0].point.longitude, 1e-9)
        assertEquals("Sam", places[1].name)
        assertEquals(-85.70, places[1].point.longitude, 1e-9)
        assertEquals("Jo", places[2].name)
        assertEquals(38.21, places[2].point.latitude, 1e-9)
        assertEquals("Pat", places[3].name)
        assertNull(places[4].name)
        assertEquals(38.4, places[4].point.latitude, 1e-9)
    }

    @Test
    fun `a map share link with an at sign gives its coordinates`() {
        val p = PlaceText.parseLine("https://www.google.com/maps/@38.2542,-85.7594,15z")!!
        assertEquals(38.2542, p.point.latitude, 1e-9)
        assertNull(p.name)
    }

    @Test
    fun `bands come from frequencies, GMRS before 70 cm`() {
        assertEquals(ReachBand.TWO_METER, ReachBand.of(146_940_000))
        assertEquals(ReachBand.SEVENTY_CM, ReachBand.of(444_100_000))
        assertEquals(ReachBand.GMRS, ReachBand.of(462_550_000))
        assertEquals(ReachBand.SIX_METER, ReachBand.of(53_010_000))
        assertNull(ReachBand.of(28_400_000))
    }

    @Test
    fun `feedline loss grows with length and frequency`() {
        assertEquals(4.7, Feedline.RG8X.lossDb(30.48, 144.0), 1e-9)
        assertEquals(8.6, Feedline.RG8X.lossDb(30.48, 440.0), 1e-9)
        assertTrue(Feedline.RG8X.lossDb(10.0, 446.0) > Feedline.RG8X.lossDb(10.0, 146.0))
        assertTrue(Feedline.LMR400.lossDb(10.0, 146.0) < Feedline.RG58.lossDb(10.0, 146.0))
    }

    @Test
    fun `a station turns into RF numbers for the band`() {
        val radio = Catalog.radios.first { it.catalogKey == "uv5r" }.copy(id = 1)
        val antenna = Catalog.antennas.first { it.catalogKey == "stock-duck" }.copy(id = 2)
        val station = Station(id = 3, name = "Hand", radioId = 1, antennaId = 2, watts = 10.0, heightM = 1.5)
        val setup = StationSetup(station, radio, antenna)
        assertEquals(listOf(ReachBand.TWO_METER, ReachBand.SEVENTY_CM), setup.bands)
        val rf = setup.rf(ReachBand.TWO_METER)
        // 10 W asked, but the UV-5R makes 4 W: 36 dBm.
        assertEquals(36.02, rf.txPowerDbm, 0.01)
        assertEquals(-4.0, rf.antennaGainDbi, 1e-9)
        assertEquals(0.0, rf.lineLossDb, 1e-9)
        assertEquals(AntennaPattern.Omni, rf.pattern)
    }

    @Test
    fun `a beam with a bearing points that way, GMRS uses the 70 cm gain`() {
        val radio = Catalog.radios.first { it.catalogKey == "mobile50" }.copy(id = 1)
        val yagi = Catalog.antennas.first { it.catalogKey == "yagi5-70" }.copy(id = 2)
        val station = Station(id = 3, name = "Beam", radioId = 1, antennaId = 2, watts = 50.0, heightM = 10.0,
            feedline = Feedline.LMR400, feedlineM = 15.0, bearingDeg = 45.0)
        val rf = StationSetup(station, radio, yagi).rf(ReachBand.SEVENTY_CM)
        val beam = rf.pattern as AntennaPattern.Beam
        assertEquals(45.0, beam.aimDeg, 1e-9)
        assertTrue(rf.lineLossDb > 1.0 && rf.lineLossDb < 2.0)
        assertEquals(9.0, yagi.gain(ReachBand.GMRS)!!, 1e-9)
    }

    @Test
    fun `a place beyond the mapped area is checked over its own path terrain, not sea level`() {
        val origin = com.vyntric.offset.propagation.GeoPoint(38.25, -85.76)
        // 15 km east, a base station; on flat ground this path is open (grazing at worst).
        val farPoint = com.vyntric.offset.propagation.Earth.destination(origin, 90.0, 15_000.0)
        val place = com.vyntric.offset.domain.reach.Place(id = 7, name = "Far", latitude = farPoint.latitude, longitude = farPoint.longitude,
            far = com.vyntric.offset.domain.reach.FarStation.BASE)
        val mast = com.vyntric.offset.domain.reach.FarStation.BASE.rf().copy(heightM = 30.0)
        val freeSpace = com.vyntric.offset.propagation.PathLossModel { p, _, _, f ->
            com.vyntric.offset.propagation.PathLoss(com.vyntric.offset.propagation.FreeSpace.lossDb(p[0] * p[1], f), com.vyntric.offset.propagation.itm.PropagationMode.LINE_OF_SIGHT)
        }
        // The mapped area's terrain is missing out there (reads as sea level); the path terrain has a ridge.
        val mapped = com.vyntric.offset.propagation.Terrain.FLAT
        val path = com.vyntric.offset.propagation.Terrain { _, lon -> if (lon in -85.70..-85.65) 400.0 else 0.0 }
        val targets = com.vyntric.offset.domain.reach.ReachTargets.check(
            mapped, freeSpace, origin, mast, ReachBand.TWO_METER, emptyList(), listOf(place),
            com.vyntric.offset.domain.reach.RepeaterAssumptions(), stepM = 500.0,
        ) { if (it.id == 7L) path else mapped }
        assertEquals(com.vyntric.offset.propagation.Clearance.BLOCKED, targets.single().check.geometry.clearance)
    }

    private val freeSpaceModel = com.vyntric.offset.propagation.PathLossModel { p, _, _, f ->
        com.vyntric.offset.propagation.PathLoss(com.vyntric.offset.propagation.FreeSpace.lossDb(p[0] * p[1], f), com.vyntric.offset.propagation.itm.PropagationMode.LINE_OF_SIGHT)
    }

    private fun placeAt(id: Long, km: Double) = com.vyntric.offset.propagation.Earth.destination(com.vyntric.offset.propagation.GeoPoint(38.25, -85.76), 90.0, km * 1000)
        .let { com.vyntric.offset.domain.reach.Place(id = id, name = "P$id", latitude = it.latitude, longitude = it.longitude) }

    @Test
    fun `near targets come first, far places load their own terrain a few at a time, and gaps are flagged`() = kotlinx.coroutines.runBlocking {
        val origin = com.vyntric.offset.propagation.GeoPoint(38.25, -85.76)
        val me = com.vyntric.offset.domain.reach.FarStation.BASE.rf()
        val places = listOf(placeAt(1, 10.0)) + (2L..25L).map { placeAt(it, 60.0 + it) } + listOf(placeAt(99, 400.0))
        var nearSeen: List<com.vyntric.offset.domain.reach.ReachTarget>? = null
        val active = java.util.concurrent.atomic.AtomicInteger()
        var peak = 0
        var loads = 0
        val all = com.vyntric.offset.domain.reach.ReachTargets.checkAll(
            com.vyntric.offset.propagation.Terrain.FLAT, 50_000.0, freeSpaceModel, origin, me, ReachBand.TWO_METER, emptyList(), places,
            com.vyntric.offset.domain.reach.RepeaterAssumptions(), 1_000.0,
            loadTerrain = { _, _ ->
                peak = maxOf(peak, active.incrementAndGet())
                val n = ++loads // every other load comes back incomplete
                kotlinx.coroutines.delay(5)
                active.decrementAndGet()
                com.vyntric.offset.domain.reach.ReachTargets.PathTerrain(com.vyntric.offset.propagation.Terrain.FLAT, complete = n % 2 == 0)
            },
            onNear = { nearSeen = it },
        )
        // Inside the map (P1), past the cap (the 4 furthest of the 24 far ones) and too far (P99) are checked before any download.
        assertEquals(setOf(1L, 99L) + (22L..25L), nearSeen!!.map { it.place!!.id }.toSet())
        assertTrue(nearSeen!!.single { it.place!!.id == 99L }.terrainNote!!.startsWith("Too far"))
        assertEquals(com.vyntric.offset.domain.reach.ReachTargets.MAX_FAR_PLACES, loads)
        assertTrue(peak <= com.vyntric.offset.domain.reach.ReachTargets.FAR_PLACE_LOADS)
        assertEquals(places.size, all.size)
        assertTrue(all.any { it.terrainNote?.startsWith("Some terrain") == true })
        assertNull(all.single { it.place!!.id == 1L }.terrainNote)
    }

    @Test
    fun `the repeater search is filtered to the band before its limit, and skipped where directories have none`() {
        val here = com.vyntric.offset.propagation.GeoPoint(38.25, -85.76)
        val q = com.vyntric.offset.domain.reach.ReachTargets.repeaterQuery(here, 80_000.0, ReachBand.SEVENTY_CM) as com.vyntric.offset.domain.RepeaterQuery.Near
        assertEquals(setOf(com.vyntric.offset.protocol.Band.SEVENTY_CM), q.filters.bands)
        assertEquals(com.vyntric.offset.domain.reach.ReachTargets.MAX_REPEATERS, q.limit)
        assertEquals(80.0, q.radiusKm!!, 1e-9)
        val gmrs = com.vyntric.offset.domain.reach.ReachTargets.repeaterQuery(here, 80_000.0, ReachBand.GMRS) as com.vyntric.offset.domain.RepeaterQuery.Near
        assertEquals(setOf(com.vyntric.offset.domain.Service.GMRS), gmrs.filters.services)
        assertNull(com.vyntric.offset.domain.reach.ReachTargets.repeaterQuery(here, 80_000.0, ReachBand.SIX_METER))
    }
}
