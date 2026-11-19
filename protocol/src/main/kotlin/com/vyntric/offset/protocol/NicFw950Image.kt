/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Storage layout from nicsure's nicFW880/950 documentation (Storage-Layout, Data-Structures) and
 * the NicFW950 RMS app (channel N at 0x50200 + (N + 3) * 62). Facts only; no code copied.
 */
package com.vyntric.offset.protocol

/**
 * An immutable image of an RT-950 Pro running nicFW950: its whole channel section, flash
 * 0x50200 to 0x5F600 (0xF400 bytes), as the radio stores it.
 * ```
 * 0x0000..0x00F8  slots 0 to 3: the VFOs (never written)
 * 0x00F8..0xF2CA  slots 4 to 1002: channels 1 to 999, 62 bytes each   the memory Offset writes
 * 0xF2CA..0xF400  unused tail (never written)
 * ```
 * Settings, calibration and the band plan live elsewhere in flash and are never read or written.
 */
class NicFw950Image private constructor(private val data: ByteArray) : RadioImage {

    override val driver: RadioDriver get() = NicFw950Driver
    override val profile: RadioProfile get() = NicFw950Profile
    override val identity: String get() = IDENTITY
    override val modelName: String get() = MODEL_NAME
    override val firmwareVersion: String? get() = FIRMWARE
    override val size: Int get() = data.size

    override fun bytes(): ByteArray = data.copyOf()

    override fun channel(index: Int): Channel? {
        require(index in 0 until CHANNEL_COUNT) { "Channel index $index out of range" }
        return NicFw950Codec.decode(data, channelOffset(index))
    }

    override val usedChannelCount: Int get() = (0 until CHANNEL_COUNT).count { NicFw950Codec.isActive(data, channelOffset(it)) }

    /**
     * True when some slot carries nicFW950's magic. A radio running nicFW for another model (the
     * RT-880's nicFW880 speaks the same protocol) stores a different magic, so this is how a read
     * tells the two apart.
     */
    val looksLikeNicFw950: Boolean get() = (0 until SLOTS).any { NicFw950Codec.hasMagic(data, it * RECORD) }

    /**
     * Replaces every channel. An unchanged channel keeps its exact bytes; an edited one is
     * re-encoded on top of its old record; a deleted one loses its frequency and magic.
     */
    override fun withChannels(slots: List<Channel?>): NicFw950Image {
        require(slots.size <= CHANNEL_COUNT) { "At most $CHANNEL_COUNT channels, got ${slots.size}" }
        val out = data.copyOf()
        for (i in 0 until CHANNEL_COUNT) writeSlot(out, i, slots.getOrNull(i))
        return NicFw950Image(out)
    }

    override fun withWritableRegionsOf(source: RadioImage): NicFw950Image {
        require(source is NicFw950Image) { "Not a nicFW950 image" }
        val out = data.copyOf()
        source.data.copyInto(out, WRITABLE.first, WRITABLE.first, WRITABLE.last + 1)
        return NicFw950Image(out)
    }

    override fun differsOnlyInWritableRegions(other: RadioImage): Boolean {
        if (other !is NicFw950Image) return false
        return data.indices.all { it in WRITABLE || data[it] == other.data[it] }
    }

    override fun mismatchedChannels(actual: RadioImage): List<Int> {
        require(actual is NicFw950Image) { "Not a nicFW950 image" }
        return (0 until CHANNEL_COUNT).filter { i ->
            val o = channelOffset(i)
            (o until o + RECORD).any { data[it] != actual.data[it] }
        }
    }

    override val chirpMetadata: Map<String, String>
        get() = linkedMapOf("rclass" to "NicFW950", "vendor" to "Radtel", "model" to CHIRP_MODEL, "variant" to "nicFW950")

    private fun writeSlot(out: ByteArray, index: Int, channel: Channel?) {
        val o = channelOffset(index)
        val old = out.copyOfRange(o, o + RECORD)
        if (channel == null) {
            if (NicFw950Codec.isActive(old)) NicFw950Codec.clear(out, o)
            return
        }
        NicFw950Codec.encode(channel, old).copyInto(out, o)
    }

    override fun equals(other: Any?): Boolean = other is NicFw950Image && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "NicFw950Image($usedChannelCount channels)"

    companion object {
        const val IDENTITY = "RT-950 Pro nicFW950"
        const val MODEL_NAME = "RT-950 Pro"
        const val FIRMWARE = "nicFW950"
        const val CHIRP_MODEL = "RT-950 Pro nicFW950"

        /** Flash address of the channel section and its length. */
        const val BASE_ADDRESS = 0x50200
        const val SIZE = 0xF400
        const val BLOCK = 128

        const val CHANNEL_COUNT = 999
        private const val RECORD = NicFw950Codec.RECORD_SIZE

        /** Slots before channel 1: the VFOs. */
        const val FIRST_CHANNEL_SLOT = 4
        private const val SLOTS = FIRST_CHANNEL_SLOT + CHANNEL_COUNT

        /** Image offsets Offset may write: the 999 channel records. Rule 3. */
        val WRITABLE: IntRange = FIRST_CHANNEL_SLOT * RECORD until SLOTS * RECORD

        fun channelOffset(index: Int): Int = (index + FIRST_CHANNEL_SLOT) * RECORD

        fun of(bytes: ByteArray): NicFw950Image {
            if (bytes.size != SIZE) {
                throw InvalidImageException("Image is ${bytes.size} bytes; a nicFW950 channel image is $SIZE bytes.")
            }
            return NicFw950Image(bytes.copyOf())
        }
    }
}
