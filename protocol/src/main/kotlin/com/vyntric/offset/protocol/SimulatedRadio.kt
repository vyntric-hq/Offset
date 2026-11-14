/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * A UV-5R that lives in memory and speaks the clone protocol byte for byte.
 *
 * Every write from the host is processed synchronously, so replies are already queued when the
 * host reads. Reads never block: an empty queue behaves like a serial timeout.
 *
 * Fault injection covers the failure paths the app must survive: [detachAfterHostBytes] (cable
 * pulled mid transfer), [silent] (radio off or wrong cable), and [corruptWriteAddresses] (the
 * radio stores different bytes than it acknowledged, so verification must catch it).
 */
class SimulatedRadio(
    ident: ByteArray,
    /** Full 0x2000 byte radio address space. */
    memory: ByteArray,
    private val acceptedMagics: List<ByteArray> = Uv5rClone.MAGICS,
) : SimulatedDevice {
    private val ident = ident.copyOf()
    private val mem = memory.copyOf()
    private val out = ArrayDeque<Byte>()
    private val pending = ArrayList<Byte>()
    private var state = State.IDLE
    private var hostBytes = 0L
    private var detached = false

    var detachAfterHostBytes: Long? = null
    var silent: Boolean = false
    var corruptWriteAddresses: Set<Int> = emptySet()

    /** Bytes received from the host since construction; use it to place [detachAfterHostBytes]. */
    val hostBytesReceived: Long get() = hostBytes

    /** Number of 16 byte blocks the host has written since construction. */
    var blocksWritten: Int = 0
        private set

    private enum class State { IDLE, MAGIC_OK, IDENT_SENT, CLONE, READ_AWAIT_ACK }

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

    /** Ends the clone session, as power cycling or closing the port would. */
    @Synchronized
    override fun endSession() {
        state = State.IDLE
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

    @Synchronized
    fun memorySnapshot(): ByteArray = mem.copyOf()

    /** The image a correct download from this radio produces. */
    @Synchronized
    override fun currentImage(): Uv5rImage = Uv5rImage.assemble(
        ident,
        mem.copyOfRange(0, Uv5rImage.MAIN_SIZE),
        mem.copyOfRange(Uv5rImage.AUX_RADIO_START, Uv5rImage.RADIO_END),
    )

    private fun accept(b: Byte) {
        when (state) {
            State.IDLE -> {
                pending += b
                val buf = pending.toByteArray()
                val match = acceptedMagics.firstOrNull { it.contentEquals(buf) }
                when {
                    match != null -> { pending.clear(); out.addLast(ACK); state = State.MAGIC_OK }
                    acceptedMagics.none { it.size >= buf.size && it.copyOf(buf.size).contentEquals(buf) } ->
                        // Not a prefix of any magic: resynchronise on this byte.
                        if (pending.size > 1) { pending.clear(); accept(b) } else pending.clear()
                }
            }
            State.MAGIC_OK -> if (b == 0x02.toByte()) {
                ident.forEach { out.addLast(it) }
                state = State.IDENT_SENT
            } else restart(b)
            State.IDENT_SENT -> if (b == ACK) {
                out.addLast(ACK)
                state = State.CLONE
            } else restart(b)
            State.READ_AWAIT_ACK -> if (b == ACK) {
                out.addLast(ACK)
                state = State.CLONE
            } else { state = State.CLONE; accept(b) }
            State.CLONE -> clone(b)
        }
    }

    private fun clone(b: Byte) {
        pending += b
        val cmd = pending[0]
        when (cmd) {
            'S'.code.toByte() -> if (pending.size == 4) {
                val addr = addr()
                val size = pending[3].toInt() and 0xFF
                pending.clear()
                out.addLast('X'.code.toByte()); out.addLast((addr ushr 8).toByte())
                out.addLast(addr.toByte()); out.addLast(size.toByte())
                for (i in 0 until size) out.addLast(mem.getOrElse(addr + i) { 0xFF.toByte() })
                state = State.READ_AWAIT_ACK
            }
            'X'.code.toByte() -> if (pending.size >= 4) {
                val size = pending[3].toInt() and 0xFF
                if (pending.size == 4 + size) {
                    val addr = addr()
                    val data = pending.subList(4, 4 + size).toByteArray()
                    if (addr in corruptWriteAddresses) data[0] = (data[0].toInt() xor 0x01).toByte()
                    data.copyInto(mem, addr)
                    blocksWritten++
                    pending.clear()
                    out.addLast(ACK)
                }
            }
            else -> restart(b)
        }
    }

    /** An unexpected byte means the host started a new session. */
    private fun restart(b: Byte) {
        state = State.IDLE
        pending.clear()
        accept(b)
    }

    private fun addr(): Int = ((pending[1].toInt() and 0xFF) shl 8) or (pending[2].toInt() and 0xFF)

    companion object {
        private const val ACK: Byte = 0x06

        /** Builds a radio whose memory matches [image]; unmapped addresses read as 0xFF. */
        fun fromImage(image: Uv5rImage): SimulatedRadio {
            val bytes = image.bytes()
            val mem = ByteArray(Uv5rImage.RADIO_END) { 0xFF.toByte() }
            bytes.copyInto(mem, 0, Uv5rImage.IDENT_SIZE, Uv5rImage.NO_AUX_SIZE)
            if (image.hasAux) bytes.copyInto(mem, Uv5rImage.AUX_RADIO_START, Uv5rImage.NO_AUX_SIZE, Uv5rImage.FULL_SIZE)
            return SimulatedRadio(image.ident, mem)
        }
    }
}
