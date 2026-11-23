/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol from Hertzz58/Radtel-RT950-Pro-Firmware (tools/cps_flash.py), GPL-3.0.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * An RT-950 Pro that lives in memory and speaks the clone protocol: the handshake, the key
 * frame (the key is derived from the frame exactly as the radio does), encrypted reads, decrypted
 * writes and `E`. Every host write is processed synchronously; reads never block, so an empty
 * queue behaves like a serial timeout.
 *
 * Faults: [silent] (radio off or wrong cable), [detachAfterHostBytes] (cable pulled, the next
 * byte throws [IOException]), [corruptWriteAddresses] (the radio stores different bytes than
 * it acknowledged, so only a read back can catch it) and [handshakesIgnoredAfterWrite] (the radio
 * restarts after a write session, as the real one does).
 */
class Rt950ProSimulator(
    image: Rt950ProImage,
    /** What the radio answers to `M`, before null padding to 12 bytes. */
    private val model: String = "RT-950",
) : SimulatedDevice {
    /** The 0x52/0x57 address space; addresses outside every segment read as 0xFF. */
    private val main = ByteArray(0x10000) { 0xFF.toByte() }
    private val aprs = ByteArray(0x100) { 0xFF.toByte() }
    private val out = ArrayDeque<Byte>()
    private val pending = ArrayList<Byte>()
    private var state = State.IDLE
    private var key: ByteArray? = null
    private var hostBytes = 0L
    private var detached = false

    var silent: Boolean = false
    var detachAfterHostBytes: Long? = null
    var corruptWriteAddresses: Set<Int> = emptySet()

    /**
     * After a session that wrote blocks ends, the radio restarts and stays deaf to this many
     * complete handshakes, standing in for the seconds a real RT-950 Pro takes to boot.
     */
    var handshakesIgnoredAfterWrite: Int = 0
    private var restartingFor = 0
    private var wroteThisSession = false

    /** Handshakes the host sent while the radio was restarting. */
    var handshakesIgnored: Int = 0
        private set

    /** Bytes received from the host since construction; use it to place [detachAfterHostBytes]. */
    val hostBytesReceived: Long get() = hostBytes

    /** Number of 128 byte blocks the host has written since construction. */
    var blocksWritten: Int = 0
        private set

    /** Number of sessions the host closed with `E`. */
    var sessionsEnded: Int = 0
        private set

    private enum class State { IDLE, AWAIT_F, AWAIT_M, AWAIT_KEY, CLONE }

    init {
        val bytes = image.bytes()
        // An image saved before 0.7.7 has no zone name segment; that memory stays blank.
        for (s in Rt950ProImage.SEGMENTS.filter { it.imageOffset + it.length <= bytes.size }) {
            bytes.copyInto(space(s.readCommand), s.address, s.imageOffset, s.imageOffset + s.length)
        }
    }

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
        return ByteArray(minOf(count, out.size)) { out.removeFirst() }
    }

    @Synchronized
    override fun purge() {
        if (detached) throw IOException("USB device detached")
        out.clear()
    }

    @Synchronized
    override fun endSession() {
        state = State.IDLE
        key = null
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

    /** The image a correct download from this radio produces. */
    @Synchronized
    override fun currentImage(): Rt950ProImage {
        val bytes = ByteArray(Rt950ProImage.SIZE)
        for (s in Rt950ProImage.SEGMENTS) space(s.readCommand).copyInto(bytes, s.imageOffset, s.address, s.address + s.length)
        return Rt950ProImage.of(bytes)
    }

    private fun accept(b: Byte) {
        when (state) {
            State.IDLE -> {
                pending += b
                val handshake = Rt950ProClone.HANDSHAKE
                if (pending.size > handshake.size || pending.last() != handshake[pending.size - 1]) {
                    // Not a prefix of the handshake: resynchronise on this byte.
                    pending.clear()
                    if (b == handshake[0]) pending += b
                } else if (pending.size == handshake.size) {
                    pending.clear()
                    if (restartingFor > 0) {
                        restartingFor--
                        handshakesIgnored++
                        return
                    }
                    out.addLast(ACK)
                    state = State.AWAIT_F
                }
            }
            State.AWAIT_F -> if (b == 'F'.code.toByte()) {
                IDENTITY.forEach { out.addLast(it) }
                state = State.AWAIT_M
            } else restart(b)
            State.AWAIT_M -> if (b == 'M'.code.toByte()) {
                model.toByteArray(Charsets.US_ASCII).copyOf(Rt950ProClone.MODEL_SIZE).forEach { out.addLast(it) }
                state = State.AWAIT_KEY
            } else restart(b)
            State.AWAIT_KEY -> {
                pending += b
                if (pending.size == Rt950ProCipher.FRAME_SIZE) {
                    key = Rt950ProCipher.keyFor(pending.toByteArray())
                    pending.clear()
                    if (key != null) {
                        out.addLast(ACK)
                        state = State.CLONE
                    } else {
                        state = State.IDLE
                    }
                }
            }
            State.CLONE -> clone(b)
        }
    }

    private fun clone(b: Byte) {
        if (pending.isEmpty() && b == 'E'.code.toByte()) {
            sessionsEnded++
            state = State.IDLE
            key = null
            if (wroteThisSession) restartingFor = handshakesIgnoredAfterWrite
            wroteThisSession = false
            return
        }
        pending += b
        val command = pending[0].toInt() and 0xFF
        val k = checkNotNull(key)
        when (command) {
            Rt950ProImage.READ, Rt950ProImage.READ_APRS -> if (pending.size == HEADER) {
                val (address, size) = addressAndSize()
                val header = pending.toByteArray()
                pending.clear()
                val space = space(command)
                val plain = ByteArray(size) { space.getOrElse(address + it) { 0xFF.toByte() } }
                header.forEach { out.addLast(it) }
                Rt950ProCipher.apply(plain, k).forEach { out.addLast(it) }
            }
            Rt950ProImage.WRITE, Rt950ProImage.WRITE_APRS -> if (pending.size >= HEADER) {
                val (address, size) = addressAndSize()
                if (pending.size == HEADER + size) {
                    val data = Rt950ProCipher.apply(pending.subList(HEADER, HEADER + size).toByteArray(), k)
                    pending.clear()
                    if (command == Rt950ProImage.WRITE && address in corruptWriteAddresses) {
                        data[0] = (data[0].toInt() xor 0x01).toByte()
                    }
                    data.copyInto(space(command), address)
                    blocksWritten++
                    wroteThisSession = true
                    out.addLast(ACK)
                }
            }
            else -> restart(b)
        }
    }

    private fun space(command: Int): ByteArray =
        if (command == Rt950ProImage.READ_APRS || command == Rt950ProImage.WRITE_APRS) aprs else main

    private fun addressAndSize(): Pair<Int, Int> =
        (((pending[1].toInt() and 0xFF) shl 8) or (pending[2].toInt() and 0xFF)) to (pending[3].toInt() and 0xFF)

    /** An unexpected byte means the host started a new session. */
    private fun restart(b: Byte) {
        state = State.IDLE
        key = null
        pending.clear()
        accept(b)
    }

    companion object {
        private const val ACK: Byte = 0x06
        private const val HEADER = 4

        /** The 16 bytes answered to `F`. Their meaning is unknown; the host ignores them. */
        private val IDENTITY: ByteArray = ByteArray(16) { i -> if (i < 6) "RT-950"[i].code.toByte() else 0 }
    }
}
