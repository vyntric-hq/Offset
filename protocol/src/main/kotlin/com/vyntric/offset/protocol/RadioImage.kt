/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Image layout ported from CHIRP (chirp/drivers/uv5r.py), GPL.
 */
package com.vyntric.offset.protocol

/**
 * An immutable UV-5R clone image in CHIRP's layout:
 * ```
 * 0x0000..0x0008  ident returned by the radio handshake (8 bytes)
 * 0x0008..0x1808  radio memory 0x0000..0x1800 (channels, settings, names)
 * 0x1808..0x1948  radio memory 0x1EC0..0x2000 (aux: power on message, firmware, limits)
 * ```
 * Images without the aux block (0x1808 bytes) are accepted, as CHIRP accepts them.
 */
class RadioImage private constructor(private val data: ByteArray) {

    val size: Int get() = data.size
    val hasAux: Boolean get() = data.size == FULL_SIZE

    /** A defensive copy of the raw bytes. */
    fun bytes(): ByteArray = data.copyOf()

    val ident: ByteArray get() = data.copyOfRange(0, IDENT_SIZE)
    val identHex: String get() = ident.toHex()

    /** The 14 byte firmware string at image 0x1838 (radio 0x1EF0), trimmed. Null without aux. */
    val firmwareVersion: String?
        get() = if (!hasAux) null else data.copyOfRange(FIRMWARE_START, FIRMWARE_END)
            .map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), " ")

    val detection: ModelDetection
        get() {
            val fw = firmwareVersion ?: return ModelDetection(RadioModel.UV5R, recognized = false)
            val model = RadioModel.fromFirmware(fw)
            return ModelDetection(model ?: RadioModel.UV5R, recognized = model != null)
        }

    val model: RadioModel get() = detection.model

    /** True for UV-5R firmware older than BFB291, which CHIRP treats as a separate layout. */
    val isOriginalFirmware: Boolean
        get() {
            if (model != RadioModel.UV5R) return false
            val fw = firmwareVersion ?: return false
            val idx = fw.indexOf("BFB")
            if (idx < 0) return false
            return fw.drop(idx + 3).take(3).toIntOrNull()?.let { it < 291 } ?: false
        }

    /** Upper band byte from the ident (CHIRP _upper_band_from_data). 0x02 marks a 220 MHz radio. */
    val upperBandByte: Int get() = data[3].toInt() and 0xFF

    private val codec get() = ChannelCodec(model)

    fun channel(index: Int): Channel? {
        require(index in 0 until CHANNEL_COUNT) { "Channel index $index out of range" }
        return codec.decode(data, channelOffset(index), data, nameOffset(index))
    }

    fun channels(): List<Channel?> = (0 until CHANNEL_COUNT).map { channel(it) }

    val usedChannelCount: Int get() = (0 until CHANNEL_COUNT).count { !codec.isEmpty(data, channelOffset(it)) }

    /**
     * Returns a copy with all 128 slots replaced by [channels] (null = empty slot). Only the
     * channel and name regions are touched. Unchanged channels keep their exact bytes.
     */
    fun withChannels(channels: List<Channel?>): RadioImage {
        require(channels.size <= CHANNEL_COUNT) { "At most $CHANNEL_COUNT channels, got ${channels.size}" }
        val out = data.copyOf()
        for (i in 0 until CHANNEL_COUNT) writeSlot(out, i, channels.getOrNull(i))
        return RadioImage(out)
    }

    fun withChannel(index: Int, channel: Channel?): RadioImage {
        val out = data.copyOf()
        writeSlot(out, index, channel)
        return RadioImage(out)
    }

    private fun writeSlot(out: ByteArray, index: Int, channel: Channel?) {
        val c = codec
        val ro = channelOffset(index)
        val no = nameOffset(index)
        if (channel == null) {
            if (c.isEmpty(out, ro)) return
            out.fill(0xFF.toByte(), ro, ro + RECORD)
            out.fill(0xFF.toByte(), no, no + RECORD)
            return
        }
        val oldRecord = out.copyOfRange(ro, ro + RECORD)
        val oldName = out.copyOfRange(no, no + RECORD)
        val wasEmpty = c.isEmpty(oldRecord)
        // An unchanged slot keeps every byte, including padding the OEM software wrote.
        if (!wasEmpty && c.decode(oldRecord, 0, oldName, 0) == c.normalize(channel)) return
        val record = c.encodeRecord(channel, if (wasEmpty) null else oldRecord, if (wasEmpty) null else oldName)
        record.copyInto(out, ro)
        val name = c.encodeName(channel.name, if (wasEmpty) null else oldName)
        name.copyInto(out, no)
    }

    override fun equals(other: Any?): Boolean = other is RadioImage && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "RadioImage(${model.displayName}, fw=$firmwareVersion, ident=$identHex)"

    companion object {
        const val IDENT_SIZE = 8
        const val MAIN_SIZE = 0x1800
        const val AUX_SIZE = 0x140
        const val NO_AUX_SIZE = IDENT_SIZE + MAIN_SIZE
        const val FULL_SIZE = NO_AUX_SIZE + AUX_SIZE
        const val CHANNEL_COUNT = 128
        private const val RECORD = ChannelCodec.RECORD_SIZE

        /** Radio address of the aux block, stored at image offset [NO_AUX_SIZE]. */
        const val AUX_RADIO_START = 0x1EC0
        const val RADIO_END = 0x2000
        private const val FIRMWARE_START = 0x1838
        private const val FIRMWARE_END = 0x1846

        fun channelOffset(index: Int): Int = IDENT_SIZE + index * RECORD
        fun nameOffset(index: Int): Int = 0x1008 + index * RECORD

        /** Wraps raw bytes after validating the size. */
        fun of(bytes: ByteArray): RadioImage {
            if (bytes.size != FULL_SIZE && bytes.size != NO_AUX_SIZE) {
                throw InvalidImageException(
                    "Image is ${bytes.size} bytes; a UV-5R image is $FULL_SIZE bytes (or $NO_AUX_SIZE without aux)"
                )
            }
            return RadioImage(bytes.copyOf())
        }

        fun assemble(ident: ByteArray, main: ByteArray, aux: ByteArray): RadioImage {
            require(ident.size == IDENT_SIZE && main.size == MAIN_SIZE && aux.size == AUX_SIZE)
            return RadioImage(ident + main + aux)
        }

        /** Maps an image offset in the main block to the radio address. */
        fun radioAddress(imageOffset: Int): Int = when {
            imageOffset in IDENT_SIZE until NO_AUX_SIZE -> imageOffset - IDENT_SIZE
            imageOffset in NO_AUX_SIZE until FULL_SIZE -> imageOffset - NO_AUX_SIZE + AUX_RADIO_START
            else -> throw IllegalArgumentException("Offset $imageOffset is not radio memory")
        }
    }
}

class InvalidImageException(message: String) : Exception(message)

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
