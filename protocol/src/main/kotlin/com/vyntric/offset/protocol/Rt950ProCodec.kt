/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Channel layout from NathanBarguss/Chirp_Radtel-RT-950-Pro (rt950pro/channel.py, DESIGN.md),
 * MIT, Copyright (c) 2025 Nathan G. Barguss, 2E0NBS.
 */
package com.vyntric.offset.protocol

import java.nio.charset.Charset

/**
 * Encodes and decodes the RT-950 Pro's 32 byte channel record.
 *
 * ```
 *  0..3   rx frequency   packed BCD, little endian, 10 Hz units; all 00/FF = empty slot
 *  4..7   tx frequency   same; all 00/FF = receive only
 *  8..9   rx tone        00 00 or FF FF off; second byte 00 = DCS table index 1..210
 * 10..11  tx tone        (105 normal codes, then the same 105 inverted); else CTCSS tenths, LE
 * 12      low nibble signalling group (option rt950.signalling)
 * 13      low nibble PTT ID: 0 off, 1 BOT, 2 EOT, 3 both
 * 14      low nibble power (0 high, 1 medium, 2 low), high nibble scrambler (rt950.scrambler)
 * 15      bit7 learn FHSS (rt950.fhssLearn), bit6 narrow, bits5-4 encryption (rt950.encryption),
 *         bit3 busy lockout, bit2 scan add, bit1 always clear, bit0 AM receive (mode)
 * 16..19  FHSS code: six hex digits, last pair first, then A0; FF FF FF FF for none (rt950.fhssCode)
 * 20..31  name, GB2312, FF padded
 * ```
 *
 * Encoding works on top of the slot's old record: a field is rewritten only when its value
 * changed, and an option the channel does not carry keeps the slot's bits (a Chinese name that
 * was not edited survives the same way). An empty slot starts from [TEMPLATE].
 *
 * Bit 1 of byte 15: Nathan Barguss's and PE1HVH's drivers call it "TX enabled" and set it on
 * every channel that transmits. On the hardware (firmware 0.29) the radio clears it whenever it
 * saves a channel's RX Modulation, as FM or as AM; a channel with it set shows no RX Modulation
 * choice in the radio's menu; and a channel with it clear transmits. So Offset clears it on every
 * channel it writes, unchanged ones included, and reads receive only from an empty TX frequency.
 */
object Rt950ProCodec {
    const val RECORD_SIZE = 32
    const val NAME_OFFSET = 20
    const val NAME_BYTES = 12

    private const val FLAG_LEARN_FHSS = 0x80
    private const val FLAG_NARROW = 0x40
    private const val ENCRYPTION_SHIFT = 4
    private const val FLAG_AM = 0x01
    private const val FHSS_AT = 16
    private const val FHSS_MARK = 0xA0
    private const val FLAG_BUSY_LOCKOUT = 0x08
    private const val FLAG_SCAN_ADD = 0x04
    /** Set by other software as "TX enabled"; the radio clears it (see the class doc). */
    private const val FLAG_STRAY = 0x02

    /**
     * A new channel's record before its fields are written: no tones, signalling group 0, PTT ID
     * off, high power, scrambler off, wide FM, scan add, no FHSS, empty name.
     */
    val TEMPLATE: ByteArray
        get() = ByteArray(RECORD_SIZE) { 0xFF.toByte() }.also {
            it.fill(0, 8, 15)
            it[15] = FLAG_SCAN_ADD.toByte()
        }

    /**
     * DCS codes in the radio's index order (channel.py `_DCS_CODES`): the radio stores
     * `index + 1` for a normal code and `index + 1 + DCS_CODES.size` for an inverted one.
     */
    val DCS_CODES: List<Int> = Tones.DCS_CODES

    private val GB2312: Charset = Charset.forName("GB2312")

    /** True when the slot holds no receive frequency. */
    fun isEmpty(bytes: ByteArray, offset: Int = 0): Boolean =
        (0 until 4).all { bytes[offset + it].let { b -> b == 0.toByte() || b == 0xFF.toByte() } }

    /** Decodes the record at [offset]. Null for an empty slot or a frequency that is not BCD. */
    fun decode(bytes: ByteArray, offset: Int = 0): Channel? {
        if (isEmpty(bytes, offset)) return null
        val rx = (Bcd.decodeLittleEndian(bytes, offset, 4) ?: return null) * 10
        val flags = bytes[offset + 15].toInt() and 0xFF
        val tx = if (isEmpty(bytes, offset + 4)) null else Bcd.decodeLittleEndian(bytes, offset + 4, 4)?.times(10)
        val (duplex, shift) = duplexOf(rx, tx)
        val ptt = bytes[offset + 13].toInt() and 0x0F
        return Channel(
            rxFreqHz = rx,
            duplex = duplex,
            offsetHz = shift,
            txSquelch = decodeSquelch(bytes, offset + 10),
            rxSquelch = decodeSquelch(bytes, offset + 8),
            power = when (bytes[offset + 14].toInt() and 0x0F) {
                0 -> Power.HIGH
                1 -> Power.MID
                else -> Power.LOW
            },
            wide = flags and FLAG_NARROW == 0,
            scanAdd = flags and FLAG_SCAN_ADD != 0,
            busyLockout = flags and FLAG_BUSY_LOCKOUT != 0,
            pttId = PttId.entries.getOrElse(ptt) { PttId.OFF },
            name = decodeName(bytes, offset + NAME_OFFSET),
            mode = if (flags and FLAG_AM != 0) Modulation.AM else Modulation.FM,
            options = mapOf(
                SIGNALLING.key to (bytes[offset + 12].toInt() and 0x0F).toString(),
                SCRAMBLER.key to ((bytes[offset + 14].toInt() ushr 4) and 0x0F).toString(),
                ENCRYPTION.key to ((flags ushr ENCRYPTION_SHIFT) and 0x03).toString(),
                FHSS_LEARN.key to (flags and FLAG_LEARN_FHSS != 0).toString(),
                FHSS_CODE.key to decodeFhss(bytes, offset + FHSS_AT),
            ),
        )
    }

    /**
     * Encodes [channel] on top of [existing] (the slot's current record, or null for an empty
     * slot) and returns the new record. An unchanged channel returns [existing]'s bytes with bit 1
     * of the flags cleared.
     */
    fun encode(channel: Channel, existing: ByteArray? = null): ByteArray {
        require(channel.digitalMode == null) { "The RT-950 Pro has no digital channels" }
        val old = existing?.takeIf { it.size == RECORD_SIZE }?.let { decode(it) }
        val out = if (old != null) existing.copyOf() else TEMPLATE
        setFlag(out, FLAG_STRAY, false)
        if (old == channel) return out
        val c = normalize(channel)

        if (old?.rxFreqHz != c.rxFreqHz) Bcd.encodeLittleEndian(c.rxFreqHz / 10, out, 0, 4)
        val tx = c.txFreqHz
        if (old == null || old.txFreqHz != tx) {
            if (tx == null) out.fill(0xFF.toByte(), 4, 8) else Bcd.encodeLittleEndian(tx / 10, out, 4, 4)
        }
        if (old?.rxSquelch != c.rxSquelch) encodeSquelch(c.rxSquelch, out, 8)
        if (old?.txSquelch != c.txSquelch) encodeSquelch(c.txSquelch, out, 10)
        if (old?.pttId != c.pttId) setLowNibble(out, 13, c.pttId.ordinal)
        if (old?.power != c.power) {
            setLowNibble(out, 14, when (c.power) { Power.HIGH -> 0; Power.MID -> 1; else -> 2 })
        }
        if (old?.wide != c.wide) setFlag(out, FLAG_NARROW, !c.wide)
        if (old?.scanAdd != c.scanAdd) setFlag(out, FLAG_SCAN_ADD, c.scanAdd)
        if (old?.busyLockout != c.busyLockout) setFlag(out, FLAG_BUSY_LOCKOUT, c.busyLockout)
        if (old?.mode != c.mode) setFlag(out, FLAG_AM, c.mode == Modulation.AM)
        // Options as the caller gave them, not as normalized: a missing one keeps the slot's value.
        fun changed(o: ChannelOption): String? = channel.options[o.key]?.takeIf { it != old?.options?.get(o.key) }
        changed(SIGNALLING)?.let { setLowNibble(out, 12, it.toInt()) }
        changed(SCRAMBLER)?.let { out[14] = ((out[14].toInt() and 0x0F) or (it.toInt() shl 4)).toByte() }
        changed(ENCRYPTION)?.let {
            out[15] = ((out[15].toInt() and (0x03 shl ENCRYPTION_SHIFT).inv()) or (it.toInt() shl ENCRYPTION_SHIFT)).toByte()
        }
        changed(FHSS_LEARN)?.let { setFlag(out, FLAG_LEARN_FHSS, it == "true") }
        changed(FHSS_CODE)?.let { encodeFhss(it, out, FHSS_AT) }
        // A name Offset cannot write (GB2312) survives as long as the user leaves it alone.
        if (old?.name != channel.name) encodeName(c.name, out)
        return out
    }

    /** [channel] as it reads back after [encode] into an empty slot. */
    fun normalize(channel: Channel): Channel {
        val rx = channel.rxFreqHz / 10 * 10
        val (duplex, shift) = duplexOf(rx, channel.txFreqHz?.let { it / 10 * 10 })
        return channel.copy(
            rxFreqHz = rx,
            duplex = duplex,
            offsetHz = shift,
            power = Rt950ProProfile.nearestPower(channel.power),
            pttIdCode = 0,
            name = Rt950ProProfile.sanitizeName(channel.name),
            mode = Rt950ProProfile.nearestMode(channel.mode),
            options = Rt950ProProfile.completeOptions(channel.options),
        )
    }

    /** The signalling (DTMF code) group, 0 to 15 as stored; CHIRP's RT-950 module shows the same numbers. */
    val SIGNALLING = ChannelOption.Choice(
        "rt950.signalling", "Signalling group", ChannelOption.Section.SIGNALLING,
        (0..15).map { "$it" to "$it" },
        help = "Which of the radio's DTMF signalling groups this channel uses.",
    )

    val SCRAMBLER = ChannelOption.Choice(
        "rt950.scrambler", "Scrambler", ChannelOption.Section.PRIVACY,
        listOf("0" to "Off") + (1..8).map { "$it" to "Code $it" },
        storable = (0..15).map { "$it" }.toSet(),
    )

    val ENCRYPTION = ChannelOption.Choice(
        "rt950.encryption", "Encryption", ChannelOption.Section.PRIVACY,
        listOf("0" to "Off", "1" to "Type 1", "2" to "Type 2", "3" to "Type 3"),
    )

    val FHSS_LEARN = ChannelOption.Toggle(
        "rt950.fhssLearn", "Learn hopping code", ChannelOption.Section.PRIVACY,
        help = "When on, the radio learns the frequency hopping code from the first radio it hears.",
    )

    val FHSS_CODE = ChannelOption.HexCode("rt950.fhssCode", "Hopping code", ChannelOption.Section.PRIVACY, length = 6)

    val OPTIONS: List<ChannelOption> = listOf(SIGNALLING, SCRAMBLER, ENCRYPTION, FHSS_LEARN, FHSS_CODE)

    /** The six digit hopping code at [offset], or empty when the field holds none. */
    fun decodeFhss(bytes: ByteArray, offset: Int): String {
        if (bytes[offset + 3].toInt() and 0xFF != FHSS_MARK) return ""
        return (2 downTo 0).joinToString("") { "%02X".format(bytes[offset + it].toInt() and 0xFF) }
    }

    private fun encodeFhss(code: String, out: ByteArray, offset: Int) {
        if (code.isEmpty()) {
            out.fill(0xFF.toByte(), offset, offset + 4)
            return
        }
        for (i in 0 until 3) out[offset + 2 - i] = code.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        out[offset + 3] = FHSS_MARK.toByte()
    }

    /** Decodes a two byte tone field. Unknown DCS indexes read as no tone (and are kept). */
    fun decodeSquelch(bytes: ByteArray, offset: Int): Squelch {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt() and 0xFF
        val raw = lo or (hi shl 8)
        val n = DCS_CODES.size
        return when {
            raw == 0 || raw == 0xFFFF -> Squelch.None
            hi != 0 -> Squelch.Ctcss(raw)
            lo <= n -> Squelch.Dcs(DCS_CODES[lo - 1])
            lo <= 2 * n -> Squelch.Dcs(DCS_CODES[lo - n - 1], inverted = true)
            else -> Squelch.None
        }
    }

    /** Writes [squelch] as a two byte tone field. Throws for a DCS code the radio lacks. */
    fun encodeSquelch(squelch: Squelch, out: ByteArray, offset: Int) {
        val raw = when (squelch) {
            Squelch.None -> 0
            is Squelch.Ctcss -> {
                require(squelch.tenths in 0x100..0xFFFE) { "CTCSS ${squelch.tenths} is out of range" }
                squelch.tenths
            }
            is Squelch.Dcs -> {
                val index = DCS_CODES.indexOf(squelch.code)
                require(index >= 0) { "DCS code ${squelch.code} is not supported" }
                index + 1 + if (squelch.inverted) DCS_CODES.size else 0
            }
        }
        out[offset] = (raw and 0xFF).toByte()
        out[offset + 1] = (raw ushr 8).toByte()
    }

    /** The name at [offset]: GB2312 up to the first 0xFF or 0x00, trailing spaces dropped. */
    fun decodeName(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < offset + NAME_BYTES && bytes[end] != 0xFF.toByte() && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, GB2312).trimEnd()
    }

    /** Writes [name] (already sanitized to printable ASCII) as the 12 byte FF padded name field. */
    private fun encodeName(name: String, out: ByteArray) {
        for (i in 0 until NAME_BYTES) {
            out[NAME_OFFSET + i] = if (i < name.length) name[i].code.toByte() else 0xFF.toByte()
        }
    }

    private fun duplexOf(rx: Long, tx: Long?): Pair<Duplex, Long> = when {
        tx == null -> Duplex.OFF to 0L
        tx == rx -> Duplex.SIMPLEX to 0L
        kotlin.math.abs(rx - tx) > ChannelValidator.MAX_OFFSET_HZ -> Duplex.SPLIT to tx
        rx > tx -> Duplex.MINUS to rx - tx
        else -> Duplex.PLUS to tx - rx
    }

    private fun setLowNibble(out: ByteArray, index: Int, value: Int) {
        out[index] = ((out[index].toInt() and 0xF0) or (value and 0x0F)).toByte()
    }

    private fun setFlag(out: ByteArray, mask: Int, on: Boolean) {
        val b = out[15].toInt()
        out[15] = (if (on) b or mask else b and mask.inv()).toByte()
    }
}
