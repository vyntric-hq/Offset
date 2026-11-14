/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py _Link, _search, _identify, _enter_program,
 * _scan, do_download, _write_page, _follow_display, do_upload), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.PAGE
import com.vyntric.offset.protocol.Dm32uvLayout.u8
import java.io.IOException

/**
 * The DM-32UV clone protocol, one session per instance.
 *
 * Every command is preceded by a 10 ms pause and an input purge. Identification: `PSEARCH`
 * (up to 5 times; the reply is ACK + the 7 byte model ID), `PASSSTA` ('P', write password
 * flag, read password flag), `SYSINFO` (ACK), then `V` queries for the firmware (index 1) and
 * the codeplug flash range (index 10). Programming mode: `G 00 00 00 00 01` (0x106 bytes,
 * starting 'S'), `FF FF FF FF 0C`, `PROGRAM` (ACK), `02` (8 bytes), ACK (ACK).
 * Read: `R addr24le len16le`, reply `W addr24le len16le` + data. Write: `W addr24le len16le`
 * + data, reply ACK. Each 4 KB page ends in a tag naming its contents, so the session first
 * reads the last byte of every page in the range to find where each tag lives.
 *
 * The protocol has no checksums. Any bad or missing reply (apart from the PSEARCH retries)
 * stops the transfer; a reply that is garbled the way some CH340 cables garble it (bit 7 set)
 * stops it with cable advice.
 */
class Dm32uvClone(
    private val port: SerialPort,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val onProgress: (CloneProgress) -> Unit = {},
) {
    private data class RadioInfo(val firmware: String, val start: Int, val end: Int)

    private class FlashMap(val where: Map<Int, List<Int>>, val free: List<Int>)

    /** Reads the whole image. A tag with more than one page is read from the first, as CHIRP does. */
    fun download(phase: CloneProgress.Phase = CloneProgress.Phase.READING): Dm32uvImage = io {
        val info = identify(writing = false)
        enterProgram()
        val flash = scan(info)
        val image = ByteArray(Dm32uvLayout.IMAGE_SIZE) { 0xFF.toByte() }
        val total = image.size
        Dm32uvLayout.IMAGE_TAGS.forEachIndexed { i, tag ->
            flash.where[tag]?.firstOrNull()?.let { readBlock(it, PAGE).copyInto(image, i * PAGE) }
            onProgress(CloneProgress(phase, (i + 1) * PAGE, total))
        }
        Dm32uvImage.of(image, info.firmware)
    }

    /**
     * Writes the pages of Offset's writable memory (see [Dm32uvLayout.WRITABLE_TAGS]) where
     * [target] differs from what the radio holds, each as one whole page, read back to check it.
     * A page goes where the radio keeps that tag now, or to the first free page.
     *
     * Before writing anything it refuses: a radio that is not a DM-32UV or has a password, a
     * radio with more than one page for a tag, a radio whose writable pages no longer equal
     * [before], and an image that would need more free pages than the radio has.
     * Returns the number of pages written.
     */
    fun upload(target: Dm32uvImage, before: Dm32uvImage): Int = io {
        val info = identify(writing = true)
        enterProgram()
        val flash = scan(info)
        val dupes = Dm32uvLayout.CHIRP_UPLOAD_TAGS.filter { (flash.where[it]?.size ?: 0) > 1 }
        if (dupes.isNotEmpty()) {
            throw RadioException.Protocol(
                "The radio has more than one page with tag ${dupes.joinToString(", ") { "%02X".format(it) }}, so " +
                    "Offset is not writing. Switch the radio off and on, read it again and retry."
            )
        }
        onProgress(CloneProgress(CloneProgress.Phase.VERIFYING, 0, 0))
        val current = Dm32uvLayout.WRITABLE_TAGS.mapNotNull { tag ->
            flash.where[tag]?.first()?.let { tag to readBlock(it, PAGE) }
        }.toMap()
        val unchanged = Dm32uvLayout.WRITABLE_TAGS.all { tag ->
            (current[tag] ?: EMPTY_PAGE).contentEquals(before.slotBytes(tag))
        }
        if (!unchanged) {
            throw RadioException.IdentMismatch(
                "The radio's channels changed since it was read. Is a different radio connected? Nothing was written."
            )
        }
        val writes = plan(target, flash, current)
        val total = writes.size * PAGE
        onProgress(CloneProgress(CloneProgress.Phase.WRITING, 0, total))
        writes.forEachIndexed { i, (addr, page) ->
            writePage(addr, page, info)
            onProgress(CloneProgress(CloneProgress.Phase.WRITING, (i + 1) * PAGE, total))
        }
        writes.size
    }

    /** CHIRP do_upload's choice of pages, decided before anything is written. */
    private fun plan(target: Dm32uvImage, flash: FlashMap, current: Map<Int, ByteArray>): List<Pair<Int, ByteArray>> {
        val free = ArrayDeque(flash.free)
        val zones = Dm32uvCodeplug(target.bytes()).zones()
        val writes = ArrayList<Pair<Int, ByteArray>>()
        for (tag in Dm32uvLayout.WRITABLE_TAGS) {
            val slot = target.slotBytes(tag)
            val emptyBody = (0 until PAGE - 1).all { slot[it] == 0xFF.toByte() }
            // A slot is in use if it holds data, or if it came from the radio (tag byte set) and
            // has since been emptied: then the radio's page must be emptied too.
            val onRadio = current[tag]
            if (emptyBody && !(slot.u8(PAGE - 1) == tag && onRadio != null)) continue
            var want = slot.copyOf().also { it[PAGE - 1] = tag.toByte() }
            if (onRadio != null) {
                if (tag == Dm32uvLayout.ZONE_TAG0) want = followDisplay(want, onRadio, current, zones)
                if (!onRadio.contentEquals(want)) writes += flash.where.getValue(tag).first() to want
            } else {
                val addr = free.removeFirstOrNull()
                    ?: throw RadioException.Protocol("The radio has no free flash page left. Nothing was written.")
                writes += addr to want
            }
        }
        return writes
    }

    /**
     * Zone header bytes 1 to 7 for the upload (CHIRP _follow_display). Bytes 5 and 7 are the zone
     * lines A and B show, bytes 1 and 3 the position in it. They change whenever someone browses
     * on the radio, so the radio's own values are kept, moved to where that zone and channel are
     * in the new zone list.
     */
    private fun followDisplay(
        want: ByteArray,
        currentPage: ByteArray,
        radioPages: Map<Int, ByteArray>,
        zones: List<Dm32uvCodeplug.Zone>,
    ): ByteArray {
        val out = want.copyOf()
        currentPage.copyInto(out, 1, 1, 8)
        for ((pos, zoneByte) in listOf(1 to 5, 3 to 7)) {
            val z = currentPage.u8(zoneByte)
            val old = if (z in 1..Dm32uvLayout.ZONE_COUNT) radioZone(z, radioPages) else null
            var new: Int? = null
            if (old != null) {
                new = zones.indexOfFirst { it == old }.takeIf { it >= 0 }?.plus(1)
                    ?: zones.indexOfFirst { it.name == old.name }.takeIf { it >= 0 }?.plus(1)
            }
            if (new == null) new = if (old == null && z in 1..zones.size) z else 1
            val members = if (zones.isNotEmpty()) zones[new - 1].members else emptyList()
            var p = currentPage.u8(pos)
            if (old != null) {
                val channel = if (p in 1..old.members.size) old.members[p - 1] else null
                p = if (channel != null && channel in members) members.indexOf(channel) + 1 else 1
            }
            if (p !in 1..maxOf(members.size, 1)) p = 1
            out[zoneByte] = new.toByte()
            out[pos] = p.toByte()
        }
        return out
    }

    /** Zone [z] as the radio holds it now, from the zone pages already read (CHIRP radio_zone). */
    private fun radioZone(z: Int, radioPages: Map<Int, ByteArray>): Dm32uvCodeplug.Zone? {
        val page = (z - 1) / Dm32uvLayout.ZONE_PER_PAGE
        val bytes = radioPages[Dm32uvLayout.ZONE_TAG0 + page] ?: return null
        val off = (if (page == 0) 0x10 else 0) + ((z - 1) % Dm32uvLayout.ZONE_PER_PAGE) * Dm32uvLayout.ZONE_REC
        return Dm32uvCodeplug.zoneRecord(bytes, off)
    }

    // --- identification and programming mode --------------------------------------------------

    private fun identify(writing: Boolean): RadioInfo {
        onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, 0, 0))
        val model = search()
        if (model != Dm32uvLayout.MODEL_ID) {
            throw RadioException.IdentMismatch(
                "This radio identifies as \"$model\", not as a Baofeng DM-32UV (${Dm32uvLayout.MODEL_ID})."
            )
        }
        // PASSSTA: 'P', write password flag, read password flag. The radio does not enforce them;
        // the vendor software asks for the password. Offset cannot, so it refuses instead.
        val pass = xfer(ascii("PASSSTA"), 3, "PASSSTA")
        if (pass[0] != 'P'.code.toByte()) throw RadioException.Protocol("Unexpected reply to PASSSTA from the radio.")
        if (pass.u8(2) == PASSWORD_SET) throw passwordRefusal("read")
        if (writing && pass.u8(1) == PASSWORD_SET) throw passwordRefusal("write")
        if (!xfer(ascii("SYSINFO"), 1, "SYSINFO").contentEquals(byteArrayOf(ACK))) {
            throw RadioException.Protocol("The radio refused SYSINFO.")
        }
        queryV(byteArrayOf(0x56, 0x00, 0x00, 0x40, 0x0D))
        val info = HashMap<Int, ByteArray>()
        for (i in 1..16) if (i != 12) info[i] = queryV(byteArrayOf(0x56, 0x00, 0x00, 0x00, i.toByte()))
        val range = info.getValue(10)
        if (range.size < 8) throw RadioException.Protocol("Unexpected codeplug range from the radio.")
        val start = u32le(range, 0)
        val end = u32le(range, 4)
        if ((start or end) ushr 24 != 0L || start >= end) {
            throw RadioException.Protocol("Unexpected codeplug range from the radio.")
        }
        val firmware = String(info.getValue(1), Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
            .map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("")
        return RadioInfo(firmware, start.toInt(), end.toInt())
    }

    /** PSEARCH, sent up to 5 times as the vendor software does: the radio may ignore the first. */
    private fun search(): String {
        var garbled = false
        repeat(SEARCH_ATTEMPTS) {
            send(ascii("PSEARCH"))
            val resp = port.read(8, LINK_TIMEOUT_MS)
            if (resp.size == 8 && resp[0] == ACK) {
                val id = resp.copyOfRange(1, 8)
                if (id.all { it >= 0 }) return String(id, Charsets.US_ASCII)
                garbled = true // the model ID is ASCII text
            }
        }
        if (garbled) throw garbledReply("The radio's reply was garbled")
        throw RadioException.NoContact()
    }

    private fun enterProgram() {
        if (xfer(byteArrayOf(0x47, 0, 0, 0, 0, 1), 0x106, "G")[0] != 'S'.code.toByte()) {
            throw RadioException.Protocol("Unexpected reply to G from the radio.")
        }
        send(byteArrayOf(-1, -1, -1, -1, 0x0C))
        if (!xfer(ascii("PROGRAM"), 1, "PROGRAM").contentEquals(byteArrayOf(ACK))) {
            throw RadioException.Protocol("The radio refused programming mode.")
        }
        xfer(byteArrayOf(0x02), 8, "02")
        if (!xfer(byteArrayOf(ACK), 1, "ACK").contentEquals(byteArrayOf(ACK))) {
            throw RadioException.Protocol("The radio did not acknowledge programming mode.")
        }
    }

    /** The tag of every page in the codeplug range. */
    private fun scan(info: RadioInfo): FlashMap {
        val pages = (info.start..info.end + 1 - PAGE step PAGE).toList()
        val where = LinkedHashMap<Int, MutableList<Int>>()
        pages.forEachIndexed { i, addr ->
            val tag = readBlock(addr + PAGE - 1, 1).u8(0)
            if (tag in 0x80 until 0xFF) {
                // No page tag has bit 7 set; a bad cable sets it, which would make a page look missing.
                throw garbledReply("Implausible page tag %02X at %06X".format(tag, addr))
            }
            where.getOrPut(tag) { ArrayList() } += addr
            onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, i + 1, pages.size))
        }
        val free = where.remove(0xFF) ?: emptyList()
        return FlashMap(where, free)
    }

    // --- pages ----------------------------------------------------------------------------------

    private fun readBlock(addr: Int, length: Int): ByteArray {
        val cmd = byteArrayOf('R'.code.toByte(), addr.toByte(), (addr ushr 8).toByte(), (addr ushr 16).toByte(),
            length.toByte(), (length ushr 8).toByte())
        send(cmd)
        val resp = port.read(6 + length, LINK_TIMEOUT_MS)
        val expected = byteArrayOf('W'.code.toByte()) + cmd.copyOfRange(1, 6)
        if (resp.size != 6 + length || !resp.copyOfRange(0, 6).contentEquals(expected)) {
            if (resp.isEmpty()) throw RadioException.Timeout("The radio stopped responding while reading flash at %06X.".format(addr))
            throw badReply("Bad reply reading flash at %06X".format(addr), resp.copyOfRange(0, minOf(6, resp.size)), expected)
        }
        return resp.copyOfRange(6, resp.size)
    }

    /**
     * Writes one whole page, then reads it back. The protocol has no checksums and the firmware
     * does not verify writes, so anything but an ACK and an exact read-back stops the upload.
     */
    private fun writePage(addr: Int, data: ByteArray, info: RadioInfo) {
        if (data.size != PAGE || addr % PAGE != 0 || addr < info.start || addr + PAGE - 1 > info.end ||
            data.u8(PAGE - 1) in Dm32uvLayout.NEVER_WRITE || data.u8(PAGE - 1) !in Dm32uvLayout.WRITABLE_TAGS
        ) {
            throw RadioException.Unsupported("Refusing an unsafe write at %06X.".format(addr))
        }
        send(byteArrayOf('W'.code.toByte(), addr.toByte(), (addr ushr 8).toByte(), (addr ushr 16).toByte(),
            PAGE.toByte(), (PAGE ushr 8).toByte()) + data)
        val ack = port.read(1, LINK_TIMEOUT_MS)
        val reason = when {
            !ack.contentEquals(byteArrayOf(ACK)) -> "no acknowledgement"
            !readBlock(addr, PAGE).contentEquals(data) -> "it did not read back as written"
            else -> return
        }
        throw RadioException.Protocol(
            "Writing the page at %06X failed (%s). Write to the radio again, or restore the image saved before the write."
                .format(addr, reason)
        )
    }

    // --- link -----------------------------------------------------------------------------------

    private fun send(data: ByteArray) {
        sleeper.sleep(SEND_DELAY_MS)
        port.purge()
        port.write(data)
    }

    private fun xfer(data: ByteArray, n: Int, what: String): ByteArray {
        send(data)
        val resp = port.read(n, LINK_TIMEOUT_MS)
        if (resp.size != n) throw RadioException.Timeout("The radio stopped responding (no reply to $what).")
        return resp
    }

    /** A V query: the reply is 'V', the index, a length, then the data. */
    private fun queryV(cmd: ByteArray): ByteArray {
        send(cmd)
        val hdr = port.read(3, LINK_TIMEOUT_MS)
        val body = if (hdr.size == 3) port.read(hdr.u8(2), LINK_TIMEOUT_MS) else ByteArray(0)
        if (hdr.size != 3 || hdr[0] != 'V'.code.toByte() || body.size != hdr.u8(2)) {
            if (hdr.isEmpty()) throw RadioException.Timeout("The radio stopped responding to an info query.")
            throw badReply("Bad reply from the radio to an info query", hdr.copyOfRange(0, 1), byteArrayOf('V'.code.toByte()))
        }
        return body
    }

    private fun garbledReply(message: String) = RadioException.Protocol("$message. ${Dm32uvDriver.CABLE_ADVICE}")

    /** A bad reply header; with cable advice when only bit 7 differs, the bad cables' signature. */
    private fun badReply(message: String, got: ByteArray, expected: ByteArray): RadioException {
        if (got.size == expected.size && got.map { (it.toInt() and 0x7F).toByte() } == expected.toList()) {
            return garbledReply(message)
        }
        return RadioException.Protocol("$message.")
    }

    private fun passwordRefusal(kind: String) = RadioException.Unsupported(
        "The radio has a $kind password. Offset cannot enter passwords; remove it with the vendor programming software first."
    )

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: RadioException) {
        throw e
    } catch (e: IOException) {
        throw RadioException.Disconnected(e)
    }

    companion object {
        const val ACK: Byte = 0x06
        const val PASSWORD_SET = 0xA5
        const val SEARCH_ATTEMPTS = 5
        const val SEND_DELAY_MS = 10L

        /** One timeout for every reply; a 4 KB page arrives well within it at 115200 baud. */
        const val LINK_TIMEOUT_MS = 5_000L

        private val EMPTY_PAGE = ByteArray(PAGE) { 0xFF.toByte() }

        private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

        private fun u32le(b: ByteArray, off: Int): Long =
            (0 until 4).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }
    }
}
