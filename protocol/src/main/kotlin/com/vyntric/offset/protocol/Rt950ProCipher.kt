/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol from Hertzz58/Radtel-RT950-Pro-Firmware (tools/cps_flash.py), GPL-3.0.
 * Key frame and XOR rules cross checked against NathanBarguss/Chirp_Radtel-RT-950-Pro
 * (rt950pro/transport.py), MIT, Copyright (c) 2025 Nathan G. Barguss, 2E0NBS.
 */
package com.vyntric.offset.protocol

import kotlin.random.Random

/**
 * The RT-950 Pro clone session cipher.
 *
 * After the model query the host sends a 25 byte key frame: `"SEND"`, a selector byte
 * `(1 or 2) shl 4 or (0..4)`, 19 random table indexes (0..19) and a zero byte. The selector
 * points at one of the random bytes, and that byte picks one of 20 four byte keys. Every block
 * payload in both directions is then XORed with the key, cycling from key byte 0 at the start of
 * each block, except where the key byte is a space (0x20) or the data byte is 0x00, 0xFF, the key
 * byte or the key byte XOR 0xFF. Those rules make the transform its own inverse.
 */
object Rt950ProCipher {
    const val FRAME_SIZE = 25
    const val KEY_SIZE = 4
    private val MAGIC = "SEND".toByteArray(Charsets.US_ASCII)

    /** The OEM software's key table (tblEncrySymbol), in index order. */
    val KEYS: List<ByteArray> = listOf(
        "BHT ", "CO 7", "A ES", " EIY", "M PQ", "XN Y", "RVB ", " HQP", "W RC", "MS N",
        " SAT", "K DH", "ZO R", "C SL", "6RB ", " JCG", "PN V", "J PK", "EK L", "I LZ",
    ).map { it.toByteArray(Charsets.US_ASCII) }

    /** Builds a key frame from [random], as the OEM software does. */
    fun newFrame(random: Random): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        MAGIC.copyInto(frame)
        frame[4] = ((random.nextInt(1, 3) shl 4) or random.nextInt(0, 5)).toByte()
        for (i in 5 until FRAME_SIZE - 1) frame[i] = random.nextInt(0, KEYS.size).toByte()
        return frame
    }

    /**
     * The key [frame] selects, exactly as the radio derives it, or null when the frame is not a
     * well formed key frame (wrong magic, selector outside the two ranges, index outside the table).
     */
    fun keyFor(frame: ByteArray): ByteArray? {
        if (frame.size != FRAME_SIZE || !frame.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        val selector = frame[4].toInt() and 0xFF
        val position = when (selector) {
            in 0x20..0x24 -> (selector - 0x20) * 2 + 1
            in 0x10..0x14 -> (selector - 0x10) * 2
            else -> return null
        } + 1
        val index = frame[4 + position].toInt() and 0xFF
        return KEYS.getOrNull(index)?.copyOf()
    }

    /** Encrypts or decrypts one block payload with [key] (the transform is symmetric). */
    fun apply(data: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        return ByteArray(data.size) { i ->
            val b = data[i].toInt() and 0xFF
            val k = key[i % KEY_SIZE].toInt() and 0xFF
            val skip = k == 0x20 || b == 0x00 || b == 0xFF || b == k || b == (k xor 0xFF)
            (if (skip) b else b xor k).toByte()
        }
    }
}
