package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The nicFW950 driver through its public interfaces, against the simulated radio. */
class NicFw950DriverTest {
    private val driver: RadioDriver = NicFw950Driver
    private val sample = NicFw950Driver.sampleImage()
    private val record = NicFw950Codec.RECORD_SIZE

    private fun radio(image: NicFw950Image = sample) = driver.simulator(image) as NicFw950Simulator

    private fun clone(port: SerialPort, progress: MutableList<CloneProgress> = mutableListOf(), sleeps: MutableList<Long> = mutableListOf()) =
        NicFw950Clone(port, { sleeps += it }, { progress += it })

    private inline fun <reified T : RadioException> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: RadioException) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    private fun edited(before: NicFw950Image): NicFw950Image = before.withChannels(
        before.channels().toMutableList().also {
            it[0] = it[0]!!.copy(name = "Calling 146.52")
            it[9] = Channel(147_000_000, Duplex.PLUS, 600_000, Squelch.Ctcss(1000), name = "New repeater")
            it[998] = Channel(439_000_000, Duplex.MINUS, 7_600_000, power = Power.LOW)
            it[4] = null
        },
    )

    @Test fun `channel 1 is the fifth record, after the VFO slots, at 0x50200 plus 4 times 62`() {
        assertEquals(4 * 62, NicFw950Image.channelOffset(0))
        assertEquals(1002 * 62, NicFw950Image.channelOffset(998))
        assertEquals(0x50200 + (1 + 3) * 62, NicFw950Image.BASE_ADDRESS + NicFw950Image.channelOffset(0))
        assertTrue(NicFw950Image.WRITABLE.last < NicFw950Image.SIZE)
    }

    @Test fun `sample channels decode as written and VFOs are not channels`() {
        assertEquals("Calling 2m", sample.channel(0)!!.name)
        val rpt = sample.channel(2)!!
        assertEquals(Duplex.PLUS, rpt.duplex)
        assertEquals(5_000_000, rpt.offsetHz)
        assertEquals(Squelch.Ctcss(1273), rpt.rxSquelch)
        assertEquals(Power.MID, rpt.power)
        val pmr = sample.channel(3)!!
        assertEquals(Squelch.Dcs(23), pmr.txSquelch)
        assertEquals(Squelch.Dcs(23, inverted = true), pmr.rxSquelch)
        assertFalse(pmr.wide)
        assertEquals(Power.LOW, pmr.power)
        assertEquals(Duplex.OFF, sample.channel(4)!!.duplex)
        val six = sample.channel(5)!!
        assertEquals(Power.VERY_HIGH, six.power)
        assertEquals("AC", six.options["nicfw.groups"])
        assertTrue(six.busyLockout)
        assertEquals(PttId.BOTH, six.pttId)
        assertNull("a deleted slot with a leftover name is empty", sample.channel(7))
        val hf = sample.channel(6)!!
        assertEquals(Modulation.LSB, hf.mode)
        assertEquals(Duplex.OFF, hf.duplex)
        assertEquals("5", hf.options["nicfw.bandwidth"])
        assertEquals(7, sample.usedChannelCount)
        assertTrue(sample.looksLikeNicFw950)
    }

    @Test fun `subtones use nicFW's flags`() {
        assertEquals(0x8000 or 0x13, NicFw950Codec.encodeSquelch(Squelch.Dcs(23)))
        assertEquals(0xC000 or 0x1EC, NicFw950Codec.encodeSquelch(Squelch.Dcs(754, inverted = true)))
        assertEquals(885, NicFw950Codec.encodeSquelch(Squelch.Ctcss(885)))
        assertEquals(Squelch.Dcs(754, inverted = true), NicFw950Codec.decodeSquelch(0xC1EC))
        assertEquals(Squelch.None, NicFw950Codec.decodeSquelch(0xFFFF))
    }

    @Test fun `an edit keeps the bytes Offset does not model`() {
        val six = NicFw950Image.channelOffset(5)
        val target = sample.withChannels(sample.channels().toMutableList().also { it[5] = it[5]!!.copy(name = "Renamed") })
        val bytes = target.bytes()
        assertEquals("very high power survives", 6, bytes[six + 21].toInt())
        assertEquals("groups survive", 3, bytes[six + 8].toInt())
        assertEquals(1, bytes[six + 9].toInt())
        assertEquals("Renamed", target.channel(5)!!.name)
        assertEquals(listOf(5), sample.mismatchedChannels(target))
        assertTrue(sample.differsOnlyInWritableRegions(target))
    }

    @Test fun `power and transmit off round trip`() {
        val tx = Channel(146_000_000, power = Power.LOW)
        val out = NicFw950Codec.encode(tx)
        assertEquals(3, out[21].toInt())
        val off = NicFw950Codec.encode(tx.copy(duplex = Duplex.OFF), out)
        assertEquals(0, off[21].toInt())
        assertEquals(Duplex.OFF, NicFw950Codec.decode(off)!!.duplex)
        val back = NicFw950Codec.encode(tx.copy(power = Power.MID), off)
        assertEquals(4, back[21].toInt())
        assertEquals(146_000_000L, NicFw950Codec.decode(back)!!.txFreqHz)
    }

    @Test fun `every channel encodes to what decodes back`() {
        val channels = listOf(
            Channel(146_520_000, name = "A".repeat(40)),
            Channel(145_290_005, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), Squelch.Ctcss(1000), power = Power.MID, scanAdd = false),
            Channel(446_006_250, txSquelch = Squelch.Dcs(754, true), rxSquelch = Squelch.Dcs(25), wide = false),
            Channel(52_525_000, Duplex.SPLIT, 29_600_000, pttId = PttId.EOT, busyLockout = true),
            Channel(162_400_000, Duplex.OFF, power = Power.LOW),
        )
        for (c in channels) assertEquals(NicFw950Codec.normalize(c), NicFw950Codec.decode(NicFw950Codec.encode(c)))
        assertEquals(30, NicFw950Codec.decode(NicFw950Codec.encode(channels[0]))!!.name.length)
    }

    @Test fun `deleting a channel clears its frequency and magic, not its other bytes`() {
        val target = sample.withChannels(sample.channels().toMutableList().also { it[0] = null })
        val o = NicFw950Image.channelOffset(0)
        val bytes = target.bytes()
        assertNull(target.channel(0))
        assertEquals(0, NicFw950Codec.u32(bytes, o))
        assertEquals(0, NicFw950Codec.u16(bytes, o + 24))
        assertEquals("Calling 2m", NicFw950Codec.decodeName(bytes, o + 32))
    }

    @Test fun `download reproduces the radio's channel section with checked replies`() {
        val sim = radio()
        val progress = mutableListOf<CloneProgress>()
        val read = clone(sim, progress).download()
        assertEquals(sample, read)
        assertSame(NicFw950Driver, read.driver)
        assertEquals(CloneProgress.Phase.IDENTIFYING, progress.first().phase)
        assertEquals(NicFw950Image.SIZE, progress.last().bytesDone)
    }

    @Test fun `upload writes only changed blocks, commits, reboots, and reads back equal`() {
        val sim = radio()
        val sleeps = mutableListOf<Long>()
        val before = clone(sim).download()
        val target = edited(before)
        val changed = (0 until NicFw950Image.SIZE step 128).count { o ->
            !target.bytes().copyOfRange(o, o + 128).contentEquals(before.bytes().copyOfRange(o, o + 128))
        }
        val written = clone(sim, sleeps = sleeps).upload(target, before)
        assertEquals(changed, written)
        assertEquals(changed, sim.blocksWritten)
        assertTrue("a handful of blocks, not the whole section", written in 3..8)
        assertEquals(1, sim.commits)
        assertEquals(1, sim.reboots)
        assertTrue(sleeps.contains(NicFw950Clone.REBOOT_DELAY_MS))
        assertEquals(target, sim.currentImage())
        assertEquals(target, clone(sim).download(CloneProgress.Phase.VERIFYING))
        assertEquals("Calling 146.52", sim.currentImage().channel(0)!!.name)
        assertNull(sim.currentImage().channel(4))
    }

    @Test fun `nothing to change writes nothing and does not reboot`() {
        val sim = radio()
        val before = clone(sim).download()
        assertEquals(0, clone(sim).upload(before, before))
        assertEquals(0, sim.blocksWritten)
        assertEquals(0, sim.reboots)
    }

    @Test fun `the read back after a reboot waits for the radio to come up`() {
        val sim = radio()
        val before = clone(sim).download()
        clone(sim).upload(edited(before), before)
        sim.rebootingReads = 2
        clone(sim).upload(edited(before).withChannels(edited(before).channels().toMutableList().also { it[1] = null }), edited(before))
        val sleeps = mutableListOf<Long>()
        assertEquals(sim.currentImage(), clone(sim, sleeps = sleeps).download())
        assertEquals(listOf(NicFw950Clone.RETRY_DELAY_MS, NicFw950Clone.RETRY_DELAY_MS), sleeps)
    }

    @Test fun `a radio with stock firmware gets advice to pick the other type`() {
        val sim = radio().also { it.silent = true }
        val e = expect<RadioException.NoContact> { clone(sim).download() }
        assertTrue(e.message!!.contains("\"RT-950 Pro\""))
    }

    @Test fun `a nicFW radio with another model's records is refused`() {
        val sim = NicFw950Simulator(NicFw950Image.of(ByteArray(NicFw950Image.SIZE)))
        expect<RadioException.IdentMismatch> { clone(sim).download() }
    }

    @Test fun `a radio whose channels changed since the read is refused before writing`() {
        val sim = radio()
        val before = clone(sim).download()
        val other = before.withChannels(before.channels().toMutableList().also { it[0] = it[0]!!.copy(name = "Changed elsewhere") })
        clone(sim).upload(other, before)
        val blocksBefore = sim.blocksWritten
        expect<RadioException.IdentMismatch> { clone(sim).upload(edited(before), before) }
        assertEquals(blocksBefore, sim.blocksWritten)
    }

    @Test fun `a VFO the radio saves during the write is not undone by the shared first block`() {
        val sim = radio()
        val before = clone(sim).download()
        // Channel 1's frequency shares a block with VFO slot 3.
        val target = before.withChannels(before.channels().toMutableList().also { it[0] = it[0]!!.copy(rxFreqHz = 146_550_000) })
        val changed = (0 until NicFw950Image.SIZE step 128).count { o ->
            !target.bytes().copyOfRange(o, o + 128).contentEquals(before.bytes().copyOfRange(o, o + 128))
        }
        assertEquals(1, changed)
        // After the pre-write check reads every changed block, the radio moves VFO D (slot 3).
        val vfoByte = 3 * record + 1
        sim.changeAfterReads = NicFw950Image.SIZE / 128 + changed
        sim.onRead = { it[vfoByte] = 0x42 }
        clone(sim).upload(target, before)
        val now = sim.currentImage()
        assertEquals(0x42, now.bytes()[vfoByte].toInt())
        assertEquals(target.channel(0), now.channel(0))
        assertTrue(target.mismatchedChannels(now).isEmpty())
    }

    @Test fun `a channel saved on the radio during the write stops it before the commit`() {
        val sim = radio()
        val before = clone(sim).download()
        val target = edited(before)
        val changed = (0 until NicFw950Image.SIZE step 128).count { o ->
            !target.bytes().copyOfRange(o, o + 128).contentEquals(before.bytes().copyOfRange(o, o + 128))
        }
        // After the pre-write check, someone edits channel 999 on the keypad.
        val last = NicFw950Image.channelOffset(998) + NicFw950Codec.NAME_OFFSET
        sim.changeAfterReads = NicFw950Image.SIZE / 128 + changed
        sim.onRead = { it[last] = 'Z'.code.toByte() }
        val e = expect<RadioException.Protocol> { clone(sim).upload(target, before) }
        assertTrue(e.message!!.contains("restore the backup"))
        assertEquals(0, sim.commits)
        assertEquals('Z'.code.toByte(), sim.currentImage().bytes()[last])
    }

    @Test fun `an image that changes the VFOs is refused`() {
        val before = sample
        val bytes = before.bytes().also { it[0] = (it[0] + 1).toByte() }
        val sim = radio()
        expect<RadioException.Unsupported> { clone(sim).upload(NicFw950Image.of(bytes), before) }
        assertEquals(0, sim.blocksWritten)
    }

    @Test fun `a damaged reply is reported, never used`() {
        val sim = radio().also { it.damageReplies = true }
        val e = expect<RadioException.Protocol> { clone(sim).download() }
        assertTrue(e.message!!.contains("checksum"))
    }

    @Test fun `a pulled cable mid write leaves nothing committed and says so`() {
        val sim = radio()
        val before = clone(sim).download()
        sim.detachAfterHostBytes = sim.hostBytesReceived + 600
        expect<RadioException.Disconnected> { clone(sim).upload(edited(before), before) }
        assertEquals(before, sim.currentImage())
    }

    @Test fun `a block the radio stores wrongly shows up only in the read back`() {
        val sim = radio()
        val before = clone(sim).download()
        sim.corruptWriteAddresses = setOf(NicFw950Image.BASE_ADDRESS + 256)
        val target = edited(before)
        clone(sim).upload(target, before)
        val back = clone(sim).download()
        assertTrue(target.mismatchedChannels(back).isNotEmpty())
    }

    @Test fun `the driver is registered and recognizes its own images`() {
        assertSame(NicFw950Driver, RadioDrivers.byId("nicfw950"))
        assertSame(NicFw950Driver, RadioDrivers.recognize(sample.bytes()))
        assertSame(NicFw950Driver, RadioDrivers.forChirpModel(NicFw950Image.CHIRP_MODEL))
        assertEquals(1, RadioDrivers.all.count { it.recognizes(sample.bytes()) })
        assertArrayEquals(sample.bytes(), RadioDrivers.image("nicfw950", sample.bytes()).bytes())
        assertEquals(38_400, driver.baudRate)
    }
}
