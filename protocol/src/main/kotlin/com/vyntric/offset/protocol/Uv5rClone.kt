/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Clone protocol ported from CHIRP (chirp/drivers/uv5r.py _do_ident, _read_block,
 * _do_download, _send_block, _do_upload), GPL, and cross checked against baoclone
 * (sergev/baoclone uv-5r.c read_block, write_block), BSD.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * The UV-5R clone protocol, one session per instance.
 *
 * Handshake: send a 7 byte magic one byte at a time, expect ACK (0x06), send 0x02, receive an
 * 8 byte ident (12 bytes on some UV-6 style firmware, folded to 8), send ACK, expect ACK.
 * Read: send `'S' addrHi addrLo size`, receive `'X' addrHi addrLo size` + data, send ACK.
 * The radio ACKs that ACK; CHIRP consumes it before the next block header and so do we.
 * Write: send `'X' addrHi addrLo size` + data, expect ACK.
 */
class Uv5rClone(
    private val port: SerialPort,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val onProgress: (CloneProgress) -> Unit = {},
) {
    private var firstRead = true

    /** Performs the handshake, trying each known magic in turn. Returns the 8 byte ident. */
    fun identify(): ByteArray = io {
        onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, 0, 0))
        var lastError: RadioException? = null
        for ((i, magic) in MAGICS.withIndex()) {
            if (i > 0) sleeper.sleep(RETRY_DELAY_MS)
            try {
                return@io handshake(magic)
            } catch (e: RadioException.NoContact) {
                lastError = e
            } catch (e: RadioException.Protocol) {
                lastError = e
            }
        }
        throw lastError ?: RadioException.NoContact()
    }

    /** Downloads the full image (ident, main block, aux block). Rule 1: always the whole image. */
    fun download(phase: CloneProgress.Phase = CloneProgress.Phase.READING): RadioImage = io {
        val ident = identify()
        val (_, droppedByte) = readFirmwareProbe()
        val total = RadioImage.MAIN_SIZE + RadioImage.AUX_SIZE
        var done = 0
        val main = ByteArray(RadioImage.MAIN_SIZE)
        for (addr in 0 until RadioImage.MAIN_SIZE step READ_BLOCK) {
            readBlock(addr, READ_BLOCK).copyInto(main, addr)
            done += READ_BLOCK
            onProgress(CloneProgress(phase, done, total))
        }
        val aux = ByteArray(RadioImage.AUX_SIZE)
        val auxStart = RadioImage.AUX_RADIO_START
        val tailStart = 0x1FC0
        var addr = auxStart
        while (addr < RadioImage.RADIO_END) {
            // Newer radios drop byte 0x1FCF when the last 0x40 bytes are read as one block.
            val size = if (droppedByte && addr >= tailStart) 0x10 else READ_BLOCK
            readBlock(addr, size).copyInto(aux, addr - auxStart)
            addr += size
            done += size
            onProgress(CloneProgress(phase, done, total))
        }
        RadioImage.assemble(ident, main, aux)
    }

    /**
     * Writes the 16 byte blocks at [imageOffsets] from [image] to the radio.
     *
     * Guards (defense in depth, the use case checks these too):
     * - the radio must return exactly the image's ident;
     * - the radio firmware string must equal the image's;
     * - every block must be inside the channel or name region;
     * - when [expectedBefore] is given, the radio's channel and name memory must still equal it.
     *   The ident names a model, not a unit, so this is what catches a second radio of the same
     *   model being connected between the read and the write.
     */
    fun upload(image: RadioImage, imageOffsets: List<Int>, expectedBefore: RadioImage? = null) = io {
        val bad = imageOffsets.filterNot { ImageRegions.isWritableBlock(it) }
        if (bad.isNotEmpty()) {
            throw RadioException.Unsupported("Refusing to write outside the channel and name regions")
        }
        val ident = identify()
        if (!ident.contentEquals(image.ident)) {
            throw RadioException.IdentMismatch(
                "This image belongs to a different radio (image ${image.identHex}, radio ${ident.toHex()})."
            )
        }
        val (radioFirmware, _) = readFirmwareProbe()
        val unchanged = expectedBefore?.let { matchesWritableRegions(it) } ?: true
        // The radio ACKs the ACK of the last block read; consume it so block ACKs stay aligned.
        port.read(1, TRAILING_ACK_TIMEOUT_MS)
        if (!unchanged) {
            throw RadioException.IdentMismatch(
                "The radio's channels changed since it was read. Is a different radio connected? Nothing was written."
            )
        }
        val imageFirmware = image.firmwareVersion
        if (imageFirmware != null && normalizeFw(radioFirmware) != imageFirmware) {
            throw RadioException.IdentMismatch(
                "Firmware differs (image \"$imageFirmware\", radio \"${normalizeFw(radioFirmware)}\")."
            )
        }
        val bytes = image.bytes()
        val total = imageOffsets.size * ImageRegions.BLOCK
        onProgress(CloneProgress(CloneProgress.Phase.WRITING, 0, total))
        imageOffsets.sorted().forEachIndexed { i, off ->
            sendBlock(RadioImage.radioAddress(off), bytes.copyOfRange(off, off + ImageRegions.BLOCK))
            onProgress(CloneProgress(CloneProgress.Phase.WRITING, (i + 1) * ImageRegions.BLOCK, total))
        }
    }

    private fun handshake(magic: ByteArray): ByteArray {
        port.purge()
        for (b in magic) {
            port.write(byteArrayOf(b))
            sleeper.sleep(10)
        }
        val ack = port.read(1, IDENT_TIMEOUT_MS)
        if (ack.isEmpty() || ack[0] != ACK) throw RadioException.NoContact()
        port.write(byteArrayOf(0x02))
        val response = ArrayList<Byte>(12)
        while (response.size < 12) {
            val b = port.read(1, IDENT_TIMEOUT_MS)
            if (b.isEmpty()) break
            response += b[0]
            if (b[0] == 0xDD.toByte()) break
        }
        val ident = when (response.size) {
            8 -> response.toByteArray()
            // UV-6 style 12 byte idents: keep bytes 0, 3, 5 and 7..11 (CHIRP _do_ident).
            12 -> byteArrayOf(response[0], response[3], response[5]) + response.subList(7, 12).toByteArray()
            else -> throw RadioException.Protocol("Unexpected ident from radio (${response.size} bytes).")
        }
        port.write(byteArrayOf(ACK))
        val ack2 = port.read(1, IDENT_TIMEOUT_MS)
        if (ack2.isEmpty() || ack2[0] != ACK) throw RadioException.Protocol("Radio refused clone mode.")
        firstRead = true
        return ident
    }

    /**
     * Reads the aux area the way CHIRP does before a download: one block outside aux first
     * (new firmware returns bad aux data otherwise), then the firmware block and the tail block.
     * Returns the firmware string and whether this radio has the dropped byte quirk.
     */
    private fun readFirmwareProbe(): Pair<String, Boolean> {
        readBlock(0x1E80, READ_BLOCK)
        val block1 = readBlock(0x1EC0, READ_BLOCK)
        val block2 = readBlock(0x1FC0, READ_BLOCK)
        val version = String(block1.copyOfRange(48, 62), Charsets.ISO_8859_1)
        return version to (block2[15] == 0xFF.toByte())
    }

    /** Reads the channel and name memory and compares it with [expected]. */
    private fun matchesWritableRegions(expected: RadioImage): Boolean {
        val bytes = expected.bytes()
        for (region in ImageRegions.WRITABLE) {
            var off = region.first
            while (off <= region.last) {
                val live = readBlock(RadioImage.radioAddress(off), READ_BLOCK)
                if (!live.contentEquals(bytes.copyOfRange(off, off + READ_BLOCK))) return false
                off += READ_BLOCK
            }
        }
        return true
    }

    private fun readBlock(addr: Int, size: Int): ByteArray {
        port.write(byteArrayOf('S'.code.toByte(), (addr ushr 8).toByte(), addr.toByte(), size.toByte()))
        var header = port.read(4, BLOCK_TIMEOUT_MS)
        // After the first block the radio ACKs our ACK first. baoclone notes the BF-F8HP can
        // deliver that ACK late, so skip a leading ACK whenever one appears.
        if (header.isNotEmpty() && header[0] == ACK) {
            header = header.copyOfRange(1, header.size) + port.read(1, BLOCK_TIMEOUT_MS)
        }
        // A missing ACK is tolerated when a valid header follows; the header check below decides.
        firstRead = false
        if (header.size != 4) throw RadioException.Timeout("Radio stopped responding at block 0x%04X.".format(addr))
        val respAddr = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
        if (header[0] != 'X'.code.toByte() || respAddr != addr || (header[3].toInt() and 0xFF) != size) {
            throw RadioException.Protocol("Unexpected reply for block 0x%04X.".format(addr))
        }
        val chunk = port.read(size, BLOCK_TIMEOUT_MS)
        if (chunk.size != size) {
            throw RadioException.Timeout("Radio sent an incomplete block 0x%04X (%d of %d bytes).".format(addr, chunk.size, size))
        }
        port.write(byteArrayOf(ACK))
        sleeper.sleep(50)
        return chunk
    }

    private fun sendBlock(addr: Int, data: ByteArray) {
        port.write(byteArrayOf('X'.code.toByte(), (addr ushr 8).toByte(), addr.toByte(), data.size.toByte()) + data)
        sleeper.sleep(50)
        val ack = port.read(1, BLOCK_TIMEOUT_MS)
        if (ack.isEmpty()) throw RadioException.Timeout("Radio did not confirm block 0x%04X.".format(addr))
        if (ack[0] != ACK) throw RadioException.Protocol("Radio refused block 0x%04X.".format(addr))
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: RadioException) {
        throw e
    } catch (e: IOException) {
        throw RadioException.Disconnected(e)
    }

    private fun normalizeFw(raw: String): String =
        raw.map { if (it.code in 0x20..0x7E) it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    companion object {
        const val ACK: Byte = 0x06
        const val READ_BLOCK = 0x40
        const val IDENT_TIMEOUT_MS = 1_000L
        const val BLOCK_TIMEOUT_MS = 1_500L
        const val RETRY_DELAY_MS = 2_000L
        const val TRAILING_ACK_TIMEOUT_MS = 300L
        const val BAUD_RATE = 9600

        /** UV5R_MODEL_291 (BFB291 and later), UV5R_MODEL_ORIG, UV5R_MODEL_A58 (BF-F8HP). */
        val MAGIC_291 = bytes(0x50, 0xBB, 0xFF, 0x20, 0x12, 0x07, 0x25)
        val MAGIC_ORIG = bytes(0x50, 0xBB, 0xFF, 0x01, 0x25, 0x98, 0x4D)
        val MAGIC_A58 = bytes(0x50, 0xBB, 0xFF, 0x20, 0x14, 0x04, 0x13)
        val MAGICS = listOf(MAGIC_291, MAGIC_ORIG, MAGIC_A58)

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}
