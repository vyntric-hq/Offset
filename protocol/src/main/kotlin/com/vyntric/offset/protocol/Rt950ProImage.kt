/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Image layout from NathanBarguss/Chirp_Radtel-RT-950-Pro (rt950pro/transport.py DEFAULT_SEGMENTS,
 * rt950pro/image.py), MIT, Copyright (c) 2025 Nathan G. Barguss, 2E0NBS.
 */
package com.vyntric.offset.protocol

/** One contiguous range of radio memory the clone protocol reads and writes in 128 byte blocks. */
data class Rt950ProSegment(
    val readCommand: Int,
    val writeCommand: Int,
    /** Radio address of the first byte. */
    val address: Int,
    val length: Int,
    /** Where the segment starts in the image. */
    val imageOffset: Int,
) {
    val blocks: Int get() = length / Rt950ProImage.BLOCK
}

/**
 * An immutable RT-950 Pro image: the decrypted clone segments concatenated in read order, the
 * same 33,152 byte format as the dumps written by Nathan Barguss's tools.
 * ```
 * image 0x0000..0x7800  radio 0x0000 channels (960 x 32 bytes)   the only memory Offset writes
 * image 0x7800..0x7900  radio 0x8000 VFO A/B/C
 * image 0x7900..0x7A00  radio 0x9000 function settings
 * image 0x7A00..0x7C00  radio 0xA000 DTMF
 * image 0x7C00..0x7E00  radio 0xB000 modulation parameters
 * image 0x7E00..0x8100  radio 0xD000 modulation names
 * image 0x8100..0x8180  APRS 0x0000 (read with 0x54, written with 0x55)
 * image 0x8180..0x8280  radio 0xC000 zone names (10 x 16 bytes; since Offset 0.7.7)
 * ```
 * The image does not carry the model string; the clone checks it at every handshake. Images saved
 * before 0.7.7 end after APRS ([LEGACY_SIZE]); they have no zone names and are still accepted.
 *
 * Zones: firmware 0.24 and later groups the channels into 10 zones of 99 and keeps the zone names
 * at radio 0xC000 (PE1HVH's CHIRP driver, confirmed on hardware with firmware 0.29; the radio's
 * defaults are "ZoneOne" to "ZoneTen"). The firmware version is not readable over the cable, so
 * [namedZones] trusts that block only when all ten slots hold printable ASCII and one is not empty.
 * Offset keeps to channels 1 to 960 (zone 10 ends at its channel 69): on older firmware the bytes
 * after 0x7800 are not channels.
 */
class Rt950ProImage private constructor(private val data: ByteArray) : RadioImage {

    override val driver: RadioDriver get() = Rt950ProDriver
    override val profile: RadioProfile get() = Rt950ProProfile
    override val identity: String get() = MODEL_NAME
    override val modelName: String get() = MODEL_NAME
    override val firmwareVersion: String? get() = null
    override val size: Int get() = data.size

    override fun bytes(): ByteArray = data.copyOf()

    override fun channel(index: Int): Channel? {
        require(index in 0 until CHANNEL_COUNT) { "Channel index $index out of range" }
        return Rt950ProCodec.decode(data, channelOffset(index))
    }

    override val usedChannelCount: Int get() = (0 until CHANNEL_COUNT).count { channel(it) != null }

    /**
     * Replaces every slot. A slot whose channel is unchanged keeps its exact bytes; an edited
     * channel is re-encoded on top of its old record; a deleted one becomes all 0xFF. A record
     * Offset cannot decode (a frequency that is not BCD) is left alone when its slot stays empty.
     */
    override fun withChannels(slots: List<Channel?>): Rt950ProImage {
        require(slots.size <= CHANNEL_COUNT) { "At most $CHANNEL_COUNT channels, got ${slots.size}" }
        val out = data.copyOf()
        for (i in 0 until CHANNEL_COUNT) writeSlot(out, i, slots.getOrNull(i))
        return Rt950ProImage(out)
    }

    fun withChannel(index: Int, channel: Channel?): Rt950ProImage {
        require(index in 0 until CHANNEL_COUNT) { "Channel index $index out of range" }
        val out = data.copyOf()
        writeSlot(out, index, channel)
        return Rt950ProImage(out)
    }

    /** Channels, and the zone names when both images have them (an image without them restores channels only). */
    override fun withWritableRegionsOf(source: RadioImage): Rt950ProImage {
        require(source is Rt950ProImage) { "Not an RT-950 Pro image" }
        val out = data.copyOf()
        source.data.copyInto(out, WRITABLE.first, WRITABLE.first, WRITABLE.last + 1)
        if (namedZones != null && source.namedZones != null) {
            source.data.copyInto(out, ZONE_NAMES.first, ZONE_NAMES.first, ZONE_NAMES.last + 1)
        }
        return Rt950ProImage(out)
    }

    /** Channels may differ, and zone names when this image (the radio as read) has them. Rule 3. */
    override fun differsOnlyInWritableRegions(other: RadioImage): Boolean {
        if (other !is Rt950ProImage || other.data.size != data.size) return false
        val namesWritable = namedZones != null
        return data.indices.all { it in WRITABLE || (namesWritable && it in ZONE_NAMES) || data[it] == other.data[it] }
    }

    /** True when the zone name bytes differ from [other]'s. */
    internal fun zoneNamesDiffer(other: Rt950ProImage): Boolean =
        data.size == SIZE && other.data.size == SIZE && ZONE_NAMES.any { data[it] != other.data[it] }

    override val namedZones: RadioZones? by lazy { readZones() }

    private fun readZones(): RadioZones? {
        if (data.size != SIZE) return null
        val names = (0 until ZONE_COUNT).map { z ->
            val o = ZONE_NAMES_OFFSET + z * ZONE_STRIDE
            val used = (o until o + ZONE_STRIDE).firstOrNull { data[it] == 0.toByte() || data[it] == 0xFF.toByte() }?.minus(o) ?: ZONE_STRIDE
            val text = (o until o + used).map { data[it].toInt() and 0xFF }
            if (text.any { it !in 0x20..0x7E }) return null
            String(CharArray(text.size) { text[it].toChar() }).trim()
        }
        if (names.all { it.isEmpty() }) return null
        return RadioZones(names, ZONE_SIZE, ZONE_NAME_LENGTH)
    }

    /**
     * Renames the zones. An unchanged name keeps its exact bytes; a changed one is written in
     * printable ASCII, at most 12 characters, padded with the byte its slot already used (after
     * PE1HVH's encode_zone_names). A renamed zone may not be blank: [namedZones] relies on names
     * to tell zone firmware apart. A zone the radio already holds blank can stay blank.
     */
    override fun withZoneNames(names: List<String>): Rt950ProImage {
        val current = requireNotNull(namedZones) { "This RT-950 Pro image has no zone names" }
        require(names.size == ZONE_COUNT) { "The RT-950 Pro has $ZONE_COUNT zones, got ${names.size}" }
        val out = data.copyOf()
        names.forEachIndexed { z, raw ->
            // An unedited name, even one longer than 12 characters, keeps its bytes.
            if (raw == current.names[z]) return@forEachIndexed
            val name = RadioZones.clean(raw, ZONE_NAME_LENGTH)
            if (name == current.names[z]) return@forEachIndexed
            require(name.isNotEmpty()) { "Zone ${z + 1} needs a name" }
            val o = ZONE_NAMES_OFFSET + z * ZONE_STRIDE
            val old = out.copyOfRange(o, o + ZONE_STRIDE)
            val used = old.indexOfFirst { it == 0.toByte() || it == 0xFF.toByte() }.let { if (it < 0) ZONE_STRIDE else it }
            val pad: Byte = if (used < ZONE_STRIDE && old[used] == 0.toByte()) 0 else 0xFF.toByte()
            val width = maxOf(ZONE_NAME_LENGTH, used)
            for (i in 0 until width) out[o + i] = if (i < name.length) name[i].code.toByte() else pad
        }
        return Rt950ProImage(out)
    }

    override fun mismatchedChannels(actual: RadioImage): List<Int> {
        require(actual is Rt950ProImage) { "Not an RT-950 Pro image" }
        return (0 until CHANNEL_COUNT).filter { i ->
            val o = channelOffset(i)
            (o until o + RECORD).any { data[it] != actual.data[it] }
        }
    }

    override val chirpMetadata: Map<String, String>
        get() = linkedMapOf("rclass" to "RT950Pro", "vendor" to "Radtel", "model" to MODEL_NAME, "variant" to "")

    private fun writeSlot(out: ByteArray, index: Int, channel: Channel?) {
        val o = channelOffset(index)
        val old = out.copyOfRange(o, o + RECORD)
        if (channel == null) {
            if (Rt950ProCodec.decode(old) != null) out.fill(0xFF.toByte(), o, o + RECORD)
            return
        }
        Rt950ProCodec.encode(channel, old).copyInto(out, o)
    }

    override fun equals(other: Any?): Boolean = other is Rt950ProImage && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "Rt950ProImage($usedChannelCount channels)"

    companion object {
        const val MODEL_NAME = "RT-950 Pro"
        const val CHANNEL_COUNT = 960
        const val BLOCK = 0x80
        private const val RECORD = Rt950ProCodec.RECORD_SIZE

        const val READ = 0x52
        const val WRITE = 0x57
        const val READ_APRS = 0x54
        const val WRITE_APRS = 0x55

        /** Every segment, in the order the hardware tested transport reads them. */
        val SEGMENTS: List<Rt950ProSegment> = run {
            var offset = 0
            listOf(
                Triple(READ, WRITE, 0x0000 to 0x7800),
                Triple(READ, WRITE, 0x8000 to 0x0100),
                Triple(READ, WRITE, 0x9000 to 0x0100),
                Triple(READ, WRITE, 0xA000 to 0x0200),
                Triple(READ, WRITE, 0xB000 to 0x0200),
                Triple(READ, WRITE, 0xD000 to 0x0300),
                Triple(READ_APRS, WRITE_APRS, 0x0000 to 0x0080),
                Triple(READ, WRITE, 0xC000 to 0x0100),
            ).map { (r, w, range) ->
                Rt950ProSegment(r, w, range.first, range.second, offset).also { offset += range.second }
            }
        }

        /** The channel segment, the only memory Offset writes. */
        val CHANNELS: Rt950ProSegment get() = SEGMENTS[0]

        /** The zone name segment, read last as PE1HVH's driver does. */
        val ZONE_SEGMENT: Rt950ProSegment get() = SEGMENTS[7]

        const val SIZE = 33_408

        /** Images saved before zone names were read: no 0xC000 segment. */
        const val LEGACY_SIZE = 33_152

        const val ZONE_COUNT = 10
        const val ZONE_SIZE = 99
        const val ZONE_STRIDE = 16
        const val ZONE_NAME_LENGTH = 12
        private const val ZONE_NAMES_OFFSET = LEGACY_SIZE

        /** Image offsets Offset may write: the 960 channel records. Rule 3. */
        val WRITABLE: IntRange = 0 until CHANNEL_COUNT * RECORD

        /** Image offsets of the ten zone names, written only on a radio whose read shows them. */
        val ZONE_NAMES: IntRange = ZONE_NAMES_OFFSET until ZONE_NAMES_OFFSET + ZONE_COUNT * ZONE_STRIDE

        fun channelOffset(index: Int): Int = index * RECORD

        /** Wraps raw bytes after validating the size. */
        fun of(bytes: ByteArray): Rt950ProImage {
            if (bytes.size != SIZE && bytes.size != LEGACY_SIZE) {
                throw InvalidImageException("Image is ${bytes.size} bytes; an RT-950 Pro image is $SIZE bytes.")
            }
            return Rt950ProImage(bytes.copyOf())
        }
    }
}
