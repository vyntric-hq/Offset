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

    /**
     * False when the radio's bandwidth is one of its [channelOptions] (more than wide and narrow),
     * so the editor hides the shared wide/narrow switch.
     */
    val storesWide: Boolean get() = true

    /** Modes the radio stores in a channel, the usual one first. */
    val modes: List<Modulation> get() = listOf(Modulation.FM)

    /** Settings beyond the shared ones that this radio stores in each channel, in editor order. */
    val channelOptions: List<ChannelOption> get() = emptyList()

    /** How many PTT-ID codes a channel can pick from (shown 1 to n); 0 when channels have no code. */
    val pttIdCodes: Int get() = 15

    /**
     * True when channel frequencies may be any multiple of 10 Hz (needed for SSB and CW);
     * otherwise they must be multiples of 2.5 or 6.25 kHz.
     */
    val fineTuning: Boolean get() = false

    /** Nominal watts per level, used for CHIRP CSV export (CHIRP parses watts). */
    fun watts(power: Power): Double

    fun inBand(freqHz: Long): Boolean = bands.any { freqHz in it }

    /** Drops characters the radio cannot show (as spaces), applies case, then truncates. */
    fun sanitizeName(name: String): String =
        (if (uppercaseNames) name.uppercase() else name)
            .map { if (it in nameCharset) it else ' ' }.joinToString("")
            .trimEnd().take(nameLength).trimEnd()

    /** Maps a power level the model lacks to the nearest one it has, the lower one on a tie. */
    fun nearestPower(power: Power): Power =
        if (power in powerLevels) power
        else powerLevels.minWith(compareBy<Power>({ kotlin.math.abs(it.ordinal - power.ordinal) }, { -it.ordinal }))

    /**
     * Every option this radio stores, taken from [options] where set and the default otherwise:
     * what a channel reads back as after it is written into an empty slot.
     */
    fun completeOptions(options: Map<String, String>): Map<String, String> =
        channelOptions.associate { it.key to (options[it.key] ?: it.default) }

    /** Only the [options] this radio stores ([channelOptions] keys); others belong to other radios. */
    fun ownOptions(options: Map<String, String>): Map<String, String> {
        val keys = channelOptions.map { it.key }.toSet()
        return options.filterKeys { it in keys }
    }

    /** Maps a mode the model lacks to one it has: AM-like modes to AM where it can, else the usual mode. */
    fun nearestMode(mode: Modulation): Modulation = when {
        mode in modes -> mode
        mode in setOf(Modulation.DSB, Modulation.TUNER_AM) && Modulation.AM in modes -> Modulation.AM
        else -> modes.first()
    }

    companion object {
        /** Printable ASCII, for radios with a full character set. */
        val ASCII: String = (0x20..0x7E).map { it.toChar() }.joinToString("")
    }
}
