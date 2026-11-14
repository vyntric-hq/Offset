/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py), GPL.
 */
package com.vyntric.offset.protocol

/**
 * Where things live in a DM-32UV image.
 *
 * The radio keeps its codeplug in 4 KB flash pages whose last byte is a tag naming the contents.
 * The image (CHIRP's, byte for byte) is logical: one 4 KB slot per tag in [IMAGE_TAGS], in that
 * order, each holding the page as read (tag byte included), or all 0xFF when the radio has no
 * page with that tag.
 */
internal object Dm32uvLayout {
    const val PAGE = 0x1000

    /** The tags the vendor CPS reads (0x12 to 0x41 are the channel pages). */
    val IMAGE_TAGS: List<Int> =
        listOf(0x02, 0x03, 0x04, 0x06, 0x0A, 0x0B, 0x0F, 0x10, 0x11) + (0x12 until 0x49) + (0x5C until 0x68)

    val IMAGE_SIZE: Int = IMAGE_TAGS.size * PAGE

    /** The PSEARCH reply of the DM-32UV. */
    const val MODEL_ID = "DP570UV"

    const val CH_TAG0 = 0x12
    const val CH_SIZE = 0x30
    const val CH_PER_PAGE = 85
    const val CH_COUNT = 4000

    const val SCAN_TAG = 0x11
    const val SCAN_MAX = 32
    const val SCAN_REC = 0x39
    const val SCAN_NAME = 11
    const val SCAN_MEMBERS = 16

    /** Options of the radio's factory scan lists; bytes 3 and 4 are the designed channel. */
    val SCAN_DEFAULT_OPTS: ByteArray = hex("0306000100000000000a007f")

    val TXC_TAGS = listOf(0x42, 0x43)

    const val ZONE_TAG0 = 0x5C
    const val ZONE_PER_PAGE = 28
    const val ZONE_COUNT = 250
    const val ZONE_MEMBERS = 64
    const val ZONE_REC = 0x91

    /** Tags that look like calibration. Nothing ever writes them. */
    val NEVER_WRITE = setOf(0x02, 0x69)

    /**
     * CHIRP's UPLOAD_TAGS, in its write order. Upload refuses when any of them has more than one
     * page on the radio, as CHIRP does.
     */
    val CHIRP_UPLOAD_TAGS: List<Int> =
        listOf(0x03, 0x04, 0x06, 0x67) + (0x44 until 0x49) + listOf(0x0B, 0x0F, SCAN_TAG) +
            (0x12 until 0x42) + TXC_TAGS + (0x5C until 0x65)

    /**
     * The pages Offset may write (rule 3), in CHIRP's write order: the scan list page, the channel
     * pages (with the channel count), the TX contact pages, then the zone pages.
     */
    val WRITABLE_TAGS: List<Int> = listOf(SCAN_TAG) + (CH_TAG0 until 0x42) + TXC_TAGS + (ZONE_TAG0 until 0x65)

    private val slotIndex: Map<Int, Int> = IMAGE_TAGS.withIndex().associate { (i, t) -> t to i }

    /** Image offset of the slot holding [tag]. */
    fun slot(tag: Int): Int = PAGE * requireNotNull(slotIndex[tag]) { "Tag %02X is not in the image".format(tag) }

    val CH_BASE: Int = slot(CH_TAG0)
    val ZONE_BASE: Int = slot(ZONE_TAG0)
    val SCAN_BASE: Int = slot(SCAN_TAG)

    /** Image offset of channel [n] (1 based), as the CPS computes it (0x47dcc0). */
    fun channelOffset(n: Int): Int {
        require(n in 1..CH_COUNT) { "Channel $n out of range" }
        return if (n < CH_PER_PAGE) CH_BASE + 0x10 + (n - 1) * CH_SIZE
        else CH_BASE + (n / CH_PER_PAGE) * PAGE + (n % CH_PER_PAGE) * CH_SIZE
    }

    /** Image offset of zone [z] (1 based), as the CPS computes it (0x482830). */
    fun zoneOffset(z: Int): Int {
        require(z in 1..ZONE_COUNT) { "Zone $z out of range" }
        val page = (z - 1) / ZONE_PER_PAGE
        return ZONE_BASE + page * PAGE + (if (page == 0) 0x10 else 0) + ((z - 1) % ZONE_PER_PAGE) * ZONE_REC
    }

    /** Image offset of channel [n]'s 2 byte TX contact entry (CPS 0x480050). */
    fun txContactOffset(n: Int): Int =
        if (n < 0x800) slot(TXC_TAGS[0]) + 2 * (n - 1) else slot(TXC_TAGS[1]) + 2 * (n and 0x7FF)

    /** Image offset of scan list [n] (1 based). */
    fun scanListOffset(n: Int): Int = SCAN_BASE + SCAN_REC * n - 0x38

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    fun ByteArray.putU16le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    fun ByteArray.isAll(value: Int, from: Int, to: Int): Boolean =
        (from until to).all { this[it] == value.toByte() }

    /** The bytes before the first 0x00 or 0xFF (CHIRP's split at NUL, then at 0xFF). */
    fun ByteArray.textPrefix(from: Int, length: Int): ByteArray {
        var end = from
        while (end < from + length && this[end] != 0.toByte() && this[end] != 0xFF.toByte()) end++
        return copyOfRange(from, end)
    }
}
