package com.vyntric.offset.domain

import com.vyntric.offset.MemoryLibrary
import com.vyntric.offset.SimLink
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Rt950ProDriver
import com.vyntric.offset.protocol.Rt950ProImage
import com.vyntric.offset.protocol.Rt950ProSimulator
import com.vyntric.offset.protocol.Sleeper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RT-950 Pro zones through staging, naming and the write. */
class ZonesTest {
    private val sample = Rt950ProDriver.sampleImage()
    private var key = 0L
    private val build = BuildStaging { ++key }
    private val zones = StagedZones.of(sample)!!
    private val target = StagingTarget.of(sample).copy(zones = zones)
    private fun ch(mhz: Double, name: String = "") = Channel((mhz * 1_000_000).toLong(), name = name)

    @Test fun `new channels go to the chosen zone, and only it`() {
        val toCb = target.copy(zones = zones.copy(addTo = 2))
        val r = build.add(emptyList(), listOf(ch(27.065, "CB9"), ch(27.185, "CB19")), ChannelOrigin.MANUAL, toCb)
        assertEquals(listOf(198, 199), r.staging.map { it.slot })
        // A full zone overflows instead of spilling into the next one.
        val full = (198 until 297).map { StagedChannel(++key, ch(146.0 + it / 1000.0), ChannelOrigin.MANUAL, slot = it) }
        assertEquals(1, build.add(full, listOf(ch(27.2)), ChannelOrigin.MANUAL, toCb).overflow)
        // Any zone: the first free slot anywhere.
        assertEquals(0, build.add(r.staging, listOf(ch(146.52)), ChannelOrigin.MANUAL, target).staging.first().slot)
    }

    @Test fun `moving between zones moves only that channel`() {
        val list = build.add(emptyList(), listOf(ch(146.52), ch(146.55), ch(146.58)), ChannelOrigin.MANUAL, target).staging
        val moved = build.moveToZone(list, list[0].key, 1, target)!!
        assertEquals(listOf(1, 2, 99), moved.map { it.slot })
        // Dragging onto a channel in another zone does the same; neighbours keep their zones.
        val dragged = build.move(moved, 0, 2, target)
        assertEquals(listOf(2, 99, 100), dragged.map { it.slot })
        assertEquals(list[1].key, dragged.single { it.slot == 100 }.key)
        // A move within a zone permutes that zone's slots only.
        assertEquals(listOf(list[2].key, list[1].key, list[0].key), build.move(dragged, 2, 1, target).map { it.key })
    }

    @Test fun `close gaps packs each zone from its first channel`() {
        val list = listOf(5, 7, 120, 300).map { StagedChannel(++key, ch(146.0 + it / 1000.0), ChannelOrigin.MANUAL, slot = it) }
        assertTrue(build.hasGaps(list, target))
        val packed = build.compact(list, target)
        assertEquals(listOf(0, 1, 99, 297), packed.map { it.slot })
        assertFalse(build.hasGaps(packed, target))
    }

    @Test fun `checked channels move to a zone together, in order, and what does not fit stays`() {
        val list = listOf(0, 1, 2, 120).map { StagedChannel(++key, ch(146.0 + it / 1000.0), ChannelOrigin.MANUAL, slot = it) }
        val r = build.moveAllToZone(list, setOf(list[0].key, list[2].key, list[3].key), 2, target)
        assertEquals(3, r.moved)
        assertEquals(listOf(list[1].key to 1, list[0].key to 198, list[2].key to 199, list[3].key to 200), r.staging.map { it.key to it.slot })
        // Already there: nothing moves.
        assertEquals(0, build.moveAllToZone(r.staging, setOf(list[0].key), 2, target).moved)
        // A nearly full zone takes what fits.
        val fill = (99 until 197).map { StagedChannel(++key, ch(147.0 + it / 1000.0), ChannelOrigin.MANUAL, slot = it) }
        val tight = build.moveAllToZone(list + fill, setOf(list[0].key, list[1].key), 1, target)
        assertEquals(1, tight.moved)
        assertEquals(1, tight.didNotFit)
    }

    @Test fun `a set for a zone radio says why it has no zones`() {
        val noRead = com.vyntric.offset.ui.radio.RadioUiState(radioType = Rt950ProDriver)
        assertEquals("Read the radio", noRead.zonesMissing)
        val binary = Rt950ProImage.of(sample.bytes().also { it[Rt950ProImage.ZONE_NAMES.first + 2] = 0x05 })
        assertEquals("Not found", noRead.copy(snapshot = RadioSession.Snapshot(binary, 0)).zonesMissing)
        assertNull(noRead.copy(zones = zones).zonesMissing)
        assertNull(com.vyntric.offset.ui.radio.RadioUiState().zonesMissing)
    }

    @Test fun `a full zone refuses a move`() {
        val full = (0 until 99).map { StagedChannel(++key, ch(146.0 + it / 1000.0), ChannelOrigin.MANUAL, slot = it) }
        val other = StagedChannel(++key, ch(147.0), ChannelOrigin.MANUAL, slot = 150)
        assertNull(build.moveToZone(full + other, other.key, 0, target))
    }

    @Test fun `a write renames the zones with the channels and verifies them`() = runTest {
        val sim = Rt950ProDriver.simulator(sample) as Rt950ProSimulator
        val link = SimLink(sim)
        val library = MemoryLibrary()
        val session = RadioSession()
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Rt950ProDriver }.invoke {}
        val staged = zones.renamed(0, "Repeaters").renamed(3, "CB")
        val slots = read.image.channels().toMutableList().also { it[99 * 3] = ch(27.185, "CB19") }
        val write = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)
        val outcome = write(WriteRadio.Request(WriteRadio.Content.Channels(slots, staged.names), "Trip", read.image.identity, Rt950ProDriver)) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        assertEquals(241, (outcome as WriteRadio.Outcome.Verified).blocksWritten)
        assertEquals(staged.names, sim.currentImage().namedZones!!.names)
        assertEquals("CB19", sim.currentImage().channel(297)!!.name)
        // The saved result keeps the names; the same set again is not saved twice, a renamed one is.
        val naming = SaveNamedImage(library)
        assertTrue(naming(sim.currentImage(), "Trip").existing)
        assertEquals("Trip (2)", naming(sim.currentImage().withZoneNames(staged.renamed(1, "Marine").names), "Trip").name)
    }

    @Test fun `a radio never given zone names gets them on a write`() = runTest {
        val blank = Rt950ProImage.of(sample.bytes().also { it.fill(0xFF.toByte(), Rt950ProImage.ZONE_NAMES.first, Rt950ProImage.ZONE_NAMES.last + 1) })
        val sim = Rt950ProDriver.simulator(blank) as Rt950ProSimulator
        val link = SimLink(sim)
        val library = MemoryLibrary()
        val session = RadioSession()
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Rt950ProDriver }.invoke {}
        val staged = StagedZones.of(read.image)!!
        assertEquals(List(10) { "" }, staged.names)
        val outcome = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)(
            WriteRadio.Request(WriteRadio.Content.Channels(read.image.channels(), staged.renamed(0, "Repeaters").renamed(1, "CB").names), "Trip", read.image.identity, Rt950ProDriver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        assertEquals(241, (outcome as WriteRadio.Outcome.Verified).blocksWritten)
        assertEquals(listOf("Repeaters", "CB", ""), sim.currentImage().namedZones!!.names.take(3))
    }

    @Test fun `zone names are left out on a radio whose read shows none`() = runTest {
        val binary = Rt950ProImage.of(sample.bytes().also { it[Rt950ProImage.ZONE_NAMES.first + 2] = 0x05 })
        val sim = Rt950ProDriver.simulator(binary) as Rt950ProSimulator
        val link = SimLink(sim)
        val library = MemoryLibrary()
        val session = RadioSession()
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Rt950ProDriver }.invoke {}
        val slots = read.image.channels().toMutableList().also { it[10] = ch(146.52, "CALL") }
        val outcome = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)(
            WriteRadio.Request(WriteRadio.Content.Channels(slots, zones.renamed(0, "Repeaters").names), "Trip", read.image.identity, Rt950ProDriver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        assertEquals(240, (outcome as WriteRadio.Outcome.Verified).blocksWritten)
        assertNull(sim.currentImage().namedZones)
    }
}
