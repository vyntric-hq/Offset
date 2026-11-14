/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Model identification ported from CHIRP (chirp/drivers/uv5r.py), GPL.
 */
package com.vyntric.offset.protocol

/**
 * A UV-5R family model: everything that differs between members of the family.
 *
 * Frequency ranges are half open (`[low, high)`) as in CHIRP.
 */
enum class RadioModel(
    override val displayName: String,
    val vendor: String,
    /** CHIRP's MODEL string, written into .img metadata. */
    val chirpModel: String,
    /** CHIRP's registered driver class name, written into .img metadata. */
    val chirpRclass: String,
    /** Firmware version substrings that identify this model (CHIRP's _basetype). */
    val baseTypes: List<String>,
    val vhfHz: LongRange,
    val uhfHz: LongRange,
    val triPower: Boolean,
    /** Characters available for channel names. All current UV-5R variants use 7. */
    override val nameLength: Int,
    /** Retail names that share this memory layout and are programmed with this profile. */
    val aliases: List<String>,
) : RadioProfile {
    UV5R(
        displayName = "UV-5R",
        vendor = "Baofeng",
        chirpModel = "UV-5R",
        chirpRclass = "BaofengUV5RGeneric",
        baseTypes = listOf("BFS", "BFB", "N5R-2", "N5R2", "N5RV", "BTS", "D5R2", "B5R2"),
        vhfHz = 130_000_000L until 176_000_000L,
        uhfHz = 400_000_000L until 520_000_000L,
        triPower = false,
        nameLength = 7,
        aliases = listOf("UV-5X", "RT5R", "RT5RV", "RT5", "RH5R", "UV-5R EX", "A-5R"),
    ),
    BF_F8HP(
        displayName = "BF-F8HP",
        vendor = "Baofeng",
        chirpModel = "BF-F8HP",
        chirpRclass = "BaofengBFF8HPRadio",
        baseTypes = listOf("BFP3V3 F", "N5R-3", "N5R3", "F5R3", "BFT", "N5RV"),
        vhfHz = 130_000_000L until 180_000_000L,
        uhfHz = 400_000_000L until 521_000_000L,
        triPower = true,
        nameLength = 7,
        aliases = listOf("UV-5R 8W", "UV-5RTP", "RT5(tri-power)", "GA-5S", "UV-5XP", "TI-F8+", "UV-5R Pro", "TS-T9+"),
    );

    /** Power levels in the order the radio encodes them (index = stored value). */
    override val powerLevels: List<Power>
        get() = if (triPower) listOf(Power.HIGH, Power.MID, Power.LOW) else listOf(Power.HIGH, Power.LOW)

    override val bands: List<LongRange> get() = listOf(vhfHz, uhfHz)

    override val nameCharset: String get() = ChannelCodec.NAME_CHARSET

    override val uppercaseNames: Boolean get() = true

    override fun watts(power: Power): Double = when {
        triPower -> when (power) { Power.HIGH -> 8.0; Power.MID -> 4.0; Power.LOW -> 1.0 }
        else -> when (power) { Power.HIGH, Power.MID -> 4.0; Power.LOW -> 1.0 }
    }

    override fun inBand(freqHz: Long): Boolean = freqHz in vhfHz || freqHz in uhfHz

    companion object {
        /**
         * Identify the model from the 14 byte firmware version string.
         *
         * "N5RV" appears in both CHIRP lists. CHIRP resolves the tie by registration order, which
         * selects the UV-5R; we do the same. Returns null when nothing matches.
         */
        fun fromFirmware(version: String): RadioModel? {
            val distinctive = { m: RadioModel -> m.baseTypes.filter { it != "N5RV" } }
            entries.firstOrNull { m -> distinctive(m).any { version.contains(it) } }?.let { return it }
            if (version.contains("N5RV")) return UV5R
            return null
        }

        fun fromChirpModel(model: String?): RadioModel? = entries.firstOrNull { it.chirpModel == model }
    }
}

/** Result of identifying an image: the model and whether the firmware string was recognized. */
data class ModelDetection(val model: RadioModel, val recognized: Boolean)
