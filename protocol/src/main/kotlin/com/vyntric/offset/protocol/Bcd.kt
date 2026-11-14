/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Frequency encoding ported from CHIRP (chirp/bitwise.py "lbcd"), GPL.
 */
package com.vyntric.offset.protocol

/**
 * Little-endian packed BCD as used by the UV-5R for frequencies ("lbcd" in CHIRP).
 * Byte 0 holds the two least significant digits, high nibble first.
 *
 * Example: 146.520 MHz is stored in 10 Hz units as 14652000, which encodes as
 * `00 20 65 14`.
 */
object Bcd {
    /** Decodes [length] bytes at [offset]. Returns null when any nibble is not a decimal digit. */
    fun decodeLittleEndian(bytes: ByteArray, offset: Int, length: Int): Long? {
        var value = 0L
        for (i in length - 1 downTo 0) {
            val b = bytes[offset + i].toInt() and 0xFF
            val hi = b ushr 4
            val lo = b and 0x0F
            if (hi > 9 || lo > 9) return null
            value = value * 100 + hi * 10 + lo
        }
        return value
    }

    /** Encodes a non-negative [value] into [length] bytes at [offset]. */
    fun encodeLittleEndian(value: Long, bytes: ByteArray, offset: Int, length: Int) {
        require(value >= 0) { "BCD value must be non-negative: $value" }
        var remaining = value
        for (i in 0 until length) {
            val pair = (remaining % 100).toInt()
            bytes[offset + i] = (((pair / 10) shl 4) or (pair % 10)).toByte()
            remaining /= 100
        }
        require(remaining == 0L) { "BCD value $value does not fit in $length bytes" }
    }
}
