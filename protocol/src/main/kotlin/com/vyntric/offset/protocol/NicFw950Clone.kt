/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol from nicsure's nicFW880/950 documentation (Serial-Protocol) and RT950Pro BTLE bridge,
 * checked against the NicFW950 RMS app (checksum over address and data, finalize, reboot).
 * Facts only; no code copied.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * One session with an RT-950 Pro running nicFW950, at 38400 8N1 (the speed nicFW always starts at).
 *
 * Every packet starts `AA` and a command; numbers are little endian. Read 128 bytes: send
 * `AA 30 addr[4] 00`, receive `AA 30 addr[4] data[128] sum`. Write: send
 * `AA 40 addr[4] data[128] sum`, receive `AA 40`. The sum is the low byte of the address and data
 * bytes added. After writing, `AA 48` commits to flash (answered `AA 48`) and `AA 50` reboots the
 * radio so the channels take effect. There is no handshake or session end: nicFW answers these
 * packets whenever it is on.
 */
class NicFw950Clone(
    private val port: SerialPort,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val onProgress: (CloneProgress) -> Unit = {},
) : RadioClone {

    /**
     * Reads the whole channel section (rule 1). The first block is retried for a few seconds,
     * since the radio may still be restarting after a write. Refuses a radio that answers like
     * nicFW but holds none of nicFW950's records (nicFW880 on an RT-880).
     */
    override fun download(phase: CloneProgress.Phase): NicFw950Image = io {
        onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, 0, 0))
        port.purge()
        val image = ByteArray(NicFw950Image.SIZE)
        firstBlock().copyInto(image, 0)
        onProgress(CloneProgress(phase, BLOCK, NicFw950Image.SIZE))
        for (offset in BLOCK until NicFw950Image.SIZE step BLOCK) {
            readBlock(offset).copyInto(image, offset)
            onProgress(CloneProgress(phase, offset + BLOCK, NicFw950Image.SIZE))
        }
        val read = NicFw950Image.of(image)
        if (!read.looksLikeNicFw950) {
            throw RadioException.IdentMismatch(
                "This radio answers like nicFW but holds no nicFW950 channels or VFOs. It may be an " +
                    "RT-880 or a different nicFW. Nothing was written."
            )
        }
        read
    }

    /**
     * Writes the 128 byte blocks of the channel records that differ between [target] and
     * [before], commits them and restarts the radio. Before writing, it reads those blocks again
     * and refuses when they no longer match [before] (another radio, or channels changed since
     * the read). Nothing outside the channel records is ever sent. Returns the blocks written.
     */
    override fun upload(target: RadioImage, before: RadioImage): Int {
        require(target is NicFw950Image && before is NicFw950Image) { "Not a nicFW950 image" }
        if (!before.differsOnlyInWritableRegions(target)) {
            throw RadioException.Unsupported("Offset only writes channels, and this image also changes other memory. Nothing was written.")
        }
        if (!before.looksLikeNicFw950) {
            throw RadioException.IdentMismatch("This image is not from a radio running nicFW950. Nothing was written.")
        }
        val want = target.bytes()
        val had = before.bytes()
        val blocks = (0 until NicFw950Image.SIZE step BLOCK).filter { o ->
            (o until o + BLOCK).any { want[it] != had[it] }
        }
        if (blocks.isEmpty()) return 0
        return io {
            onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, 0, 0))
            port.purge()
            // Rule 2 for a protocol without an ident: the blocks about to be written must still
            // hold what was read moments ago.
            blocks.forEachIndexed { i, o ->
                val current = if (i == 0) firstBlock(o) else readBlock(o)
                if (!current.contentEquals(had.copyOfRange(o, o + BLOCK))) {
                    throw RadioException.IdentMismatch(
                        "The radio's channels changed since it was read, or another radio is connected. Nothing was written."
                    )
                }
            }
            val total = blocks.size * BLOCK
            onProgress(CloneProgress(CloneProgress.Phase.WRITING, 0, total))
            blocks.forEachIndexed { i, o ->
                // Each block is read again just before it is written, so a channel saved on the
                // radio's keypad since the check above is not silently overwritten. The radio only
                // writes whole blocks, and the first and last channel blocks also hold VFO or
                // unused bytes; those go back exactly as the radio holds them now.
                val fresh = readBlock(o)
                if ((o until o + BLOCK).any { it in NicFw950Image.WRITABLE && fresh[it - o] != had[it] }) {
                    throw RadioException.Protocol(
                        "A channel changed on the radio while Offset was writing, so the write stopped before " +
                            "saving. The radio may hold some new channels; restore the backup if they look wrong."
                    )
                }
                val data = want.copyOfRange(o, o + BLOCK)
                for (j in 0 until BLOCK) if (o + j !in NicFw950Image.WRITABLE) data[j] = fresh[j]
                writeBlock(o, data)
                onProgress(CloneProgress(CloneProgress.Phase.WRITING, (i + 1) * BLOCK, total))
            }
            port.write(byteArrayOf(SIGNATURE, FINALIZE))
            expectAck(FINALIZE, "The radio did not confirm saving the channels.")
            port.write(byteArrayOf(SIGNATURE, REBOOT))
            // The RMS reads two bytes after a reboot and only reports a mismatch; neither is needed.
            port.read(2, REBOOT_ACK_TIMEOUT_MS)
            sleeper.sleep(REBOOT_DELAY_MS)
            blocks.size
        }
    }

    /** The first read of a session, retried while the radio may be restarting. */
    private fun firstBlock(offset: Int = 0): ByteArray {
        repeat(FIRST_BLOCK_ATTEMPTS) { attempt ->
            val address = NicFw950Image.BASE_ADDRESS + offset
            port.write(readRequest(address))
            val reply = port.read(READ_REPLY, BLOCK_TIMEOUT_MS)
            if (reply.isNotEmpty()) return checkedBlock(reply + readRest(reply.size, address), address)
            if (attempt < FIRST_BLOCK_ATTEMPTS - 1) {
                sleeper.sleep(RETRY_DELAY_MS)
                port.purge()
            }
        }
        throw RadioException.NoContact(NO_CONTACT_HINT)
    }

    private fun readBlock(offset: Int): ByteArray {
        val address = NicFw950Image.BASE_ADDRESS + offset
        port.write(readRequest(address))
        return checkedBlock(readRest(0, address), address)
    }

    /** Reads the rest of a reply of which [have] bytes already arrived. */
    private fun readRest(have: Int, address: Int): ByteArray {
        var buf = ByteArray(0)
        while (have + buf.size < READ_REPLY) {
            val chunk = port.read(READ_REPLY - have - buf.size, BLOCK_TIMEOUT_MS)
            if (chunk.isEmpty()) throw RadioException.Timeout("The radio stopped responding at 0x%05X.".format(address))
            buf += chunk
        }
        return buf
    }

    private fun checkedBlock(reply: ByteArray, address: Int): ByteArray {
        if (reply[0] != SIGNATURE || reply[1] != READ || u32(reply, 2) != address) {
            throw RadioException.Protocol("Unexpected reply for 0x%05X. Is the radio running nicFW950?".format(address))
        }
        if (checksum(reply, 2, READ_REPLY - 1) != reply[READ_REPLY - 1]) {
            throw RadioException.Protocol("The radio's reply for 0x%05X was damaged (checksum).".format(address))
        }
        return reply.copyOfRange(6, 6 + BLOCK)
    }

    private fun writeBlock(offset: Int, data: ByteArray) {
        val address = NicFw950Image.BASE_ADDRESS + offset
        val packet = ByteArray(WRITE_PACKET)
        packet[0] = SIGNATURE
        packet[1] = WRITE
        putU32(packet, 2, address)
        data.copyInto(packet, 6)
        packet[WRITE_PACKET - 1] = checksum(packet, 2, WRITE_PACKET - 1)
        port.write(packet)
        expectAck(WRITE, "The radio did not confirm block 0x%05X.".format(address))
    }

    private fun expectAck(command: Byte, message: String) {
        var ack = ByteArray(0)
        while (ack.size < 2) {
            val chunk = port.read(2 - ack.size, BLOCK_TIMEOUT_MS)
            if (chunk.isEmpty()) throw RadioException.Timeout(message)
            ack += chunk
        }
        if (ack[0] != SIGNATURE || ack[1] != command) {
            throw RadioException.Protocol("The radio answered %02X %02X instead of confirming.".format(ack[0], ack[1]))
        }
    }

    private fun readRequest(address: Int) = ByteArray(READ_REQUEST).also {
        it[0] = SIGNATURE
        it[1] = READ
        putU32(it, 2, address)
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: RadioException) {
        throw e
    } catch (e: IOException) {
        throw RadioException.Disconnected(e)
    }

    companion object {
        const val BAUD_RATE = 38_400
        const val BLOCK = NicFw950Image.BLOCK

        const val SIGNATURE: Byte = 0xAA.toByte()
        const val READ: Byte = 0x30
        const val WRITE: Byte = 0x40
        const val FINALIZE: Byte = 0x48
        const val REBOOT: Byte = 0x50

        /** `AA 30`, the address and one zero byte, as the RMS sends it. */
        const val READ_REQUEST = 7
        const val READ_REPLY = 2 + 4 + BLOCK + 1
        const val WRITE_PACKET = 2 + 4 + BLOCK + 1

        const val BLOCK_TIMEOUT_MS = 3_000L
        const val FIRST_BLOCK_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 1_000L
        const val REBOOT_ACK_TIMEOUT_MS = 500L

        /** Time for nicFW to restart after `AA 50` before the read back. */
        const val REBOOT_DELAY_MS = 4_000L

        private const val NO_CONTACT_HINT =
            "The radio did not answer nicFW's protocol. Check that it is on and runs nicFW950; " +
                "a radio with Radtel's own firmware is programmed as \"RT-950 Pro\" instead."

        /** The low byte of the sum of [b] from [from] until [until]. */
        fun checksum(b: ByteArray, from: Int, until: Int): Byte {
            var sum = 0
            for (i in from until until) sum += b[i].toInt() and 0xFF
            return sum.toByte()
        }

        internal fun u32(b: ByteArray, at: Int): Int =
            (0 until 4).fold(0) { acc, i -> acc or ((b[at + i].toInt() and 0xFF) shl (8 * i)) }

        internal fun putU32(b: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
        }
    }
}
