/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py get_memory, set_memory, TONE_MODEL;
 * chirp/kenwood_tone.py), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.CH_SIZE
import com.vyntric.offset.protocol.Dm32uvLayout.isAll
import com.vyntric.offset.protocol.Dm32uvLayout.u16le
import com.vyntric.offset.protocol.Dm32uvLayout.putU16le
import com.vyntric.offset.protocol.Dm32uvLayout.u8

/**
 * The 0x30 byte DM-32UV channel record (CHIRP's `struct chan`).
 *
 * Bit fields are MSB first, as CHIRP's bitwise declares them. Fields Offset models:
 * ```
 * 0x00 name[16]   0x10 rxfreq lbcd[4] (10 Hz)   0x14 txfreq lbcd[4] (FF = TX off)
 * 0x18 chtype:4 forbid_tx:1 power:2 lone_work:1     chtype 1 and 3 are digital (DMR)
 * 0x19 wide:1 auto_scan:1 scanlist:6
 * 0x1A forbid_talkaround:1 tx_admit:3 ...           analog: Allow TX, Channel Idle, Match CTC, Non Match CTC
 * 0x21 rxtone ul16   0x23 txtone ul16               Kenwood tone model, see [decodeTone]
 * 0x29 step:4 ptt_id:2 unknown:2
 * ```
 * Everything else (DMR settings, signaling, APRS, the unused offset field) is kept as it is.
 */
internal object Dm32uvCodec {
    /** [Channel.digitalMode] of channel types 1 and 3. */
    const val DMR = "DMR"

    private const val NAME = 0x00
    private const val RX = 0x10
    private const val TX = 0x14
    private const val TYPE_POWER = 0x18
    private const val WIDE = 0x19
    private const val ADMIT = 0x1A
    private const val RX_TONE = 0x21
    private const val TX_TONE = 0x23
    private const val STEP_PTT = 0x29

    /** CHIRP's split rule: a shift larger than this is stored as split. */
    private const val MAX_SHIFT_HZ = 70_000_000L

    /** Index of the 5 kHz step in CHIRP's STEPS, the step of a new CHIRP memory. */
    private const val STEP_5K = 1
    private const val STEP_COUNT = 8

    /** Stored power index 0, 1, 2 (CHIRP Low, Middle, High). */
    private val POWERS = listOf(Power.LOW, Power.MID, Power.HIGH)

    /** CHIRP's default record for a new channel, as on the channels the radio came with. */
    val TEMPLATE: ByteArray = ByteArray(16) { 0xFF.toByte() } + ByteArray(8) +
        Dm32uvLayout.hex("0000000030000000" + "00ffffffff000000" + "0000000000000000")

    fun isDigital(data: ByteArray, off: Int): Boolean = (data.u8(off + TYPE_POWER) ushr 4).let { it == 1 || it == 3 }

    /** True when the record holds no channel: rx all 0xFF or all 0x00 (CHIRP get_memory). */
    fun isBlank(data: ByteArray, off: Int): Boolean =
        data.isAll(0xFF, off + RX, off + RX + 4) || data.isAll(0x00, off + RX, off + RX + 4)

    /**
     * Decodes the record at [off], or null when it is empty or its receive frequency is not
     * valid BCD. The caller decides whether the slot is past the channel count.
     */
    fun decode(data: ByteArray, off: Int): Channel? {
        if (isBlank(data, off)) return null
        val rx = (Bcd.decodeLittleEndian(data, off + RX, 4) ?: return null) * 10
        val txRaw = if (data.isAll(0xFF, off + TX, off + TX + 4)) null else Bcd.decodeLittleEndian(data, off + TX, 4)
        val (duplex, offset) = splitToOffset(rx, txRaw?.let { it * 10 })
        val name = String(data, off + NAME, 16, Charsets.ISO_8859_1).trimEnd('\u0000', '\u00FF', ' ')
        val power = POWERS[minOf((data.u8(off + TYPE_POWER) ushr 1) and 3, 2)]
        if (isDigital(data, off)) {
            return Channel(rx, duplex, offset, power = power, name = name, digitalMode = DMR)
        }
        return Channel(
            rxFreqHz = rx,
            duplex = duplex,
            offsetHz = offset,
            txSquelch = decodeTone(data.u16le(off + TX_TONE)),
            rxSquelch = decodeTone(data.u16le(off + RX_TONE)),
            power = power,
            wide = data.u8(off + WIDE) and 0x80 != 0,
            busyLockout = (data.u8(off + ADMIT) ushr 4) and 7 != 0,
            pttId = PttId.entries[(data.u8(off + STEP_PTT) ushr 2) and 3],
            name = name,
        )
    }

    /**
     * Encodes analog [channel] on top of [old] (a 0x30 byte record), as CHIRP's set_memory does:
     * only the modeled fields change. [isNew] gives the step of a new CHIRP memory (5 kHz).
     */
    fun encode(channel: Channel, old: ByteArray, isNew: Boolean): ByteArray {
        require(channel.digitalMode == null) { "Offset does not encode digital channels" }
        val r = old.copyOf(CH_SIZE)
        val name = channel.name.take(16).map { if (it.code in 0..0xFF) it.code.toByte() else '?'.code.toByte() }
        for (i in 0 until 16) r[NAME + i] = name.getOrElse(i) { 0 }
        Bcd.encodeLittleEndian(channel.rxFreqHz / 10, r, RX, 4)
        val tx = channel.txFreqHz
        if (tx == null) r.fill(0xFF.toByte(), TX, TX + 4) else Bcd.encodeLittleEndian(tx / 10, r, TX, 4)
        var tp = r.u8(TYPE_POWER)
        if ((tp ushr 4) != 0 && (tp ushr 4) != 2) tp = tp and 0x0F
        tp = (tp and 0b1111_1001) or (POWERS.indexOf(channel.power) shl 1)
        r[TYPE_POWER] = tp.toByte()
        r[WIDE] = ((r.u8(WIDE) and 0x7F) or (if (channel.wide) 0x80 else 0)).toByte()
        var step = r.u8(STEP_PTT) ushr 4
        if (isNew || step >= STEP_COUNT) step = STEP_5K
        r[STEP_PTT] = ((step shl 4) or (channel.pttId.ordinal shl 2) or (r.u8(STEP_PTT) and 0x03)).toByte()
        val admit = (r.u8(ADMIT) ushr 4) and 7
        val newAdmit = when {
            !channel.busyLockout -> 0
            admit == 0 -> 1 // Channel Idle
            else -> admit // Match CTC and Non Match CTC are busy lockout variants too
        }
        r[ADMIT] = ((r.u8(ADMIT) and 0x8F) or (newAdmit shl 4)).toByte()
        r.putU16le(RX_TONE, encodeTone(channel.rxSquelch))
        r.putU16le(TX_TONE, encodeTone(channel.txSquelch))
        return r
    }

    /** CHIRP split_to_offset. A null [txHz] is transmit off. */
    private fun splitToOffset(rxHz: Long, txHz: Long?): Pair<Duplex, Long> = when {
        txHz == null -> Duplex.OFF to 0L
        kotlin.math.abs(txHz - rxHz) > MAX_SHIFT_HZ -> Duplex.SPLIT to txHz
        txHz > rxHz -> Duplex.PLUS to txHz - rxHz
        txHz < rxHz -> Duplex.MINUS to rxHz - txHz
        else -> Duplex.SIMPLEX to 0L
    }

    /**
     * CHIRP's Kenwood tone model for this radio: CTCSS is tenths of a hertz in BCD (88.5 Hz is
     * 0x0885); DCS is 0x8000 plus the code in BCD, plus 0x4000 when inverted (D754I is 0xC754);
     * 0xFFFF (or 0x0000) is no tone. Values that are not BCD decode as no tone (CHIRP fails on them).
     */
    fun decodeTone(v: Int): Squelch {
        if (v == 0xFFFF || v == 0x0000) return Squelch.None
        return if (v and 0x8000 != 0) {
            val code = Integer.toHexString(v and 0x07FF).toIntOrNull() ?: return Squelch.None
            Squelch.Dcs(code, inverted = v and 0x4000 != 0)
        } else {
            val tenths = Integer.toHexString(v and 0x7FFF).toIntOrNull() ?: return Squelch.None
            Squelch.Ctcss(tenths)
        }
    }

    fun encodeTone(s: Squelch): Int = when (s) {
        Squelch.None -> 0xFFFF
        is Squelch.Ctcss -> s.tenths.toString().toInt(16)
        is Squelch.Dcs -> (s.code.toString().toInt(16) or 0x8000) + if (s.inverted) 0x4000 else 0
    }
}
