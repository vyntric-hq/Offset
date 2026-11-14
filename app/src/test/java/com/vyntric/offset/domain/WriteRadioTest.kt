package com.vyntric.offset.domain

import com.vyntric.offset.AppFixtures
import com.vyntric.offset.MemoryLibrary
import com.vyntric.offset.SimLink
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.ImageRegions
import com.vyntric.offset.protocol.Uv5rImage
import com.vyntric.offset.protocol.Uv5rDriver
import com.vyntric.offset.protocol.SimulatedRadio
import com.vyntric.offset.protocol.Sleeper
import com.vyntric.offset.protocol.Squelch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteRadioTest {
    private val factory = AppFixtures.factoryImage()
    private val radio = SimulatedRadio.fromImage(factory)
    private val link = SimLink(radio)
    private val library = MemoryLibrary()
    private val session = RadioSession()
    private val write = WriteRadio(link, library, session, fixedClock, Sleeper.NONE)

    private val channels = listOf(
        Channel(146_520_000, name = "CALL"),
        Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW"),
        Channel(444_000_000, Duplex.PLUS, 5_000_000, Squelch.Dcs(23), Squelch.Dcs(23), name = "UHF"),
    )

    private fun request(content: WriteRadio.Content = WriteRadio.Content.Channels(channels), ident: String? = factory.identHex) =
        WriteRadio.Request(content, "Trip", ident, Uv5rDriver)

    @Test fun `writes, verifies, backs up first and saves the result`() = runTest {
        val outcome = write(request()) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        outcome as WriteRadio.Outcome.Verified
        // Rule 4: the backup is exactly what the radio held before the write.
        assertEquals(factory, library.items[outcome.backupImageId]!!.image)
        assertEquals(ImageKind.BACKUP, library.items[outcome.backupImageId]!!.kind)
        // The saved image is what the radio holds now.
        val now = radio.currentImage()
        assertEquals(now, library.items[outcome.savedImageId]!!.image)
        assertEquals(channels + List(Uv5rImage.CHANNEL_COUNT - 3) { null }, now.channels())
        // Rule 3: settings, calibration and aux are untouched.
        assertTrue(ImageRegions.differsOnlyInWritableRegions(factory, now))
        // Rule 1 and rule 5: read, write, read back.
        assertEquals(3, link.sessions)
    }

    @Test fun `rule 2 - a different radio than the one confirmed blocks before writing`() = runTest {
        val outcome = write(request(ident = "AA000000000000DD")) {}
        assertTrue(outcome is WriteRadio.Outcome.Blocked)
        assertEquals(0, radio.blocksWritten)
        assertTrue(library.items.isEmpty())
    }

    @Test fun `rule 2 - restoring an image from another radio is blocked`() = runTest {
        val foreign = Uv5rImage.of(AppFixtures.bytes("fixtures/baoclone-uv5r-sunnyvale.img"))
        val outcome = write(request(WriteRadio.Content.FromImage(foreign))) {}
        assertTrue(outcome is WriteRadio.Outcome.Blocked)
        assertTrue((outcome as WriteRadio.Outcome.Blocked).reasons.first().contains("different radio"))
        assertEquals(0, radio.blocksWritten)
    }

    @Test fun `restore copies only channel and name memory from the stored image`() = runTest {
        val stored = factory.withChannels(channels)
        // Pretend the stored image also carries different settings bytes; they must not be written.
        val tampered = stored.bytes().also { it[0x0E08 + 2] = 0x55 }
        val outcome = write(request(WriteRadio.Content.FromImage(Uv5rImage.of(tampered)))) {}
        assertTrue(outcome is WriteRadio.Outcome.Verified)
        assertEquals(stored, radio.currentImage())
    }

    @Test fun `restoring an image with an out of band channel is blocked`() = runTest {
        val bad = factory.withChannel(3, Channel(225_000_000, name = "220"))
        val outcome = write(request(WriteRadio.Content.FromImage(bad))) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Blocked)
        assertTrue((outcome as WriteRadio.Outcome.Blocked).reasons.first().startsWith("Channel 4"))
        assertEquals(0, radio.blocksWritten)
    }

    @Test fun `the radio's own image always restores`() = runTest {
        assertTrue(write(request(WriteRadio.Content.FromImage(factory))) {} is WriteRadio.Outcome.Verified)
    }

    @Test fun `invalid channels block the write with reasons`() = runTest {
        val outcome = write(request(WriteRadio.Content.Channels(listOf(Channel(225_000_000))))) {}
        assertTrue(outcome is WriteRadio.Outcome.Blocked)
        assertEquals(WriteRadio.Stage.CHECK, (outcome as WriteRadio.Outcome.Blocked).stage)
        assertEquals(0, radio.blocksWritten)
    }

    @Test fun `failure to save the backup blocks the write`() = runTest {
        library.failSaves = true
        val outcome = write(request()) {}
        assertEquals(WriteRadio.Stage.BACKUP, (outcome as WriteRadio.Outcome.Blocked).stage)
        assertEquals(0, radio.blocksWritten)
    }

    @Test fun `rule 7 - cable pulled mid write is reported as possibly partial with the backup`() = runTest {
        link.beforeSession = { n -> if (n == 1) radio.detachAfterHostBytes = radio.hostBytesReceived + 24 + 20 * 2 }
        val many = List(40) { Channel(146_000_000L + it * 15_000L, name = "C$it") }
        val outcome = write(request(WriteRadio.Content.Channels(many))) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Failed)
        outcome as WriteRadio.Outcome.Failed
        assertTrue(outcome.radioMayBePartial)
        assertEquals(WriteRadio.Stage.WRITE, outcome.stage)
        assertEquals(factory, library.items[outcome.backupImageId]!!.image)
    }

    @Test fun `rule 7 - unplugged before reading reports and writes nothing`() = runTest {
        link.unplugged = true
        val outcome = write(request()) {}
        assertEquals(WriteRadio.Stage.READ, (outcome as WriteRadio.Outcome.Blocked).stage)
    }

    @Test fun `rule 5 - read back mismatch is reported per channel`() = runTest {
        radio.corruptWriteAddresses = setOf(Uv5rImage.radioAddress(Uv5rImage.channelOffset(1)))
        val outcome = write(request()) {}
        assertEquals(listOf(2), (outcome as WriteRadio.Outcome.Mismatch).channels)
    }

    @Test fun `radio swapped between read and write is caught by the protocol guard`() = runTest {
        val other = SimulatedRadio.fromImage(Uv5rImage.of(AppFixtures.bytes("fixtures/baoclone-uv5r-sunnyvale.img")))
        link.beforeSession = { n -> if (n == 1) link.radio = other }
        val outcome = write(request()) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Failed)
        assertTrue(!(outcome as WriteRadio.Outcome.Failed).radioMayBePartial)
        assertEquals(0, other.blocksWritten)
    }

    @Test fun `a second radio of the same model connected after the read is refused`() = runTest {
        val sameModel = SimulatedRadio.fromImage(factory.withChannel(20, Channel(147_000_000, name = "OTHER")))
        link.beforeSession = { n -> if (n == 1) link.radio = sameModel }
        val outcome = write(request()) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Failed)
        assertTrue(!(outcome as WriteRadio.Outcome.Failed).radioMayBePartial)
        assertEquals(0, sameModel.blocksWritten)
    }

    @Test fun `a storage failure after a verified write still reports the verified write`() = runTest {
        library.failSaveNumber = 2 // 1 is the backup, 2 is the result
        val outcome = write(request()) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        outcome as WriteRadio.Outcome.Verified
        assertEquals(null, outcome.savedImageId)
        assertTrue(outcome.saveError!!.contains("could not be saved"))
        assertEquals(channels, radio.currentImage().channels().take(3))
    }

    @Test fun `reading a radio with gaps and writing it straight back keeps every channel number`() = runTest {
        // The factory image uses channels 1 and 128 with everything between empty.
        val read = ReadRadio(link, library, session, fixedClock, Sleeper.NONE) { Uv5rDriver }.invoke {}
        var k = 0L
        val staging = BuildStaging { ++k }.fromImage(read.image)
        assertEquals(listOf(0, 127), staging.map { it.slot })
        val outcome = write(request(WriteRadio.Content.Channels(staging.toSlots(Uv5rImage.CHANNEL_COUNT)))) {}
        assertEquals(0, (outcome as WriteRadio.Outcome.Verified).blocksWritten)
        assertEquals(factory.channels(), radio.currentImage().channels())
    }

    @Test fun `only changed channels are validated, so a radio's own odd channel writes back`() = runTest {
        // A channel the radio holds that Offset would not create (outside the UV-5R's bands).
        val odd = factory.withChannel(5, Channel(225_000_000, name = "220"))
        val r = SimulatedRadio.fromImage(odd)
        link.radio = r
        val slots = odd.channels().toMutableList().also { it[1] = Channel(146_520_000, name = "CALL") }
        val outcome = write(WriteRadio.Request(WriteRadio.Content.Channels(slots), "Trip", odd.identity, Uv5rDriver)) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        assertEquals(Channel(225_000_000, name = "220"), r.currentImage().channel(5))
        // The same odd channel in a slot where it is new is checked and refused.
        val moved = slots.toMutableList().also { it[6] = it[5] }
        val blocked = write(WriteRadio.Request(WriteRadio.Content.Channels(moved), "Trip", odd.identity, Uv5rDriver)) {}
        assertTrue(blocked.toString(), blocked is WriteRadio.Outcome.Blocked)
        assertTrue((blocked as WriteRadio.Outcome.Blocked).reasons.single().startsWith("Channel 7"))
    }

    @Test fun `every session talks to the request's radio type`() = runTest {
        write(request()) {}
        assertEquals(Uv5rDriver, link.lastDriver)
    }

    @Test fun `staging more channels than the radio holds is blocked`() = runTest {
        val outcome = write(request(WriteRadio.Content.Channels(List(129) { null }))) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Blocked)
        assertTrue((outcome as WriteRadio.Outcome.Blocked).reasons.single().contains("128 channels"))
    }

    @Test fun `writing the same channels twice is idempotent`() = runTest {
        write(request()) {}
        val after1 = radio.memorySnapshot()
        val blocksBefore = radio.blocksWritten
        val outcome = write(request()) {}
        assertEquals(0, (outcome as WriteRadio.Outcome.Verified).blocksWritten)
        assertEquals(blocksBefore, radio.blocksWritten)
        assertArrayEquals(after1, radio.memorySnapshot())
    }
}

class ReadRadioTest {
    @Test fun `first read saves a factory image, later reads do not`() = runTest {
        val library = MemoryLibrary()
        val radio = SimulatedRadio.fromImage(AppFixtures.factoryImage())
        val read = ReadRadio(SimLink(radio), library, RadioSession(), fixedClock, Sleeper.NONE) { Uv5rDriver }
        val first = read {}
        assertEquals("Factory read UV-5R BFB291 2026-11-13", first.factoryImageName)
        assertEquals(ImageKind.FACTORY, library.items[first.factoryImageId]!!.kind)
        val second = read {}
        assertEquals(null, second.factoryImageId)
        assertEquals(1, library.items.size)
    }
}
