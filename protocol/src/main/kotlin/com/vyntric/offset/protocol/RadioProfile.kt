/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

/** What a radio model accepts in a channel: bands, power levels and names. */
interface RadioProfile {
    val displayName: String

    /** Frequency ranges the radio tunes, each half open (`[low, high)`) as in CHIRP. */
    val bands: List<LongRange>

    /** Power levels the model has, highest first. */
    val powerLevels: List<Power>

    /** Longest channel name, in characters. */
    val nameLength: Int

    /** Characters the radio can show in a name. */
    val nameCharset: String

    /** True when the radio shows names in capitals only, so names are uppercased. */
    val uppercaseNames: Boolean

    /** False when the radio has no per channel scan setting (it scans some other way), so Offset hides it. */
    val storesScanAdd: Boolean get() = true

    /** Nominal watts per level, used for CHIRP CSV export (CHIRP parses watts). */
    fun watts(power: Power): Double

    fun inBand(freqHz: Long): Boolean = bands.any { freqHz in it }

    /** Drops characters the radio cannot show (as spaces), applies case, then truncates. */
    fun sanitizeName(name: String): String =
        (if (uppercaseNames) name.uppercase() else name)
            .map { if (it in nameCharset) it else ' ' }.joinToString("")
            .trimEnd().take(nameLength).trimEnd()

    /** Maps a power level the model lacks to the nearest one it has. */
    fun nearestPower(power: Power): Power = when {
        power in powerLevels -> power
        power == Power.MID -> powerLevels.firstOrNull { it == Power.LOW } ?: powerLevels.last()
        else -> powerLevels.last()
    }

    companion object {
        /** Printable ASCII, for radios with a full character set. */
        val ASCII: String = (0x20..0x7E).map { it.toChar() }.joinToString("")
    }
}
