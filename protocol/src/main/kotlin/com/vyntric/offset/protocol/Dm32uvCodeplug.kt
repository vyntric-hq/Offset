/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py DM32UV set_memory, zones, scan lists and
 * TX contacts), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.CH_COUNT
import com.vyntric.offset.protocol.Dm32uvLayout.CH_SIZE
import com.vyntric.offset.protocol.Dm32uvLayout.PAGE
import com.vyntric.offset.protocol.Dm32uvLayout.SCAN_MAX
import com.vyntric.offset.protocol.Dm32uvLayout.SCAN_MEMBERS
import com.vyntric.offset.protocol.Dm32uvLayout.SCAN_NAME
import com.vyntric.offset.protocol.Dm32uvLayout.SCAN_REC
import com.vyntric.offset.protocol.Dm32uvLayout.ZONE_COUNT
import com.vyntric.offset.protocol.Dm32uvLayout.ZONE_MEMBERS
import com.vyntric.offset.protocol.Dm32uvLayout.putU16le
import com.vyntric.offset.protocol.Dm32uvLayout.textPrefix
import com.vyntric.offset.protocol.Dm32uvLayout.u16le
import com.vyntric.offset.protocol.Dm32uvLayout.u8

/**
 * Reads and edits the channel side of a DM-32UV image in place: channel records, the channel
 * count, zones, scan lists and the per-channel TX contact table. Each edit does what CHIRP's
 * DM32UV.set_memory does, byte for byte, so images edited here and in CHIRP agree.
 *
 * Channel numbers are 1 based, as on the radio.
 */
internal class Dm32uvCodeplug(val data: ByteArray) {
    init {
        require(data.size == Dm32uvLayout.IMAGE_SIZE) { "Not a DM-32UV image" }
    }

    /** A zone as CHIRP compares them: name bytes (before any NUL or 0xFF) and members. */
    data class Zone(val name: List<Byte>, val members: List<Int>)

    private data class ScanList(val name: List<Byte>, val members: List<Int>, val opts: List<Byte>)

    // --- channels -------------------------------------------------------------------------

    var channelCount: Int
        get() = data.u16le(Dm32uvLayout.CH_BASE).let { if (it > CH_COUNT) 0 else it }
        private set(value) = data.putU16le(Dm32uvLayout.CH_BASE, value)

    fun channel(n: Int): Channel? =
        if (n > channelCount) null else Dm32uvCodec.decode(data, Dm32uvLayout.channelOffset(n))

    fun record(n: Int): ByteArray = Dm32uvLayout.channelOffset(n).let { data.copyOfRange(it, it + CH_SIZE) }

    /** CHIRP set_memory for an analog channel on a slot that holds an analog channel or nothing. */
    fun put(n: Int, channel: Channel, isNew: Boolean) {
        val off = Dm32uvLayout.channelOffset(n)
        if (isNew) Dm32uvCodec.TEMPLATE.copyInto(data, off)
        if (n > channelCount) {
            // Channels skipped over become part of 1..count: make them empty, not left-over data.
            for (gap in channelCount + 1 until n) Dm32uvLayout.channelOffset(gap).let { data.fill(0xFF.toByte(), it, it + CH_SIZE) }
            channelCount = n
        }
        Dm32uvCodec.encode(channel, record(n), isNew).copyInto(data, off)
        updateTxContact(n, txContact(n), digital = false)
    }

    /** CHIRP set_memory for an empty memory. */
    fun delete(n: Int) {
        val off = Dm32uvLayout.channelOffset(n)
        data.fill(0xFF.toByte(), off, off + CH_SIZE)
        updateTxContact(n, 0, digital = false)
        // Zones must not point at a channel that no longer exists.
        for (z in 1..zoneCount) {
            val members = zoneMembers(z)
            if (n in members) setZoneMembers(z, members.filter { it != n })
        }
        dropEmptyLastZone()
        removeFromScanLists(n)
        if (n == channelCount) {
            var count = n - 1
            while (count > 0 && Dm32uvCodec.isBlank(data, Dm32uvLayout.channelOffset(count))) count--
            channelCount = count
        }
    }

    // --- TX contacts ----------------------------------------------------------------------

    private fun txContact(n: Int): Int {
        val off = Dm32uvLayout.txContactOffset(n)
        val value = ((data.u8(off) ushr 4) shl 8) or data.u8(off + 1)
        return if (value > CONTACT_MAX) 0 else value
    }

    /** Writes the TX contact entry only if something changes, so no page is created for nothing. */
    private fun updateTxContact(n: Int, contact: Int, digital: Boolean) {
        val off = Dm32uvLayout.txContactOffset(n)
        val hi = data.u8(off)
        val lo = data.u8(off + 1)
        if (hi == 0xFF && lo == 0xFF && contact == 0) return
        if (txContact(n) != contact || (hi and 1) != (if (digital) 1 else 0)) {
            val keep = if (hi == 0xFF) 0 else hi and 0x0E
            put(off, byteArrayOf((((contact ushr 8) shl 4) or keep or (if (digital) 1 else 0)).toByte(), contact.toByte()))
        }
    }

    // --- zones ----------------------------------------------------------------------------

    val zoneCount: Int get() = data.u8(Dm32uvLayout.ZONE_BASE).let { if (it > ZONE_COUNT) 0 else it }

    fun zoneMembers(z: Int): List<Int> {
        if (z > zoneCount) return emptyList()
        val off = Dm32uvLayout.zoneOffset(z)
        val count = minOf(data.u8(off + 16), ZONE_MEMBERS)
        return (0 until count).map { data.u16le(off + 17 + 2 * it) }.filter { it in 1..CH_COUNT }
    }

    fun zoneName(z: Int): List<Byte> = data.textPrefix(Dm32uvLayout.zoneOffset(z), 16).toList()

    /** Zones 1..count as CHIRP's upload compares them (_zone_list). */
    fun zones(): List<Zone> = (1..zoneCount).map { Zone(zoneName(it), zoneMembers(it)) }

    /** A zone as the radio holds it, unfiltered (CHIRP _zone_record). */
    fun rawZone(z: Int): Zone = zoneRecord(data, Dm32uvLayout.zoneOffset(z))

    private fun setZoneMembers(z: Int, members: List<Int>) {
        val off = Dm32uvLayout.zoneOffset(z)
        for (i in 0 until ZONE_MEMBERS) data.putU16le(off + 17 + 2 * i, members.getOrElse(i) { 0 })
        data[off + 16] = members.size.toByte()
    }

    private fun dropEmptyLastZone() {
        val count = zoneCount
        if (count > 1 && zoneMembers(count).isEmpty()) {
            Dm32uvLayout.zoneOffset(count).let { data.fill(0xFF.toByte(), it, it + 16) }
            data[Dm32uvLayout.ZONE_BASE] = (count - 1).toByte()
        }
    }

    /**
     * Adds channel [n] to the zone named "Offset", creating it after the last zone when absent
     * and overflowing into "Offset 2", "Offset 3" and so on when a zone is full. Returns false,
     * leaving the channel in no zone, only when all 250 zones exist and none has room.
     */
    fun addToOffsetZone(n: Int): Boolean {
        var k = 1
        while (true) {
            val name = (if (k == 1) OFFSET_ZONE else "$OFFSET_ZONE $k").toByteArray(Charsets.ISO_8859_1)
            var z = (1..zoneCount).firstOrNull { zoneName(it) == name.toList() }
            if (z == null) {
                if (zoneCount >= ZONE_COUNT) return false
                z = zoneCount + 1
                createZone(z, name)
            }
            val members = zoneMembers(z)
            if (n in members) return true
            if (members.size < ZONE_MEMBERS) {
                setZoneMembers(z, members + n)
                return true
            }
            k++
        }
    }

    /** CHIRP _create_zone followed by a rename: zone [z] (count + 1) becomes an empty named zone. */
    private fun createZone(z: Int, name: ByteArray) {
        val off = Dm32uvLayout.zoneOffset(z)
        for (i in 0 until 16) data[off + i] = name.getOrElse(i) { 0 }
        setZoneMembers(z, emptyList())
        data[Dm32uvLayout.ZONE_BASE] = z.toByte()
    }

    // --- scan lists -----------------------------------------------------------------------

    /** Scan list members as (list name, channels), for comparing two codeplugs. */
    fun scanListMembers(): List<Pair<List<Byte>, List<Int>>> = scanLists().map { it.name to it.members }

    private fun scanLists(): List<ScanList> {
        val base = Dm32uvLayout.SCAN_BASE
        val count = data.u8(base).let { if (it <= SCAN_MAX) it else 0 }
        return (1..count).map { n ->
            val rec = Dm32uvLayout.scanListOffset(n)
            val k = minOf(data.u8(rec + 0x0B), SCAN_MEMBERS)
            ScanList(
                data.textPrefix(rec, SCAN_NAME).toList(),
                (0 until k).map { data.u16le(rec + 0x18 + 2 * it) }.filter { it in 1..CH_COUNT },
                data.copyOfRange(rec + 0x0C, rec + 0x18).toList(),
            )
        }
    }

    private fun setScanLists(lists: List<ScanList>) {
        val default = lists.firstOrNull()?.opts ?: Dm32uvLayout.SCAN_DEFAULT_OPTS.toList()
        val oldCount = scanLists().size
        val tagBase = Dm32uvLayout.SCAN_BASE
        put(tagBase, byteArrayOf(lists.size.toByte()))
        for (n in 1..SCAN_MAX) {
            val base = Dm32uvLayout.scanListOffset(n)
            if (n > lists.size) {
                if (n <= oldCount) put(base, ByteArray(SCAN_REC))
                continue
            }
            val list = lists[n - 1]
            val opts = (if (list.opts.size == 12) list.opts else default).toByteArray()
            val members = list.members.take(SCAN_MEMBERS)
            val designed = opts.u16le(3)
            if (members.isNotEmpty() && designed !in members) opts.putU16le(3, members[0])
            val rec = ByteArray(SCAN_REC - 1)
            list.name.take(SCAN_NAME).forEachIndexed { i, b -> rec[i] = b }
            rec[SCAN_NAME] = members.size.toByte()
            opts.copyInto(rec, SCAN_NAME + 1)
            members.forEachIndexed { i, m -> rec.putU16le(0x18 + 2 * i, m) }
            put(base, rec)
        }
    }

    private fun removeFromScanLists(n: Int) {
        val lists = scanLists()
        val updated = lists.map { list ->
            val members = list.members.filter { it != n }
            val opts = list.opts.toByteArray()
            if (members.isEmpty() && opts.u16le(3) == n) {
                // The designed channel; the factory lists use channel 1.
                Dm32uvLayout.SCAN_DEFAULT_OPTS.copyInto(opts, 3, 3, 5)
            }
            ScanList(list.name, members, opts.toList())
        }
        if (updated != lists) setScanLists(updated)
    }

    /** CHIRP _put: raw bytes into a page, which then exists (its tag byte is set). */
    private fun put(imageOffset: Int, bytes: ByteArray) {
        val slotStart = imageOffset - imageOffset % PAGE
        require(imageOffset + bytes.size <= slotStart + PAGE - 1)
        bytes.copyInto(data, imageOffset)
        data[slotStart + PAGE - 1] = Dm32uvLayout.IMAGE_TAGS[slotStart / PAGE].toByte()
    }

    companion object {
        const val OFFSET_ZONE = "Offset"

        /** The 0x91 byte zone record at [off] in [bytes], members unfiltered (CHIRP _zone_record). */
        fun zoneRecord(bytes: ByteArray, off: Int): Zone {
            val count = minOf(bytes.u8(off + 16), ZONE_MEMBERS)
            return Zone(bytes.textPrefix(off, 16).toList(), (0 until count).map { bytes.u16le(off + 17 + 2 * it) })
        }
        private const val CONTACT_MAX = 800
    }
}
