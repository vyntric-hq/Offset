/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol from Hertzz58/Radtel-RT950-Pro-Firmware (tools/cps_flash.py), GPL-3.0.
 * Segment order and session flow from NathanBarguss/Chirp_Radtel-RT-950-Pro
 * (rt950pro/transport.py), MIT, Copyright (c) 2025 Nathan G. Barguss, 2E0NBS.
 */
package com.vyntric.offset.protocol

import java.io.IOException
import kotlin.random.Random

/**
 * One RT-950 Pro clone session at 115200 8N1.
 *
 * Handshake: send `PROGRAMBT9000U`, expect ACK (0x06); send `F`, receive 16 bytes; send `M`,
 * receive the 12 byte model (`RT-950`, null padded); send a 25 byte key frame
 * ([Rt950ProCipher.newFrame]), expect ACK. Read: send `[cmd, hi, lo, 0x80]`, receive the same 4
 * byte header and 128 encrypted bytes. Write: send `[cmd, hi, lo, 0x80]` and 128 encrypted bytes,
 * expect ACK. The session ends with `E`. Each session either reads or writes, never both, as in
 * the flows tested on hardware.
 */
class Rt950ProClone(
    private val port: SerialPort,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val onProgress: (CloneProgress) -> Unit = {},
    private val random: Random = Random.Default,
) : RadioClone {
    private var key: ByteArray? = null

    /** Reads every segment (rule 1: always the whole image) and ends the session. */
    override fun download(phase: CloneProgress.Phase): Rt950ProImage = io {
        handshake()
        val image = ByteArray(Rt950ProImage.SIZE)
        var done = 0
        for (segment in Rt950ProImage.SEGMENTS) {
            for (block in 0 until segment.blocks) {
                val offset = block * Rt950ProImage.BLOCK
                readBlock(segment.readCommand, segment.address + offset)
                    .copyInto(image, segment.imageOffset + offset)
                done += Rt950ProImage.BLOCK
                onProgress(CloneProgress(phase, done, Rt950ProImage.SIZE))
            }
        }
        end()
        Rt950ProImage.of(image)
    }

    /**
     * Writes the whole channel segment of [target] (all 240 blocks, in address order, as the
     * hardware tested flow does) and ends the session. Returns the number of blocks written.
     *
     * Nothing outside the channel segment is ever sent. Refuses before writing when the radio
     * does not report an RT-950 model, or when [target] differs from [before] outside the
     * channels. This session does not read the radio first (reading and writing in one session
     * is untested on hardware), so the caller's full read made moments before (rule 1) is the
     * only check that the radio's channels still match [before]; the model string names a
     * model, not a unit.
     */
    override fun upload(target: RadioImage, before: RadioImage): Int {
        require(target is Rt950ProImage && before is Rt950ProImage) { "Not an RT-950 Pro image" }
        if (!before.differsOnlyInWritableRegions(target)) {
            throw RadioException.Unsupported("Offset only writes channels, and this image also changes settings. Nothing was written.")
        }
        return io {
            handshake()
            val bytes = target.bytes()
            val segment = Rt950ProImage.CHANNELS
            onProgress(CloneProgress(CloneProgress.Phase.WRITING, 0, segment.length))
            for (block in 0 until segment.blocks) {
                val offset = block * Rt950ProImage.BLOCK
                val data = bytes.copyOfRange(segment.imageOffset + offset, segment.imageOffset + offset + Rt950ProImage.BLOCK)
                writeBlock(segment.writeCommand, segment.address + offset, data)
                onProgress(CloneProgress(CloneProgress.Phase.WRITING, offset + Rt950ProImage.BLOCK, segment.length))
            }
            end()
            segment.blocks
        }
    }

    /** Opens the session and agrees the XOR key. Returns the model string. */
    private fun handshake(): String {
        onProgress(CloneProgress(CloneProgress.Phase.IDENTIFYING, 0, 0))
        port.purge()
        port.write(HANDSHAKE)
        val ack = port.read(1, IDENT_TIMEOUT_MS)
        if (ack.isEmpty()) throw RadioException.NoContact()
        if (ack[0] != ACK) throw RadioException.Protocol("The radio refused clone mode.")
        port.write(byteArrayOf('F'.code.toByte()))
        readExact(IDENTITY_SIZE, IDENT_TIMEOUT_MS) { HANDSHAKE_STOPPED }
        port.write(byteArrayOf('M'.code.toByte()))
        val model = String(readExact(MODEL_SIZE, IDENT_TIMEOUT_MS) { HANDSHAKE_STOPPED }, Charsets.ISO_8859_1).trim('\u0000', ' ')
        if (!model.startsWith(MODEL_PREFIX)) {
            throw RadioException.IdentMismatch(
                "This radio reports itself as \"$model\", not an RT-950 Pro. Nothing was read or written."
            )
        }
        val frame = Rt950ProCipher.newFrame(random)
        val sessionKey = checkNotNull(Rt950ProCipher.keyFor(frame)) { "Built a malformed key frame" }
        port.write(frame)
        val keyAck = port.read(1, IDENT_TIMEOUT_MS)
        if (keyAck.isEmpty()) throw RadioException.Timeout(HANDSHAKE_STOPPED)
        if (keyAck[0] != ACK) throw RadioException.Protocol("The radio did not accept the session key.")
        key = sessionKey
        return model
    }

    private fun readBlock(command: Int, address: Int): ByteArray {
        val header = header(command, address)
        port.write(header)
        val reply = readExact(header.size + Rt950ProImage.BLOCK, BLOCK_TIMEOUT_MS) {
            "The radio stopped responding at block 0x%04X.".format(address)
        }
        // The radio echoes the request header. The command byte is not checked: for the APRS
        // block (0x54) no source documents what the radio echoes.
        if ((1 until header.size).any { reply[it] != header[it] }) {
            throw RadioException.Protocol("Unexpected reply for block 0x%04X.".format(address))
        }
        return Rt950ProCipher.apply(reply.copyOfRange(header.size, reply.size), sessionKey())
    }

    private fun writeBlock(command: Int, address: Int, data: ByteArray) {
        port.write(header(command, address) + Rt950ProCipher.apply(data, sessionKey()))
        val ack = port.read(1, BLOCK_TIMEOUT_MS)
        if (ack.isEmpty()) throw RadioException.Timeout("The radio did not confirm block 0x%04X.".format(address))
        if (ack[0] != ACK) throw RadioException.Protocol("The radio refused block 0x%04X.".format(address))
    }

    private fun end() {
        port.write(byteArrayOf('E'.code.toByte()))
        sleeper.sleep(END_DELAY_MS)
        key = null
    }

    /** Reads exactly [count] bytes or throws [RadioException.Timeout] with [message]. */
    private fun readExact(count: Int, timeoutMs: Long, message: () -> String): ByteArray {
        var buf = ByteArray(0)
        while (buf.size < count) {
            val chunk = port.read(count - buf.size, timeoutMs)
            if (chunk.isEmpty()) throw RadioException.Timeout(message())
            buf += chunk
        }
        return buf
    }

    private fun sessionKey(): ByteArray = checkNotNull(key) { "No clone session" }

    private fun header(command: Int, address: Int) =
        byteArrayOf(command.toByte(), (address ushr 8).toByte(), address.toByte(), Rt950ProImage.BLOCK.toByte())

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: RadioException) {
        throw e
    } catch (e: IOException) {
        throw RadioException.Disconnected(e)
    }

    companion object {
        const val BAUD_RATE = 115_200
        const val ACK: Byte = 0x06
        const val IDENTITY_SIZE = 16
        const val MODEL_SIZE = 12
        const val MODEL_PREFIX = "RT-950"
        const val IDENT_TIMEOUT_MS = 1_500L
        const val BLOCK_TIMEOUT_MS = 3_000L
        const val END_DELAY_MS = 100L
        private const val HANDSHAKE_STOPPED = "The radio stopped answering during the handshake."
        val HANDSHAKE: ByteArray = "PROGRAMBT9000U".toByteArray(Charsets.US_ASCII)
    }
}
