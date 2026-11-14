package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DM-32UV image through [RadioDriver] and [RadioImage], against CHIRP's sample of a real radio. */
class Dm32uvImageTest {
    private fun fixtureFile() = ChirpImg.decode(Fixtures.bytes("chirp-dm32uv.img"))
    private fun fixture(): RadioImage = fixtureFile().image

    private fun RadioImage.zones() = (this as Dm32uvImage).zones
    private fun RadioImage.slot(tag: Int) = (this as Dm32uvImage).slotBytes(tag)
    private fun RadioImage.edit(vararg changes: Pair<Int, Channel?>): RadioImage =
        withChannels(channels().toMutableList().apply { changes.forEach { (n, c) -> this[n - 1] = c } })

    @Test fun `CHIRP image is recognized as a DM-32UV with CHIRP's metadata`() {
        val parsed = fixtureFile()
        assertEquals("DM-32UV", parsed.metadata["model"])
        assertEquals(Dm32uvDriver, parsed.image.driver)
        assertEquals(Dm32uvDriver, RadioDrivers.recognize(parsed.image.bytes()))
        assertEquals(311_296, parsed.image.size)
        assertEquals("DP570UV", parsed.image.identity)
        assertNull(parsed.image.firmwareVersion) // the image bytes do not hold it
        assertEquals(
            mapOf("rclass" to "DM32UV", "vendor" to "Baofeng", "model" to "DM-32UV", "variant" to ""),
            parsed.image.chirpMetadata,
        )
    }

    @Test fun `refuses bytes of another size`() {
        assertFalse(Dm32uvDriver.recognizes(ByteArray(311_295)))
        try {
            Dm32uvDriver.image(ByteArray(1000))
            throw AssertionError("expected InvalidImageException")
        } catch (e: InvalidImageException) {
            assertTrue(e.message!!.contains("311296"))
        }
    }

    @Test fun `decodes every kind of channel in the fixture as CHIRP does`() {
        val ch = fixture().channels()
        assertEquals(Channel(146_520_000, name = "Simplex FM"), ch[0])
        assertEquals(Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "Rpt Tone"), ch[1])
        assertEquals(
            Channel(147_330_000, Duplex.PLUS, 600_000, Squelch.Ctcss(1318), Squelch.Ctcss(1318), Power.MID, name = "Rpt TSQL"),
            ch[2],
        )
        assertEquals(
            Channel(446_100_000, txSquelch = Squelch.Dcs(754, true), rxSquelch = Squelch.Dcs(754, true), power = Power.LOW, wide = false, name = "DCS Rev"),
            ch[4],
        )
        assertEquals(Channel(145_500_000, txSquelch = Squelch.Ctcss(885), rxSquelch = Squelch.Dcs(125), name = "Cross"), ch[5])
        assertEquals(Channel(162_550_000, Duplex.OFF, power = Power.LOW, wide = false, name = "RX only"), ch[8])
        assertEquals(
            Channel(441_000_000, Duplex.PLUS, 5_000_000, name = "DMR Local", digitalMode = "DMR"),
            ch[9],
        )
        assertEquals(434_043_500, ch[13]!!.rxFreqHz)
        assertNull(ch[14])
        assertEquals(14, fixture().usedChannelCount)
        assertEquals(listOf("Analog" to listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 14), "Digital" to listOf(10, 11, 12)), fixture().zones())
    }

    @Test fun `rewriting every channel unchanged is byte identical`() {
        for (img in listOf(fixture(), Dm32uvDriver.sampleImage())) {
            assertArrayEquals(img.bytes(), img.withChannels(img.channels()).bytes())
        }
    }

    @Test fun `fields the radio lacks do not count as a change`() {
        val img = fixture()
        val same = img.channels().map { it?.takeIf { c -> c.digitalMode == null }?.copy(scanAdd = false, pttIdCode = 3) ?: it }
        assertArrayEquals(img.bytes(), img.withChannels(same).bytes())
    }

    @Test fun `an edited analog channel keeps the fields Offset does not model`() {
        val img = fixture()
        val old = img.channel(1)!!
        val edited = img.edit(2 to old.copy(name = "Renamed", power = Power.LOW, busyLockout = true, pttId = PttId.EOT))
        assertEquals(old.copy(name = "Renamed", power = Power.LOW, busyLockout = true, pttId = PttId.EOT), edited.channel(1))
        val off = 0x9000 + 0x10 + 0x30
        val a = img.bytes().copyOfRange(off, off + 0x30)
        val b = edited.bytes().copyOfRange(off, off + 0x30)
        val modeled = (0 until 16) + listOf(0x18, 0x1A, 0x29)
        for (i in 0 until 0x30) if (i !in modeled) assertEquals("record byte $i", a[i], b[i])
        assertEquals(listOf(1), img.mismatchedChannels(edited))
        // Only the edited channel's page changed.
        assertEquals(listOf(0x12), Dm32uvLayout.IMAGE_TAGS.filter { !img.slot(it).contentEquals(edited.slot(it)) })
    }

    @Test fun `a new channel starts from CHIRP's template and joins a new zone named Offset`() {
        val img = fixture()
        val ch = Channel(145_230_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "New")
        val edited = img.edit(20 to ch, 21 to ch.copy(name = "New 2"))
        assertEquals(ch, edited.channel(19))
        assertEquals(ch.copy(name = "New 2"), edited.channel(20))
        assertEquals(21, (edited as Dm32uvImage).channelCount)
        for (gap in 15..19) {
            val off = 0x9000 + 0x10 + (gap - 1) * 0x30
            assertTrue("gap $gap cleared", edited.bytes().copyOfRange(off, off + 0x30).all { it == 0xFF.toByte() })
        }
        assertEquals(listOf("Analog", "Digital", "Offset"), edited.zones.map { it.first })
        assertEquals(listOf(20, 21), edited.zones[2].second)
        // Adding to the existing Offset zone later.
        val again = edited.edit(30 to ch.copy(name = "Later"))
        assertEquals(listOf(20, 21, 30), (again as Dm32uvImage).zones[2].second)
    }

    @Test fun `new channels overflow into Offset 2 and Offset 3 at 64 members`() {
        val img = Dm32uvDriver.sampleImage()
        val slots = img.channels().toMutableList()
        for (n in 101..230) slots[n - 1] = Channel(146_000_000L + n * 12_500L, name = "C$n")
        val edited = img.withChannels(slots) as Dm32uvImage
        assertEquals(listOf("Zone 1", "Offset", "Offset 2", "Offset 3"), edited.zones.map { it.first })
        assertEquals((101..164).toList(), edited.zones[1].second)
        assertEquals((165..228).toList(), edited.zones[2].second)
        assertEquals(listOf(229, 230), edited.zones[3].second)
        assertEquals(135, edited.usedChannelCount)
        assertEquals(Channel(146_000_000L + 230 * 12_500L, name = "C230"), edited.channel(229))
    }

    @Test fun `deleting a channel takes it out of zones and scan lists and lowers the count`() {
        val img = fixture()
        val edited = img.edit(5 to null) as Dm32uvImage
        assertNull(edited.channel(4))
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8, 9, 13, 14), edited.zones[0].second)
        val scan = edited.slot(0x11)
        assertEquals(5, scan[0x0C].toInt()) // "Analog scan" now has 5 members
        assertEquals(listOf(1, 2, 3, 4, 7), (0 until 5).map { scan[0x19 + 2 * it].toInt() })
        assertEquals(14, edited.channelCount)
        // Deleting the last two channels lowers the count past the empty ones.
        val shorter = img.edit(13 to null, 14 to null) as Dm32uvImage
        assertEquals(12, shorter.channelCount)
    }

    @Test fun `deleting every DMR channel drops their zone, empties their scan list and clears their TX contacts`() {
        val img = fixture()
        val edited = img.edit(10 to null, 11 to null, 12 to null) as Dm32uvImage
        assertEquals(listOf("Analog"), edited.zones.map { it.first })
        val scan = edited.slot(0x11)
        val dmrList = 0x01 + 0x39
        assertEquals(0, scan[dmrList + 0x0B].toInt())
        assertEquals(1, scan[dmrList + 0x0C + 3].toInt()) // designed channel reset to channel 1
        val txc = edited.slot(0x42)
        for (n in 10..12) assertEquals("TX contact of $n", 0, txc[2 * (n - 1) + 1].toInt())
        assertTrue(img.differsOnlyInWritableRegions(edited))
    }

    @Test fun `a DMR channel is kept, deleted, or refused when altered`() {
        val img = fixture()
        val dmr = img.channel(9)!!
        assertEquals("DMR", dmr.digitalMode)
        for (bad in listOf(dmr.copy(name = "Edited"), dmr.copy(digitalMode = null), Channel(146_520_000))) {
            try {
                img.edit(10 to bad)
                throw AssertionError("expected IllegalArgumentException for $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("DMR"))
            }
        }
        try {
            img.edit(20 to Channel(441_000_000, digitalMode = "DMR"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("digital"))
        }
        assertNull(img.edit(10 to null).channel(9))
    }

    @Test fun `writable memory is exactly the channel, TX contact, scan list and zone pages`() {
        val img = fixture()
        val edited = img.edit(1 to null, 30 to Channel(446_000_000, name = "X"))
        assertTrue(img.differsOnlyInWritableRegions(edited))
        // A settings change (tag 0x04) is outside.
        val settings = img.bytes().also { it[0x2000 + 0x30] = (it[0x2000 + 0x30] + 1).toByte() }
        assertFalse(img.differsOnlyInWritableRegions(Dm32uvDriver.image(settings)))
        // So is the DMR contact list (tag 0x44).
        val contacts = img.bytes().also { it[Dm32uvLayout.slot(0x44)] = 0x55 }
        assertFalse(img.differsOnlyInWritableRegions(Dm32uvDriver.image(contacts)))
        // Restoring an edited image onto a radio with other settings keeps the radio's settings.
        val restored = Dm32uvDriver.image(settings).withWritableRegionsOf(edited)
        assertEquals(edited.channels(), restored.channels())
        assertArrayEquals(Dm32uvDriver.image(settings).slot(0x04), restored.slot(0x04))
        assertEquals(listOf(0, 29), img.mismatchedChannels(edited))
        assertTrue(edited.mismatchedChannels(restored).isEmpty())
    }

    @Test fun `CHIRP img export carries the firmware read from a radio`() {
        val img = (Dm32uvDriver.simulator(Dm32uvDriver.sampleImage()) as Dm32uvSimulator).currentImage()
        assertEquals("DM32.01.01.047", img.firmwareVersion)
        val file = ChirpImg.encode(img)
        val parsed = ChirpImg.decode(file)
        assertEquals("DM32.01.01.047", parsed.metadata["dm32uv_firmware"])
        assertEquals(img, parsed.image)
    }

    @Test fun `sample image is a small analog codeplug with one zone`() {
        val img = Dm32uvDriver.sampleImage()
        assertEquals(5, img.usedChannelCount)
        assertTrue(img.channels().filterNotNull().all { it.digitalMode == null })
        assertEquals(listOf("Zone 1" to listOf(1, 2, 3, 4, 5)), img.zones())
        assertTrue(img.channels().filterNotNull().all { ChannelValidator.validate(it, img.profile).isEmpty() })
    }

    @Test fun `profile matches CHIRP's features`() {
        val p = Dm32uvDriver.defaultProfile
        assertTrue(p.inBand(136_000_000) && p.inBand(173_995_000) && p.inBand(400_000_000) && p.inBand(479_995_000))
        assertFalse(p.inBand(174_000_000) || p.inBand(480_000_000) || p.inBand(220_000_000))
        assertEquals(listOf(Power.HIGH, Power.MID, Power.LOW), p.powerLevels)
        assertEquals(listOf(5.0, 2.5, 1.0), p.powerLevels.map { p.watts(it) })
        assertEquals("Mixed case ~ok", p.sanitizeName("Mixed case ~ok"))
        assertEquals(16, p.nameLength)
    }
}
