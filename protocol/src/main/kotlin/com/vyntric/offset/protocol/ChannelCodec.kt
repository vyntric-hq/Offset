/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Record layout and field semantics ported from CHIRP (chirp/drivers/uv5r.py get_memory and
 * set_memory), GPL, and cross checked against baoclone (sergev/baoclone uv-5r.c), BSD.
 */
package com.vyntric.offset.protocol

/**
 * Encodes and decodes the 16 byte UV-5R memory record and the 16 byte name record.
 *
 * Memory record (bit numbers are MSB first, as in CHIRP's bitwise format):
 * ```
 *  0..3  rxfreq   lbcd, 10 Hz units
 *  4..7  txfreq   lbcd, 10 Hz units; FF FF FF FF or 00 00 00 00 means TX inhibit
 *  8..9  rxtone   ul16: 0/FFFF none, >= 0x258 CTCSS tenths of Hz, else DCS index+1 (+0x69 inverted)
 * 10..11 txtone   ul16, same encoding
 * 12     unused1:3 isuhf:1 scode:4
 * 13     unknown1:7 txtoneicon:1
 * 14     mailicon:3 unknown2:3 lowpower:2
 * 15     unknown3:1 wide:1 unknown4:2 bcl:1 scan:1 pttid:2
 * ```
 * Name record: 7 ASCII characters padded with 0xFF, followed by 9 bytes that are preserved.
 */
class ChannelCodec(private val model: RadioModel) {

    fun isEmpty(record: ByteArray, offset: Int = 0): Boolean = (record[offset].toInt() and 0xFF) == 0xFF

    /** Decodes one channel. Returns null for an empty slot. */
    fun decode(record: ByteArray, recordOffset: Int, name: ByteArray, nameOffset: Int): Channel? {
        if (isEmpty(record, recordOffset)) return null
        val r = recordOffset
        val rx = Bcd.decodeLittleEndian(record, r, 4) ?: return null
        val rxHz = rx * 10

        val txRaw = record.copyOfRange(r + 4, r + 8)
        val txInhibit = txRaw.all { it == 0xFF.toByte() } || txRaw.all { it == 0.toByte() }
        val tx = if (txInhibit) null else Bcd.decodeLittleEndian(record, r + 4, 4)
        val (duplex, offset) = when {
            tx == null -> Duplex.OFF to 0L
            tx == rx -> Duplex.SIMPLEX to 0L
            kotlin.math.abs(rx * 10 - tx * 10) > 70_000_000L -> Duplex.SPLIT to tx * 10
            rx > tx -> Duplex.MINUS to (rx - tx) * 10
            else -> Duplex.PLUS to (tx - rx) * 10
        }

        val rxTone = u16(record, r + 8)
        val txTone = u16(record, r + 10)
        val b12 = record[r + 12].toInt() and 0xFF
        val b14 = record[r + 14].toInt() and 0xFF
        val b15 = record[r + 15].toInt() and 0xFF

        val levels = model.powerLevels
        val powerIndex = b14 and 0x03
        val power = levels.getOrElse(powerIndex) { levels.first() }

        return Channel(
            rxFreqHz = rxHz,
            duplex = duplex,
            offsetHz = offset,
            txSquelch = decodeSquelch(txTone),
            rxSquelch = decodeSquelch(rxTone),
            power = power,
            wide = (b15 and 0x40) != 0,
            scanAdd = (b15 and 0x04) != 0,
            busyLockout = (b15 and 0x08) != 0,
            pttId = PttId.entries[b15 and 0x03],
            pttIdCode = b12 and 0x0F,
            name = decodeName(name, nameOffset),
        )
    }

    /**
     * Encodes [channel] into a 16 byte memory record and returns it.
     *
     * When [existing] already decodes to an identical channel it is returned unchanged, so a
     * read, edit nothing, write cycle is byte exact. Otherwise the record is rebuilt from zero as
     * CHIRP's set_memory does, which clears undocumented bits such as isuhf and the icons.
     */
    fun encodeRecord(channel: Channel, existing: ByteArray? = null, existingName: ByteArray? = null): ByteArray {
        if (existing != null && existingName != null &&
            decode(existing, 0, existingName, 0) == normalize(channel)
        ) return existing.copyOf()

        val out = ByteArray(RECORD_SIZE)
        Bcd.encodeLittleEndian(channel.rxFreqHz / 10, out, 0, 4)
        val tx = channel.txFreqHz
        if (tx == null) {
            for (i in 4..7) out[i] = 0xFF.toByte()
        } else {
            Bcd.encodeLittleEndian(tx / 10, out, 4, 4)
        }
        putU16(out, 8, encodeSquelch(channel.rxSquelch))
        putU16(out, 10, encodeSquelch(channel.txSquelch))
        out[12] = (channel.pttIdCode and 0x0F).toByte()
        val levels = model.powerLevels
        val powerIndex = levels.indexOf(model.nearestPower(channel.power))
        out[14] = (powerIndex and 0x03).toByte()
        var b15 = channel.pttId.ordinal and 0x03
        if (channel.wide) b15 = b15 or 0x40
        if (channel.busyLockout) b15 = b15 or 0x08
        if (channel.scanAdd) b15 = b15 or 0x04
        out[15] = b15.toByte()
        return out
    }

    /** Encodes the name into a 16 byte name record, preserving bytes 7..15 of [existing]. */
    fun encodeName(name: String, existing: ByteArray? = null): ByteArray {
        val out = existing?.copyOf() ?: ByteArray(RECORD_SIZE) { 0xFF.toByte() }
        val clean = sanitizeName(name)
        for (i in 0 until model.nameLength) {
            out[i] = if (i < clean.length) clean[i].code.toByte() else 0xFF.toByte()
        }
        return out
    }

    /** Uppercases and drops characters the radio cannot display, then truncates. */
    fun sanitizeName(name: String): String =
        name.uppercase().map { if (it in NAME_CHARSET) it else ' ' }.joinToString("")
            .trimEnd().take(model.nameLength)

    /** The channel as it will read back from the radio after encoding. */
    fun normalize(channel: Channel): Channel {
        val power = model.nearestPower(channel.power)
        return channel.copy(
            rxFreqHz = channel.rxFreqHz / 10 * 10,
            power = power,
            mode = model.nearestMode(channel.mode),
            options = model.ownOptions(channel.options),
            name = sanitizeName(channel.name),
            pttIdCode = channel.pttIdCode and 0x0F,
        ).let { c ->
            // Re-derive duplex the same way decode does so equality is meaningful.
            val tx = c.txFreqHz?.let { it / 10 * 10 }
            when {
                tx == null -> c.copy(duplex = Duplex.OFF, offsetHz = 0)
                tx == c.rxFreqHz -> c.copy(duplex = Duplex.SIMPLEX, offsetHz = 0)
                kotlin.math.abs(c.rxFreqHz - tx) > 70_000_000L -> c.copy(duplex = Duplex.SPLIT, offsetHz = tx)
                c.rxFreqHz > tx -> c.copy(duplex = Duplex.MINUS, offsetHz = c.rxFreqHz - tx)
                else -> c.copy(duplex = Duplex.PLUS, offsetHz = tx - c.rxFreqHz)
            }
        }
    }

    private fun decodeName(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until model.nameLength) {
            val b = bytes[offset + i].toInt() and 0xFF
            // The OEM software may leave 0xFF in the middle of a name.
            sb.append(if (b == 0xFF || b == 0x00) ' ' else b.toChar())
        }
        return sb.toString().trimEnd()
    }

    companion object {
        const val RECORD_SIZE = 16

        /** CHIRP's UV5R_CHARSET: uppercase, digits, space and punctuation. */
        const val NAME_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !@#\$%^&*()+-=[]:\";'<>?,./"

        fun decodeSquelch(raw: Int): Squelch = when {
            raw == 0 || raw == 0xFFFF -> Squelch.None
            raw >= Tones.CTCSS_THRESHOLD -> Squelch.Ctcss(raw)
            raw > Tones.DCS_INVERT_OFFSET -> Tones.DCS_CODES.getOrNull(raw - Tones.DCS_INVERT_OFFSET - 1)
                ?.let { Squelch.Dcs(it, inverted = true) } ?: Squelch.None
            else -> Tones.DCS_CODES.getOrNull(raw - 1)?.let { Squelch.Dcs(it, inverted = false) } ?: Squelch.None
        }

        fun encodeSquelch(squelch: Squelch): Int = when (squelch) {
            Squelch.None -> 0
            is Squelch.Ctcss -> squelch.tenths
            is Squelch.Dcs -> {
                val index = Tones.DCS_CODES.indexOf(squelch.code)
                require(index >= 0) { "DCS code ${squelch.code} is not supported" }
                index + 1 + if (squelch.inverted) Tones.DCS_INVERT_OFFSET else 0
            }
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}
