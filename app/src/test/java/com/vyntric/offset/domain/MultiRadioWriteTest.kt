package com.vyntric.offset.domain

import com.vyntric.offset.MemoryLibrary
import com.vyntric.offset.SimLink
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Dm32uvDriver
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Rt950ProDriver
import com.vyntric.offset.protocol.Sleeper
import com.vyntric.offset.protocol.Squelch
import com.vyntric.offset.protocol.Uv5rDriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read, stage, write, verify flow for the radio families beyond the UV-5R, through the real use cases. */
class MultiRadioWriteTest {
    private val repeater = Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW")

    @Test fun `DM-32UV - radio channels keep their numbers and new ones land in free slots`() = runTest {
        val sim = Dm32uvDriver.simulator(Dm32uvDriver.sampleImage())
        val link = SimLink(sim)
        val library = MemoryLibrary()
        val session = RadioSession()
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Dm32uvDriver }.invoke {}
        assertEquals(Dm32uvDriver, link.lastDriver)
        val target = StagingTarget.of(read.image)
        assertTrue(target.fixedSlots)
        var k = 0L
        val build = BuildStaging { ++k }
        val staged = build.fromImage(read.image)
        val withNew = build.add(staged, listOf(repeater), ChannelOrigin.REPEATER, target).staging
        val newSlot = withNew.single { it.channel == repeater }.slot
        // Reordering is refused for this radio, so the existing numbers never change.
        assertEquals(withNew, build.move(withNew, 0, withNew.lastIndex, target))

        val outcome = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)(
            WriteRadio.Request(WriteRadio.Content.Channels(withNew.toSlots(target.capacity)), "Trip", read.image.identity, Dm32uvDriver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        val now = sim.currentImage()
        assertEquals(repeater.rxFreqHz, now.channel(newSlot)?.rxFreqHz)
        staged.forEach { assertEquals(it.channel, now.channel(it.slot)) }
        assertTrue(read.image.differsOnlyInWritableRegions(now))
    }

    @Test fun `RT-950 Pro - an edit is written and verified`() = runTest {
        val sim = Rt950ProDriver.simulator(Rt950ProDriver.sampleImage())
        val link = SimLink(sim)
        val library = MemoryLibrary()
        val session = RadioSession()
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Rt950ProDriver }.invoke {}
        val slots = read.image.channels().toMutableList().also { it[10] = repeater }
        val outcome = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)(
            WriteRadio.Request(WriteRadio.Content.Channels(slots), "Trip", read.image.identity, Rt950ProDriver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        assertEquals(repeater, sim.currentImage().channel(10))
        // The AM airband channel the radio held is written back untouched.
        assertEquals(read.image.channel(5), sim.currentImage().channel(5))
    }

    @Test fun `restoring an image of one family onto another is blocked`() = runTest {
        val sim = Uv5rDriver.simulator(Uv5rDriver.sampleImage())
        val outcome = WriteRadio(SimLink(sim), MemoryLibrary(), RadioSession(), fixedClock, Sleeper.NONE)(
            WriteRadio.Request(WriteRadio.Content.FromImage(Dm32uvDriver.sampleImage()), "x", null, Uv5rDriver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Blocked)
        assertTrue((outcome as WriteRadio.Outcome.Blocked).reasons.single().contains("DM-32UV"))
    }

    @Test fun `CHIRP CSV locations follow each radio's first channel number`() {
        val image = Dm32uvDriver.sampleImage()
        val csv = ExportImage().csv(image)
        assertTrue(csv.lines()[1].startsWith("1,"))
        val (applied, errors) = ImportImage(MemoryLibrary()).applyCsv(image, csv.replace("\r\n1,", "\r\n9,"))
        assertEquals(emptyList<String>(), errors.filterNot { it.contains("DMR") })
        assertEquals(image.channel(0)?.rxFreqHz, applied.channel(8)?.rxFreqHz)
    }
}
