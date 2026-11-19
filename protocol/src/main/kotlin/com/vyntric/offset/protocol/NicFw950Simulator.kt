/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * An RT-950 Pro running nicFW950, in memory: it answers `AA 30` reads, takes `AA 40` writes into
 * a pending copy, commits them on `AA 48` and drops whatever was not committed on `AA 50`
 * (a reboot). Addresses outside the channel section read as 0xFF. Every host write is processed
 * synchronously; reads never block, so an empty queue behaves like a serial timeout.
 *
 * Faults: [silent] (stock firmware or radio off), [rebootingReads] (the first N read requests
 * after a reboot go unanswered), [detachAfterHostBytes] (cable pulled), [corruptWriteAddresses]
 * (stores different bytes than acknowledged) and [damageReplies] (bad checksums on reads).
 */
class NicFw950Simulator(image: NicFw950Image) : SimulatedDevice {
    private var committed = image.bytes()
    private var pendingFlash = committed.copyOf()
    private val out = ArrayDeque<Byte>()
    private val packet = ArrayList<Byte>()
    private var hostBytes = 0L
    private var detached = false
    private var ignoreReads = 0

    var silent: Boolean = false
    var detachAfterHostBytes: Long? = null
    var corruptWriteAddresses: Set<Int> = emptySet()
    var damageReplies: Boolean = false

    /** Read requests to ignore after each reboot, as a radio still starting up. */
    var rebootingReads: Int = 0

    /**
     * Runs [onRead] on the radio's memory once the host has made this many read requests, as
     * the radio saving a VFO change part way through a session.
     */
    var changeAfterReads: Int? = null
    var onRead: (ByteArray) -> Unit = {}
    private var reads = 0

    /** Bytes received from the host since construction. */
    val hostBytesReceived: Long get() = hostBytes

    /** 128 byte blocks the host has written since construction. */
    var blocksWritten: Int = 0
        private set

    /** Commits (`AA 48`) and reboots (`AA 50`) since construction. */
    var commits: Int = 0
        private set
    var reboots: Int = 0
        private set

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
        packet.clear()
        out.clear()
    }

    /** What the radio's flash holds now (committed writes only). */
    @Synchronized
    override fun currentImage(): NicFw950Image = NicFw950Image.of(committed)

    private fun accept(b: Byte) {
        if (packet.isEmpty() && b != NicFw950Clone.SIGNATURE) return
        packet += b
        if (packet.size < 2) return
        when (packet[1]) {
            NicFw950Clone.READ -> if (packet.size == NicFw950Clone.READ_REQUEST) {
                val address = address()
                packet.clear()
                if (ignoreReads > 0) {
                    ignoreReads--
                    return
                }
                answerRead(address)
                if (++reads == changeAfterReads) {
                    onRead(pendingFlash)
                    committed = pendingFlash.copyOf()
                }
            }
            NicFw950Clone.WRITE -> if (packet.size == NicFw950Clone.WRITE_PACKET) {
                val bytes = packet.toByteArray()
                packet.clear()
                val sum = NicFw950Clone.checksum(bytes, 2, bytes.size - 1)
                if (sum != bytes.last()) return
                val offset = NicFw950Clone.u32(bytes, 2) - NicFw950Image.BASE_ADDRESS
                val data = bytes.copyOfRange(6, 6 + NicFw950Image.BLOCK)
                if (offset + NicFw950Image.BASE_ADDRESS in corruptWriteAddresses) data[0] = (data[0].toInt() xor 1).toByte()
                if (offset in 0..NicFw950Image.SIZE - NicFw950Image.BLOCK) data.copyInto(pendingFlash, offset)
                blocksWritten++
                reply(NicFw950Clone.WRITE)
            }
            NicFw950Clone.FINALIZE -> {
                packet.clear()
                committed = pendingFlash.copyOf()
                commits++
                reply(NicFw950Clone.FINALIZE)
            }
            NicFw950Clone.REBOOT -> {
                packet.clear()
                pendingFlash = committed.copyOf()
                reboots++
                ignoreReads = rebootingReads
            }
            else -> packet.clear()
        }
    }

    private fun answerRead(address: Int) {
        val reply = ByteArray(NicFw950Clone.READ_REPLY)
        reply[0] = NicFw950Clone.SIGNATURE
        reply[1] = NicFw950Clone.READ
        NicFw950Clone.putU32(reply, 2, address)
        val offset = address - NicFw950Image.BASE_ADDRESS
        for (i in 0 until NicFw950Image.BLOCK) {
            reply[6 + i] = pendingFlash.getOrElse(offset + i) { 0xFF.toByte() }
        }
        reply[reply.size - 1] = NicFw950Clone.checksum(reply, 2, reply.size - 1)
        if (damageReplies) reply[10] = (reply[10].toInt() xor 0x55).toByte()
        reply.forEach { out.addLast(it) }
    }

    private fun reply(command: Byte) {
        out.addLast(NicFw950Clone.SIGNATURE)
        out.addLast(command)
    }

    private fun address(): Int = NicFw950Clone.u32(packet.toByteArray(), 2)
}
