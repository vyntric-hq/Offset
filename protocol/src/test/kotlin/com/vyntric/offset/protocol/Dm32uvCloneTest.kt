package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The DM-32UV clone protocol through [RadioDriver.clone], against the simulated radio. */
class Dm32uvCloneTest {
    private fun fixture(): RadioImage = ChirpImg.decode(Fixtures.bytes("chirp-dm32uv.img")).image

    private fun radio(image: RadioImage = fixture()) = Dm32uvDriver.simulator(image) as Dm32uvSimulator

    private fun clone(port: SerialPort, progress: MutableList<CloneProgress> = mutableListOf(), sleeps: MutableList<Long> = mutableListOf()) =
        Dm32uvDriver.clone(port, { sleeps += it }, { progress += it })

    /** Reads, ends the session, and returns the image, as the app does before a write. */
    private fun read(radio: Dm32uvSimulator): RadioImage = clone(radio).download().also { radio.endSession() }

    private fun RadioImage.edit(vararg changes: Pair<Int, Channel?>): RadioImage =
        withChannels(channels().toMutableList().apply { changes.forEach { (n, c) -> this[n - 1] = c } })

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

    @Test fun `download finds pages by tag and reproduces the image exactly`() {
        for (img in listOf(fixture(), Dm32uvDriver.sampleImage())) {
            val radio = radio(img)
            // Pages are scattered, not in tag order, with free pages between them.
            val addrs = Dm32uvLayout.IMAGE_TAGS.mapNotNull { radio.pageAddresses(it).firstOrNull() }
            if (addrs.size > 2) assertTrue(addrs != addrs.sorted())
            assertTrue(radio.pageAddresses(0xFF).isNotEmpty())
            val progress = mutableListOf<CloneProgress>()
            val sleeps = mutableListOf<Long>()
            val read = clone(radio, progress, sleeps).download()
            assertEquals(img, read)
            assertEquals("DM32.01.01.047", read.firmwareVersion)
            assertEquals(CloneProgress(CloneProgress.Phase.READING, 311_296, 311_296), progress.last())
            assertTrue(sleeps.isNotEmpty() && sleeps.all { it == 10L })
        }
    }

    @Test fun `radio that ignores the first PSEARCHes is still found`() {
        val radio = radio().apply { ignoredSearches = 4 }
        assertEquals(fixture(), clone(radio).download())
    }

    @Test fun `writing back an unchanged image writes nothing`() {
        val radio = radio()
        val before = read(radio)
        assertEquals(0, clone(radio).upload(before, before))
        assertEquals(0, radio.pagesWritten)
    }

    @Test fun `edit, add and delete round trip through the radio`() {
        val radio = radio()
        val before = read(radio)
        val after = before.edit(
            2 to before.channel(1)!!.copy(name = "Edited", txSquelch = Squelch.Dcs(23)),
            5 to null,
            200 to Channel(439_987_500, Duplex.MINUS, 7_600_000, name = "Far away"),
        )
        val progress = mutableListOf<CloneProgress>()
        val written = clone(radio, progress).upload(after, before)
        radio.endSession()
        // Scan list, channel pages 0x12 and 0x14 (new on the radio), and the zone page.
        assertEquals(4, written)
        assertEquals(4, radio.pagesWritten)
        assertEquals(CloneProgress(CloneProgress.Phase.WRITING, 4 * 0x1000, 4 * 0x1000), progress.last())
        assertEquals(1, radio.pageAddresses(0x14).size)
        val verify = read(radio)
        assertTrue(after.mismatchedChannels(verify).isEmpty())
        assertEquals(after.channels(), verify.channels())
        assertTrue(before.differsOnlyInWritableRegions(verify))
        assertEquals(listOf("Analog", "Digital", "Offset"), (verify as Dm32uvImage).zones.map { it.first })
        // A second write of the same image changes nothing.
        assertEquals(0, clone(radio).upload(after, verify))
    }

    @Test fun `zone header follows what the radio shows when a zone is removed`() {
        val img = fixture()
        // Line A shows zone 2 (Digital), second channel; line B zone 1, third channel.
        val bytes = img.bytes()
        val hdr = Dm32uvLayout.ZONE_BASE
        bytes[hdr + 5] = 2; bytes[hdr + 1] = 2
        bytes[hdr + 7] = 1; bytes[hdr + 3] = 3
        val radio = radio(Dm32uvDriver.image(bytes))
        val before = read(radio)
        val after = before.edit(10 to null, 11 to null, 12 to null)
        clone(radio).upload(after, before)
        radio.endSession()
        val zonePage = (read(radio) as Dm32uvImage).slotBytes(0x5C)
        assertEquals(1, zonePage[0].toInt()) // one zone left
        assertEquals(1, zonePage[5].toInt()) // line A moved to zone 1, position 1
        assertEquals(1, zonePage[1].toInt())
        assertEquals(1, zonePage[7].toInt()) // line B stays on zone 1, channel 3
        assertEquals(3, zonePage[3].toInt())
    }

    @Test fun `restoring a backup empties a page the radio gained after it`() {
        val radio = radio()
        val before = read(radio) as Dm32uvImage
        val tag = 0x11
        assertEquals(tag, before.slotBytes(tag).last().toInt() and 0xFF)
        // The backup was taken when the radio had no scan list page at all.
        val backupBytes = before.bytes().also { val s = Dm32uvLayout.slot(tag); it.fill(0xFF.toByte(), s, s + Dm32uvLayout.PAGE) }
        val backup = Dm32uvDriver.image(backupBytes)
        val target = before.withWritableRegionsOf(backup)
        clone(radio).upload(target, before)
        radio.endSession()
        val after = read(radio) as Dm32uvImage
        assertTrue(after.slotBytes(tag).dropLast(1).all { it == 0xFF.toByte() })
        assertEquals(emptyList<Int>(), target.mismatchedChannels(after))
    }

    @Test fun `verification reports a channel whose zone membership did not stick`() {
        val image = fixture()
        val n = Dm32uvCodeplug(image.bytes()).zones().flatMap { it.members }.first()
        val target = image.edit(n to null) // deleting removes it from zones and scan lists
        val stillListed = Dm32uvDriver.image(target.bytes().also { bytes ->
            // Put channel n back into its old zone pages as the radio might after a failed write.
            val zoneTags = Dm32uvLayout.WRITABLE_TAGS.filter { it >= Dm32uvLayout.ZONE_TAG0 } + 0x11
            for (t in zoneTags) {
                val s = Dm32uvLayout.slot(t)
                image.bytes().copyInto(bytes, s, s, s + Dm32uvLayout.PAGE)
            }
        })
        assertTrue(target.mismatchedChannels(stillListed).contains(n - 1))
    }

    @Test fun `silent radio reports no contact`() {
        val radio = radio().apply { silent = true }
        expect<RadioException.NoContact> { clone(radio).download() }
    }

    @Test fun `cable pulled mid download is Disconnected`() {
        val radio = radio().apply { detachAfterHostBytes = 500 }
        expect<RadioException.Disconnected> { clone(radio).download() }
    }

    @Test fun `cable pulled mid write is Disconnected and verification sees the partial write`() {
        val twin = radio()
        val before = read(twin)
        val slots = before.channels().toMutableList()
        for (n in 100..400) slots[n - 1] = Channel(146_000_000L + n * 12_500L, name = "C$n")
        val after = before.withChannels(slots)
        // Measure the bytes a full write takes on an identical radio.
        val start = twin.hostBytesReceived
        val pages = clone(twin).upload(after, before)
        val total = twin.hostBytesReceived - start
        val perPage = 6L + 0x1000 + 6
        assertTrue(pages >= 3)

        val radio = radio()
        read(radio)
        radio.detachAfterHostBytes = radio.hostBytesReceived + total - pages * perPage + perPage + 1000
        expect<RadioException.Disconnected> { clone(radio).upload(after, before) }
        assertTrue(radio.pagesWritten in 1 until pages)
        radio.reattach()
        assertTrue(after.mismatchedChannels(read(radio)).isNotEmpty())
    }

    @Test fun `a page stored differently than sent is caught by the read back`() {
        val radio = radio()
        val before = read(radio)
        val after = before.edit(3 to before.channel(2)!!.copy(name = "Corrupt me"))
        radio.corruptWriteTags = setOf(0x12)
        val e = expect<RadioException.Protocol> { clone(radio).upload(after, before) }
        assertTrue(e.message!!.contains("did not read back as written"))
        assertFalse(e.message!!.any { it.code == 0x2014 }) // no em dash in user-facing text
    }

    @Test fun `radio with a read password is refused, with a write password only writes are`() {
        val locked = radio().apply { readPassword = true }
        val e = expect<RadioException.Unsupported> { clone(locked).download() }
        assertTrue(e.message!!.contains("read password"))

        val radio = radio().apply { writePassword = true }
        val before = read(radio)
        val w = expect<RadioException.Unsupported> { clone(radio).upload(before.edit(1 to null), before) }
        assertTrue(w.message!!.contains("write password"))
        assertEquals(0, radio.pagesWritten)
    }

    @Test fun `duplicate page tags refuse the write but not the read`() {
        val radio = radio()
        radio.addDuplicatePage(0x12)
        val before = read(radio)
        assertEquals(fixture(), before)
        val e = expect<RadioException.Protocol> { clone(radio).upload(before.edit(1 to null), before) }
        assertTrue(e.message!!.contains("tag 12"))
        assertEquals(0, radio.pagesWritten)
    }

    @Test fun `a radio of another model is refused before anything is written`() {
        val other = Dm32uvSimulator.fromImage(fixture() as Dm32uvImage, modelId = "DP999XX")
        val before = fixture()
        val e = expect<RadioException.IdentMismatch> { clone(other).upload(before.edit(1 to null), before) }
        assertTrue(e.message!!.contains("DP999XX"))
        assertEquals(0, other.pagesWritten)
    }

    @Test fun `a radio whose channels changed since the read is refused`() {
        val first = fixture()
        val second = radio(first.edit(9 to null))
        val e = expect<RadioException.IdentMismatch> { clone(second).upload(first.edit(1 to null), first) }
        assertTrue(e.message!!.contains("Nothing was written"))
        assertEquals(0, second.pagesWritten)
    }

    @Test fun `a garbled page tag stops with cable advice`() {
        val radio = radio().apply { garbleTagReads = true }
        val e = expect<RadioException.Protocol> { clone(radio).download() }
        assertTrue(e.message!!.contains("CH340"))
        assertTrue(e.message!!.contains(Dm32uvDriver.cableAdvice))
    }

    @Test fun `a radio with no free page left is refused before anything is written`() {
        val img = fixture()
        val tight = Dm32uvSimulator.fromImage(img as Dm32uvImage, pageCount = 18)
        val before = read(tight)
        val slots = before.channels().toMutableList()
        for (n in 100..1000 step 85) slots[n - 1] = Channel(146_000_000L + n * 12_500L, name = "C$n")
        val e = expect<RadioException.Protocol> { clone(tight).upload(before.withChannels(slots), before) }
        assertTrue(e.message!!.contains("no free flash page"))
        assertEquals(0, tight.pagesWritten)
    }
}
