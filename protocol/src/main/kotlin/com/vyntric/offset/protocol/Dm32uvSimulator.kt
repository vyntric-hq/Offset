/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol as described by CHIRP (chirp/drivers/baofeng_dm32uv.py), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.PAGE
import com.vyntric.offset.protocol.Dm32uvLayout.u16le
import com.vyntric.offset.protocol.Dm32uvLayout.u8
import java.io.IOException

/**
 * A DM-32UV that lives in memory and speaks the clone protocol (see [Dm32uvClone]).
 *
 * Its flash is [pageCount] pages from [flashStart]. Each page of an image is placed at a
 * scattered address, leaving free pages in between, so finding pages by their tag is exercised.
 * Every host write is processed synchronously; reads never block, so an empty queue behaves like
 * a serial timeout.
 *
 * Faults: [silent] (radio off), [detachAfterHostBytes] (cable pulled), [corruptWriteTags] (the
 * radio stores different bytes than it acknowledged), [garbleTagReads] (a CH340 cable setting bit
 * 7), [ignoredSearches], the password flags, a different [modelId], and [addDuplicatePage].
 */
class Dm32uvSimulator private constructor(
    private val flash: ByteArray,
    val flashStart: Int,
    private val firmware: String,
    private val modelId: String,
) : SimulatedDevice {
    private val out = ArrayDeque<Byte>()
    private val pending = ArrayList<Byte>()
    private var hostBytes = 0L
    private var detached = false
    private var stage = Stage.IDLE
    private var searchesIgnored = 0

    val pageCount: Int get() = flash.size / PAGE
    val flashEnd: Int get() = flashStart + flash.size - 1

    var silent: Boolean = false
    var detachAfterHostBytes: Long? = null
    var corruptWriteTags: Set<Int> = emptySet()
    var garbleTagReads: Boolean = false
    var readPassword: Boolean = false
    var writePassword: Boolean = false

    /** PSEARCH commands ignored at the start of each session, as real radios sometimes do. */
    var ignoredSearches: Int = 0

    /** Bytes received from the host since construction; use it to place [detachAfterHostBytes]. */
    val hostBytesReceived: Long get() = hostBytes

    /** Pages the host has written since construction. */
    var pagesWritten: Int = 0
        private set

    private enum class Stage { IDLE, PROGRAM_ACKED, IDENT_SENT, PROGRAMMING }

    @Synchronized
    override fun write(data: ByteArray) {
        if (detached) throw IOException("USB device detached")
        for (b in data) {
            hostBytes++
            val limit = detachAfterHostBytes
            if (limit != null && hostBytes > limit) {
                detached = true
                throw IOException("USB device detached")
            }
            if (!silent) accept(b)
        }
    }

    @Synchronized
    override fun read(count: Int, timeoutMs: Long): ByteArray {
        if (detached) throw IOException("USB device detached")
        val n = minOf(count, out.size)
        return ByteArray(n) { out.removeFirst() }
    }

    @Synchronized
    override fun purge() {
        if (detached) throw IOException("USB device detached")
        out.clear()
    }

    @Synchronized
    override fun endSession() {
        stage = Stage.IDLE
        searchesIgnored = 0
        pending.clear()
        out.clear()
    }

    /** Reattaches the cable after an injected detach. */
    @Synchronized
    fun reattach() {
        detached = false
        detachAfterHostBytes = null
        endSession()
    }

    /** Flash addresses of the pages tagged [tag], lowest first. */
    @Synchronized
    fun pageAddresses(tag: Int): List<Int> =
        (0 until pageCount).filter { flash.u8(it * PAGE + PAGE - 1) == tag }.map { flashStart + it * PAGE }

    /** Copies the page tagged [tag] into a free page, as a radio that has not tidied up would hold it. */
    @Synchronized
    fun addDuplicatePage(tag: Int) {
        val src = pageAddresses(tag).first() - flashStart
        val dst = pageAddresses(0xFF).last() - flashStart
        flash.copyInto(flash, dst, src, src + PAGE)
    }

    /** The image a correct download from this radio produces. */
    @Synchronized
    override fun currentImage(): Dm32uvImage {
        val image = ByteArray(Dm32uvLayout.IMAGE_SIZE) { 0xFF.toByte() }
        Dm32uvLayout.IMAGE_TAGS.forEachIndexed { i, tag ->
            pageAddresses(tag).firstOrNull()?.let { flash.copyInto(image, i * PAGE, it - flashStart, it - flashStart + PAGE) }
        }
        return Dm32uvImage.of(image, firmware)
    }

    private fun accept(b: Byte) {
        pending += b
        val p = pending
        val first = p[0].toInt() and 0xFF
        when (first) {
            'P'.code, 'S'.code -> {
                val text = String(p.toByteArray(), Charsets.ISO_8859_1)
                when {
                    text in WORDS -> { pending.clear(); word(text) }
                    WORDS.none { it.startsWith(text) } -> pending.clear()
                }
            }
            'V'.code -> if (p.size == 5) { val idx = p[3].toInt() shl 8 or (p[4].toInt() and 0xFF); pending.clear(); info(idx) }
            'G'.code -> if (p.size == 6) { pending.clear(); reply(byteArrayOf('S'.code.toByte()) + ByteArray(0x105)) }
            0xFF -> if (p.size == 5) pending.clear() // FF FF FF FF 0C: no reply
            0x02 -> {
                pending.clear()
                if (stage == Stage.PROGRAM_ACKED) { reply(modelId.padEnd(8, '\u0000').toByteArray(Charsets.ISO_8859_1)); stage = Stage.IDENT_SENT }
            }
            0x06 -> {
                pending.clear()
                if (stage == Stage.IDENT_SENT) { reply(byteArrayOf(ACK)); stage = Stage.PROGRAMMING }
            }
            'R'.code -> if (p.size == 6) {
                val cmd = p.toByteArray()
                pending.clear()
                if (stage == Stage.PROGRAMMING) readFlash(cmd)
            }
            'W'.code -> if (p.size >= 6) {
                val cmd = p.toByteArray()
                if (p.size == 6 + cmd.u16le(4)) {
                    pending.clear()
                    if (stage == Stage.PROGRAMMING) writeFlash(cmd)
                }
            }
            else -> pending.clear()
        }
    }

    private fun word(text: String) {
        when (text) {
            "PSEARCH" -> if (searchesIgnored < ignoredSearches) searchesIgnored++
            else reply(byteArrayOf(ACK) + modelId.toByteArray(Charsets.ISO_8859_1).copyOf(7))
            "PASSSTA" -> reply(byteArrayOf('P'.code.toByte(), flag(writePassword), flag(readPassword)))
            "SYSINFO" -> reply(byteArrayOf(ACK))
            "PROGRAM" -> { reply(byteArrayOf(ACK)); stage = Stage.PROGRAM_ACKED }
        }
    }

    private fun info(index: Int) {
        val body = when (index) {
            1 -> firmware.toByteArray(Charsets.US_ASCII)
            10 -> le32(flashStart) + le32(flashEnd)
            else -> byteArrayOf(0, 0, 0, index.toByte())
        }
        reply(byteArrayOf('V'.code.toByte(), index.toByte(), body.size.toByte()) + body)
    }

    private fun readFlash(cmd: ByteArray) {
        val addr = cmd.u8(1) or (cmd.u8(2) shl 8) or (cmd.u8(3) shl 16)
        val len = cmd.u16le(4)
        val data = ByteArray(len) { i -> flash.getOrElse(addr - flashStart + i) { 0xFF.toByte() } }
        if (garbleTagReads && len == 1 && (addr + 1) % PAGE == 0) data[0] = (data[0].toInt() or 0x80).toByte()
        reply(byteArrayOf('W'.code.toByte()) + cmd.copyOfRange(1, 6) + data)
    }

    private fun writeFlash(cmd: ByteArray) {
        val addr = cmd.u8(1) or (cmd.u8(2) shl 8) or (cmd.u8(3) shl 16)
        val data = cmd.copyOfRange(6, cmd.size)
        if (data.size == PAGE && data.u8(PAGE - 1) in corruptWriteTags) data[0] = (data[0].toInt() xor 0x01).toByte()
        val off = addr - flashStart
        if (off >= 0 && off + data.size <= flash.size) data.copyInto(flash, off)
        pagesWritten++
        reply(byteArrayOf(ACK))
    }

    private fun reply(bytes: ByteArray) = bytes.forEach { out.addLast(it) }

    companion object {
        private const val ACK: Byte = 0x06
        private val WORDS = setOf("PSEARCH", "PASSSTA", "SYSINFO", "PROGRAM")

        const val DEFAULT_FIRMWARE = "DM32.01.01.047"
        const val DEFAULT_FLASH_START = 0x010000
        const val DEFAULT_PAGE_COUNT = 128

        /**
         * A radio holding [image]: every slot with data (or with its tag byte set) becomes a page
         * tagged with its tag, at scattered addresses.
         */
        fun fromImage(
            image: Dm32uvImage,
            firmware: String = image.firmwareVersion ?: DEFAULT_FIRMWARE,
            modelId: String = Dm32uvLayout.MODEL_ID,
            pageCount: Int = DEFAULT_PAGE_COUNT,
        ): Dm32uvSimulator {
            val bytes = image.bytes()
            val present = Dm32uvLayout.IMAGE_TAGS.withIndex().filter { (i, tag) ->
                val s = i * PAGE
                (s until s + PAGE - 1).any { bytes[it] != 0xFF.toByte() } || bytes.u8(s + PAGE - 1) == tag
            }
            require(pageCount > present.size && pageCount % 37 != 0) { "$pageCount pages cannot hold ${present.size} with one free" }
            val flash = ByteArray(pageCount * PAGE) { 0xFF.toByte() }
            present.forEachIndexed { placed, (i, tag) ->
                // A stride coprime with the page count: a scattered, collision free placement.
                val page = (placed * 37 + 5) % pageCount
                bytes.copyInto(flash, page * PAGE, i * PAGE, (i + 1) * PAGE)
                flash[page * PAGE + PAGE - 1] = tag.toByte()
            }
            return Dm32uvSimulator(flash, DEFAULT_FLASH_START, firmware, modelId)
        }

        private fun flag(set: Boolean): Byte = if (set) 0xA5.toByte() else 0x00

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    }
}
