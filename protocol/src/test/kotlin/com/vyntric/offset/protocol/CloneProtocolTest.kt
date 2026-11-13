package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloneProtocolTest {
    private fun clone(radio: SerialPort, progress: MutableList<CloneProgress> = mutableListOf()) =
        Uv5rClone(radio, Sleeper.NONE) { progress += it }

    @Test fun `download reproduces the radio image exactly`() {
        for ((name, load) in Fixtures.all) {
            val img = load()
            if (!img.hasAux) continue
            val radio = SimulatedRadio.fromImage(img)
            val progress = mutableListOf<CloneProgress>()
            val read = clone(radio, progress).download()
            assertEquals("fixture $name", img, read)
            assertEquals(RadioImage.MAIN_SIZE + RadioImage.AUX_SIZE, progress.last().bytesDone)
        }
    }

    @Test fun `upload writes only changed channel blocks and reads back identical`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory())
        val before = clone(radio).download()
        radio.endSession()
        val after = before.withChannel(0, Channel(146_520_000, name = "CALL"))
            .withChannel(5, Channel(442_100_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(1000), name = "RPT"))
        val blocks = ImageRegions.changedBlocks(before, after)
        assertEquals(4, blocks.size) // two memory records and two name records
        clone(radio).upload(after, blocks)
        radio.endSession()
        assertEquals(4, radio.blocksWritten)
        val verify = clone(radio).download()
        assertEquals(after, verify)
        assertTrue(ImageRegions.mismatchedChannels(after, verify).isEmpty())
    }

    @Test fun `upload refuses an image whose ident does not match and writes nothing`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory())
        val foreign = Fixtures.uv5rSunnyvale().withChannel(0, Channel(146_520_000))
        try {
            clone(radio).upload(foreign, ImageRegions.changedBlocks(Fixtures.uv5rSunnyvale(), foreign))
            fail("expected IdentMismatch")
        } catch (e: RadioException.IdentMismatch) {
            assertEquals(0, radio.blocksWritten)
        }
    }

    @Test fun `upload refuses a same model radio whose channels differ from the read`() {
        // Same ident and firmware (same model), different channel memory: a second unit.
        val first = Fixtures.uv5rFactory()
        val second = SimulatedRadio.fromImage(first.withChannel(9, Channel(147_000_000, name = "OTHER")))
        val target = first.withChannel(0, Channel(146_520_000))
        try {
            clone(second).upload(target, ImageRegions.changedBlocks(first, target), expectedBefore = first)
            fail("expected IdentMismatch")
        } catch (e: RadioException.IdentMismatch) {
            assertEquals(0, second.blocksWritten)
        }
    }

    @Test fun `upload with an unchanged radio passes the pre write comparison`() {
        val img = Fixtures.uv5rFactory()
        val radio = SimulatedRadio.fromImage(img)
        val target = img.withChannel(0, Channel(146_520_000))
        clone(radio).upload(target, ImageRegions.changedBlocks(img, target), expectedBefore = img)
        assertEquals(target, radio.currentImage())
    }

    @Test fun `upload refuses blocks outside the channel and name regions`() {
        val img = Fixtures.uv5rFactory()
        val radio = SimulatedRadio.fromImage(img)
        try {
            clone(radio).upload(img, listOf(0x0E08)) // settings block
            fail("expected Unsupported")
        } catch (e: RadioException.Unsupported) {
            assertEquals(0, radio.blocksWritten)
        }
    }

    @Test fun `silent radio reports no contact after trying every magic`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory()).apply { silent = true }
        try {
            clone(radio).download()
            fail("expected NoContact")
        } catch (e: RadioException.NoContact) {
            // expected
        }
    }

    @Test fun `cable detach mid download surfaces as Disconnected`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory()).apply { detachAfterHostBytes = 200 }
        try {
            clone(radio).download()
            fail("expected Disconnected")
        } catch (e: RadioException.Disconnected) {
            // expected
        }
    }

    @Test fun `cable detach mid upload leaves a partial write that verification detects`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory())
        val before = clone(radio).download()
        radio.endSession()
        val after = before.withChannels(List(RadioImage.CHANNEL_COUNT) { Channel(146_000_000L + it * 15_000L, name = "CH$it") })
        // Handshake plus firmware probe is 24 bytes; each block written is 20 bytes.
        radio.detachAfterHostBytes = radio.hostBytesReceived + 24 + 20 * 10
        try {
            clone(radio).upload(after, ImageRegions.changedBlocks(before, after))
            fail("expected Disconnected")
        } catch (e: RadioException.Disconnected) {
            assertTrue(radio.blocksWritten in 1 until ImageRegions.changedBlocks(before, after).size)
        }
        radio.reattach()
        val readBack = clone(radio).download()
        assertTrue(ImageRegions.mismatchedChannels(after, readBack).isNotEmpty())
    }

    @Test fun `verification catches a radio that stores different bytes than it acknowledged`() {
        val radio = SimulatedRadio.fromImage(Fixtures.uv5rFactory())
        val before = clone(radio).download()
        radio.endSession()
        val after = before.withChannel(2, Channel(147_000_000, Duplex.PLUS, 600_000))
        radio.corruptWriteAddresses = setOf(RadioImage.radioAddress(RadioImage.channelOffset(2)))
        clone(radio).upload(after, ImageRegions.changedBlocks(before, after))
        radio.endSession()
        assertEquals(listOf(2), ImageRegions.mismatchedChannels(after, clone(radio).download()))
    }

    @Test fun `tolerates an ACK delivered in front of the first block header`() {
        // baoclone observed the BF-F8HP delivering an ACK late, ahead of the next 'X' header.
        val sim = SimulatedRadio.fromImage(Fixtures.f8hpFactory())
        val port = object : SerialPort {
            private val extra = ArrayDeque<Byte>()
            private var reads = 0
            override fun write(data: ByteArray) {
                sim.write(data)
                if (data.size == 4 && data[0] == 'S'.code.toByte() && reads++ == 0) extra.addLast(Uv5rClone.ACK)
            }
            override fun read(count: Int, timeoutMs: Long): ByteArray {
                val out = ArrayList<Byte>()
                while (out.size < count && extra.isNotEmpty()) out += extra.removeFirst()
                if (out.size < count) out += sim.read(count - out.size, timeoutMs).toList()
                return out.toByteArray()
            }
            override fun purge() { extra.clear(); sim.purge() }
        }
        assertEquals(Fixtures.f8hpFactory(), clone(port).download())
    }
}
