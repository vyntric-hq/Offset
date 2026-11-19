/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Record layout from nicsure's nicFW880/950 documentation (Data-Structures) and RT950Pro BTLE
 * bridge (ChannelData.kt), checked against the NicFW950 RMS desktop app. Facts only; no code copied.
 */
package com.vyntric.offset.protocol

/**
 * Encodes and decodes nicFW950's 62 byte channel record (`channelInfo`), little endian.
 *
 * ```
 *  0..3   rx frequency   u32, 10 Hz units
 *  4..7   tx frequency   u32, 10 Hz units
 *  8..11  groups         up to four letters A to Z as 1 to 26, 0 unused (nicfw.groups)
 * 12..13  rx subtone     0 none; CTCSS tenths; DCS = 0x8000 | octal code (| 0x4000 inverted)
 * 14..15  tx subtone     same
 * 16..17  clarifier      s16, 10 Hz units (nicfw.clarifier)
 * 18      modulation     0 FM, 1 AM, 2 DSB, 3 auto, then the HF receiver's 4 WFM, 5 AM, 6 LSB,
 *                        7 USB, 8 CW (mode)
 * 19      bandwidth      0 wide, 1 narrow, 2 auto, 3 to 7 HF receiver 6, 4, 3, 2, 1 kHz (nicfw.bandwidth)
 * 20      busy lock      bool
 * 21      tx power       0 no transmit, 1 zero, 2 very low, 3 low, 4 medium, 5 high, 6 very high
 * 22      reversed       bool (nicfw.reversed)
 * 23      PTT ID         0 off, 1 BOT, 2 EOT, 3 both
 * 24..25  magic          0xC950 when the channel is active; anything else is an empty slot
 * 26      scrambler      0 off, 1 to 20 for 2600 to 4500 Hz (nicfw.scrambler)
 * 27..31  scan hold, scan monitor, reserved (not modeled)
 * 32..61  name           ASCII, 0 padded
 * ```
 *
 * As for the RT-950 Pro, a field is rewritten only when its value changed, and an option the
 * channel does not carry keeps the slot's bytes. nicFW has no per channel scan flag (it scans
 * groups), so every channel reads as scanned. Value lists are the NicFW950 RMS app's.
 */
object NicFw950Codec {
    const val RECORD_SIZE = 62
    const val MAGIC = 0xC950
    const val NAME_OFFSET = 32
    const val NAME_BYTES = 30

    private const val RX = 0
    private const val TX = 4
    private const val GROUPS = 8
    private const val CLARIFIER = 16
    private const val REVERSED = 22
    private const val SCRAMBLER_AT = 26
    private const val RX_TONE = 12
    private const val TX_TONE = 14
    private const val MODULATION = 18
    private const val BANDWIDTH_AT = 19
    private const val BUSY = 20
    private const val POWER = 21
    private const val PTT_ID = 23
    private const val MAGIC_AT = 24

    /** Power bytes 1 to 6, in the order of [POWERS]; 0 is "no transmit". */
    private val POWERS = listOf(Power.ZERO, Power.VERY_LOW, Power.LOW, Power.MID, Power.HIGH, Power.VERY_HIGH)
    private const val POWER_HIGH = 5
    private const val NO_TRANSMIT = 0

    /** Modulation bytes 0 to 8. */
    val MODES: List<Modulation> = listOf(
        Modulation.FM, Modulation.AM, Modulation.DSB, Modulation.AUTO,
        Modulation.WFM, Modulation.TUNER_AM, Modulation.LSB, Modulation.USB, Modulation.CW,
    )

    private val BYTE_VALUES: Set<String> = (0..255).map { "$it" }.toSet()

    val BANDWIDTH = ChannelOption.Choice(
        "nicfw.bandwidth", "Bandwidth", ChannelOption.Section.MODE,
        listOf("0" to "Wide", "1" to "Narrow", "2" to "Auto") +
            listOf(6, 4, 3, 2, 1).mapIndexed { i, khz -> "${i + 3}" to "$khz kHz" },
        help = "Auto follows nicFW's band plan. In the HF receiver's modes, bandwidth is the filter width.",
        storable = BYTE_VALUES,
        receiveOnlyValues = (3..7).map { "$it" }.toSet(),
    )

    val GROUPS_OPTION = ChannelOption.Letters(
        "nicfw.groups", "Scan groups", ChannelOption.Section.SCAN, max = 4,
        help = "nicFW scans by group. A channel can be in up to four of the groups A to Z.",
    )

    val CLARIFIER_OPTION = ChannelOption.Number(
        "nicfw.clarifier", "Clarifier", ChannelOption.Section.ADVANCED,
        min = -327.68, max = 327.67, decimals = 2, unit = "kHz",
        help = "Fine tuning for a station that is slightly off frequency. In the HF receiver's " +
            "LSB, USB and CW modes it sets the beat (BFO) offset.",
    )

    val REVERSED_OPTION = ChannelOption.Toggle(
        "nicfw.reversed", "Reversed", ChannelOption.Section.ADVANCED,
        help = "Swaps receive and transmit while on the channel. In the HF receiver's AM mode it " +
            "turns on the power line noise filter instead.",
    )

    val SCRAMBLER = ChannelOption.Choice(
        "nicfw.scrambler", "Scrambler", ChannelOption.Section.PRIVACY,
        listOf("0" to "Off") + (1..20).map { "$it" to "${2500 + it * 100} Hz" },
        help = "The split frequency of nicFW's voice inversion scrambler.",
        storable = BYTE_VALUES,
    )

    val OPTIONS: List<ChannelOption> = listOf(BANDWIDTH, GROUPS_OPTION, REVERSED_OPTION, CLARIFIER_OPTION, SCRAMBLER)

    /** Above this a stored receive frequency is not a channel (the radio tunes below 1.3 GHz). */
    private const val MAX_RX_TENS = 130_000_000L

    private const val DCS_FLAG = 0x8000
    private const val DCS_INVERTED = 0x4000
    private const val DCS_CODE_MASK = 0x01FF

    /** True when the record at [offset] carries the active magic and a plausible frequency. */
    fun isActive(bytes: ByteArray, offset: Int = 0): Boolean {
        if (u16(bytes, offset + MAGIC_AT) != MAGIC) return false
        val rx = u32(bytes, offset + RX)
        return rx in 1 until MAX_RX_TENS
    }

    /** True when the record at [offset] carries nicFW950's magic, active or a VFO. */
    fun hasMagic(bytes: ByteArray, offset: Int = 0): Boolean = u16(bytes, offset + MAGIC_AT) == MAGIC

    /** Decodes the record at [offset]. Null for an inactive slot. */
    fun decode(bytes: ByteArray, offset: Int = 0): Channel? {
        if (!isActive(bytes, offset)) return null
        val rx = u32(bytes, offset + RX) * 10
        val powerByte = bytes[offset + POWER].toInt() and 0xFF
        val txTens = u32(bytes, offset + TX)
        val tx = if (powerByte == NO_TRANSMIT || txTens == 0L || txTens >= MAX_RX_TENS) null else txTens * 10
        val (duplex, shift) = duplexOf(rx, tx)
        return Channel(
            rxFreqHz = rx,
            duplex = duplex,
            offsetHz = shift,
            txSquelch = decodeSquelch(u16(bytes, offset + TX_TONE)),
            rxSquelch = decodeSquelch(u16(bytes, offset + RX_TONE)),
            power = if (tx == null) Power.HIGH else powerOf(powerByte),
            wide = (bytes[offset + BANDWIDTH_AT].toInt() and 0xFF) != 1,
            scanAdd = true,
            busyLockout = bytes[offset + BUSY].toInt() != 0,
            pttId = PttId.entries.getOrElse(bytes[offset + PTT_ID].toInt() and 0xFF) { PttId.OFF },
            name = decodeName(bytes, offset + NAME_OFFSET),
            // A modulation byte past the list reads as auto, as the RMS app shows it.
            mode = MODES.getOrElse(bytes[offset + MODULATION].toInt() and 0xFF) { Modulation.AUTO },
            options = mapOf(
                BANDWIDTH.key to (bytes[offset + BANDWIDTH_AT].toInt() and 0xFF).toString(),
                GROUPS_OPTION.key to decodeGroups(u32(bytes, offset + GROUPS)),
                REVERSED_OPTION.key to (bytes[offset + REVERSED].toInt() != 0).toString(),
                CLARIFIER_OPTION.key to ChannelOption.Number.format(s16(bytes, offset + CLARIFIER) / 100.0, 2),
                SCRAMBLER.key to (bytes[offset + SCRAMBLER_AT].toInt() and 0xFF).toString(),
            ),
        )
    }

    /**
     * Encodes [channel] on top of [existing] (the slot's record, or null for an empty slot) and
     * returns the new record. An unchanged channel returns [existing]'s bytes.
     */
    fun encode(channel: Channel, existing: ByteArray? = null): ByteArray {
        require(channel.digitalMode == null) { "nicFW950 has no digital channels" }
        val old = existing?.takeIf { it.size == RECORD_SIZE }?.let { decode(it) }
        val out = if (old != null) existing.copyOf() else template()
        if (old == channel) return out
        val c = normalize(channel)

        if (old?.rxFreqHz != c.rxFreqHz) putU32(out, RX, c.rxFreqHz / 10)
        val tx = c.txFreqHz // null for the HF receiver's modes (normalize)
        if (tx == null) {
            // Transmit off is "N/T" power; the stored transmit frequency is kept (or set to rx).
            out[POWER] = NO_TRANSMIT.toByte()
            if (old == null) putU32(out, TX, c.rxFreqHz / 10)
        } else {
            if (old?.txFreqHz != tx) putU32(out, TX, tx / 10)
            if (old?.txFreqHz == null || old.power != c.power) out[POWER] = powerByte(c.power).toByte()
        }
        if (old?.rxSquelch != c.rxSquelch) putU16(out, RX_TONE, encodeSquelch(c.rxSquelch))
        if (old?.txSquelch != c.txSquelch) putU16(out, TX_TONE, encodeSquelch(c.txSquelch))
        // Options as the caller gave them, not as normalized: a missing one keeps the slot's value.
        fun changed(o: ChannelOption): String? = channel.options[o.key]?.takeIf { it != old?.options?.get(o.key) }
        // The bandwidth option, when the channel carries it, is the whole story; wide/narrow is
        // what a channel from a file or another radio says.
        if (BANDWIDTH.key in channel.options) changed(BANDWIDTH)?.let { out[BANDWIDTH_AT] = it.toInt().toByte() }
        else if (old?.wide != c.wide) out[BANDWIDTH_AT] = (if (c.wide) 0 else 1).toByte()
        if (old?.busyLockout != c.busyLockout) out[BUSY] = (if (c.busyLockout) 1 else 0).toByte()
        if (old?.pttId != c.pttId) out[PTT_ID] = c.pttId.ordinal.toByte()
        if (old?.name != c.name) encodeName(c.name, out)
        if (old?.mode != c.mode) out[MODULATION] = MODES.indexOf(c.mode).toByte()
        changed(GROUPS_OPTION)?.let { putU32(out, GROUPS, encodeGroups(it)) }
        changed(REVERSED_OPTION)?.let { out[REVERSED] = (if (it == "true") 1 else 0).toByte() }
        changed(CLARIFIER_OPTION)?.let { putU16(out, CLARIFIER, Math.round(it.toDouble() * 100).toInt()) }
        changed(SCRAMBLER)?.let { out[SCRAMBLER_AT] = it.toInt().toByte() }
        return out
    }

    /** [channel] as it reads back after [encode] into an empty slot. */
    fun normalize(channel: Channel): Channel {
        val rx = channel.rxFreqHz / 10 * 10
        // The HF receiver's modes never transmit, whatever the channel says.
        val tx = if (channel.mode.receiveOnly) null else channel.txFreqHz?.let { it / 10 * 10 }
        val (duplex, shift) = duplexOf(rx, tx)
        return channel.copy(
            rxFreqHz = rx,
            duplex = duplex,
            offsetHz = shift,
            power = if (duplex == Duplex.OFF) Power.HIGH else NicFw950Profile.nearestPower(channel.power),
            scanAdd = true,
            pttIdCode = 0,
            name = NicFw950Profile.sanitizeName(channel.name),
            mode = NicFw950Profile.nearestMode(channel.mode),
            // Without a bandwidth option, wide or narrow is what gets written.
            options = NicFw950Profile.completeOptions(
                mapOf(BANDWIDTH.key to if (channel.wide) "0" else "1") + channel.options,
            ),
        ).let { it.copy(wide = it.options[BANDWIDTH.key] != "1") }
    }

    /**
     * Group letters from the stored word: each byte 1 to 26 is a letter; 0 (and anything past Z)
     * is an unused place. Sorted, without repeats.
     */
    fun decodeGroups(word: Long): String =
        (0 until 4).map { ((word ushr (8 * it)) and 0xFF).toInt() }
            .filter { it in 1..26 }.map { 'A' + it - 1 }.toSortedSet().joinToString("")

    /** The stored word for [letters] (sorted), first letter in the highest used byte, as the RMS app packs them. */
    fun encodeGroups(letters: String): Long = letters.fold(0L) { acc, ch -> (acc shl 8) or (ch - 'A' + 1).toLong() }

    /** An inactive record made active: wide, high power, no groups, everything else off. */
    private fun template(): ByteArray = ByteArray(RECORD_SIZE).also {
        it[POWER] = POWER_HIGH.toByte()
        putU16(it, MAGIC_AT, MAGIC)
    }

    /** Clears [record] to an empty slot the way nicFW's own tools do: no frequency, no magic. */
    fun clear(record: ByteArray, offset: Int) {
        putU32(record, offset + RX, 0)
        putU32(record, offset + TX, 0)
        putU16(record, offset + MAGIC_AT, 0)
    }

    /** Decodes a subtone word. A DCS code the standard table lacks is still read as stored. */
    fun decodeSquelch(raw: Int): Squelch = when {
        raw == 0 || raw == 0xFFFF -> Squelch.None
        raw and DCS_FLAG != 0 -> {
            val octal = raw and DCS_CODE_MASK
            Squelch.Dcs(Integer.toOctalString(octal).toInt(), inverted = raw and DCS_INVERTED != 0)
        }
        else -> Squelch.Ctcss(raw)
    }

    /** Encodes a subtone word. Throws for a value the field cannot hold. */
    fun encodeSquelch(squelch: Squelch): Int = when (squelch) {
        Squelch.None -> 0
        is Squelch.Ctcss -> {
            require(squelch.tenths in 1 until DCS_INVERTED) { "CTCSS ${squelch.tenths} is out of range" }
            squelch.tenths
        }
        is Squelch.Dcs -> {
            val octal = squelch.code.toString().toIntOrNull(8)
            require(octal != null && octal in 1..DCS_CODE_MASK) { "DCS code ${squelch.code} is not supported" }
            DCS_FLAG or octal or if (squelch.inverted) DCS_INVERTED else 0
        }
    }

    /** The name at [offset]: printable ASCII up to the first 0, trailing spaces dropped. */
    fun decodeName(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until NAME_BYTES) {
            val b = bytes[offset + i].toInt() and 0xFF
            if (b == 0) break
            sb.append(if (b in 0x20..0x7E) b.toChar() else ' ')
        }
        return sb.toString().trimEnd()
    }

    private fun encodeName(name: String, out: ByteArray) {
        for (i in 0 until NAME_BYTES) out[NAME_OFFSET + i] = if (i < name.length) name[i].code.toByte() else 0
    }

    /** Power for a transmitting channel's byte; a byte past "very high" reads as high. */
    private fun powerOf(b: Int): Power = POWERS.getOrElse(b - 1) { Power.HIGH }

    private fun powerByte(p: Power): Int = POWERS.indexOf(NicFw950Profile.nearestPower(p)) + 1

    private fun duplexOf(rx: Long, tx: Long?): Pair<Duplex, Long> = when {
        tx == null -> Duplex.OFF to 0L
        tx == rx -> Duplex.SIMPLEX to 0L
        kotlin.math.abs(rx - tx) > ChannelValidator.MAX_OFFSET_HZ -> Duplex.SPLIT to tx
        rx > tx -> Duplex.MINUS to rx - tx
        else -> Duplex.PLUS to tx - rx
    }

    private fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()

    internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    internal fun u32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    internal fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v ushr 8).toByte()
    }

    internal fun putU32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }
}
