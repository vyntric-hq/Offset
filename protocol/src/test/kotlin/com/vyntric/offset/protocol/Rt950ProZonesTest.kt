package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** RT-950 Pro zone names at radio 0xC000 (firmware 0.24 and later), against the simulated radio. */
class Rt950ProZonesTest {
    private val sample = Rt950ProDriver.sampleImage()
    private val defaults = listOf("ZoneOne", "ZoneTwo", "ZoneThree", "ZoneFour", "ZoneFive", "ZoneSix", "ZoneSeven", "ZoneEight", "ZoneNine", "ZoneTen")

    private fun clone(sim: SerialPort) = Rt950ProDriver.clone(sim, Sleeper.NONE, {}, Random(1))

    private fun names(vararg changes: Pair<Int, String>) = defaults.toMutableList().also { l -> changes.forEach { (z, n) -> l[z] = n } }

    @Test fun `the radio's zone names are read as ten namedZones of 99`() {
        val namedZones = sample.namedZones!!
        assertEquals(defaults, namedZones.names)
        assertEquals(99, namedZones.size)
        assertEquals(1, namedZones.zoneOf(99))
        assertEquals(891 until 960, namedZones.slots(9, sample.capacity))
    }

    @Test fun `renaming keeps other namedZones byte for byte and pads like the slot`() {
        val renamed = sample.withZoneNames(names(0 to "Repeaters", 2 to "CB  ", 9 to "A very long zone name"))
        assertEquals(names(0 to "Repeaters", 2 to "CB", 9 to "A very long"), renamed.namedZones!!.names)
        val a = sample.bytes(); val b = renamed.bytes()
        val zone2 = Rt950ProImage.ZONE_NAMES.first + 16
        assertTrue((zone2 until zone2 + 16).all { a[it] == b[it] })
        assertEquals(0xFF.toByte(), b[Rt950ProImage.ZONE_NAMES.first + 9])
        assertTrue(sample.differsOnlyInWritableRegions(renamed))
        assertEquals(sample, sample.withZoneNames(defaults))
    }

    @Test fun `a blank name or the wrong count is refused`() {
        for (bad in listOf(names(1 to "   "), defaults.take(9))) {
            try {
                sample.withZoneNames(bad); fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun `a zone the radio holds blank can stay blank while another is renamed`() {
        val oneBlank = Rt950ProImage.of(sample.bytes().also { it.fill(0xFF.toByte(), Rt950ProImage.ZONE_NAMES.first + 16, Rt950ProImage.ZONE_NAMES.first + 32) })
        assertEquals("", oneBlank.namedZones!!.names[1])
        val renamed = oneBlank.withZoneNames(oneBlank.namedZones!!.names.toMutableList().also { it[0] = "Repeaters" })
        assertEquals(listOf("Repeaters", ""), renamed.namedZones!!.names.take(2))
    }

    @Test fun `an unedited name longer than 12 characters is kept byte for byte`() {
        val long = Rt950ProImage.of(sample.bytes().also { b -> "FOURTEEN CHARS".forEachIndexed { i, c -> b[Rt950ProImage.ZONE_NAMES.first + i] = c.code.toByte() } })
        assertEquals("FOURTEEN CHARS", long.namedZones!!.names[0])
        assertEquals(long, long.withZoneNames(long.namedZones!!.names))
        val other = long.withZoneNames(long.namedZones!!.names.toMutableList().also { it[1] = "CB" })
        assertEquals("FOURTEEN CHARS", other.namedZones!!.names[0])
    }

    @Test fun `bytes that are not names mean no zones, and their memory is not writable`() {
        val blank = Rt950ProImage.of(sample.bytes().also { it.fill(0xFF.toByte(), Rt950ProImage.ZONE_NAMES.first, Rt950ProImage.ZONE_NAMES.last + 1) })
        assertNull(blank.namedZones)
        val binary = Rt950ProImage.of(sample.bytes().also { it[Rt950ProImage.ZONE_NAMES.first + 2] = 0x05 })
        assertNull(binary.namedZones)
        assertFalse(blank.differsOnlyInWritableRegions(sample))
    }

    @Test fun `an image saved before zone names loads without namedZones and restores channels only`() {
        val legacy = Rt950ProImage.of(sample.bytes().copyOf(Rt950ProImage.LEGACY_SIZE))
        assertTrue(Rt950ProDriver.recognizes(legacy.bytes()))
        assertNull(legacy.namedZones)
        val renamed = sample.withZoneNames(names(0 to "Hike"))
        val restored = renamed.withWritableRegionsOf(legacy.withChannels(listOf(Channel(146_520_000, name = "CALL"))))
        assertEquals("Hike", restored.namedZones!!.names[0])
        assertEquals("CALL", restored.channel(0)!!.name)
        // A legacy image is never a write target for a radio read with the 0xC000 segment.
        assertFalse(sample.differsOnlyInWritableRegions(legacy))
        // The simulator still takes it.
        assertEquals(legacy.channels(), Rt950ProDriver.simulator(legacy).currentImage().channels())
    }

    @Test fun `a write sends the changed zone block after the channels, and only when names changed`() {
        val sim = Rt950ProDriver.simulator(sample) as Rt950ProSimulator
        val before = clone(sim).download() as Rt950ProImage
        val channelsOnly = before.withChannel(9, Channel(147_000_000, name = "New"))
        assertEquals(240, clone(sim).upload(channelsOnly, before))
        val before2 = clone(sim).download() as Rt950ProImage
        val renamed = before2.withZoneNames(names(0 to "Repeaters", 3 to "CB"))
        assertEquals(241, clone(sim).upload(renamed, before2))
        val after = clone(sim).download()
        assertEquals(renamed, after)
        assertEquals(names(0 to "Repeaters", 3 to "CB"), after.namedZones!!.names)
        // Zones 9 and 10 sit in the second block.
        val before3 = after as Rt950ProImage
        assertEquals(241, clone(sim).upload(before3.withZoneNames(names(0 to "Repeaters", 3 to "CB", 9 to "Marine")), before3))
    }

    @Test fun `zone names are not written to a radio whose read shows none`() {
        val blank = Rt950ProImage.of(sample.bytes().also { it.fill(0xFF.toByte(), Rt950ProImage.ZONE_NAMES.first, Rt950ProImage.ZONE_NAMES.last + 1) })
        val sim = Rt950ProDriver.simulator(blank) as Rt950ProSimulator
        val before = clone(sim).download() as Rt950ProImage
        try {
            clone(sim).upload(sample, before); fail()
        } catch (_: RadioException.Unsupported) {
        }
        assertEquals(0, sim.blocksWritten)
    }
}
