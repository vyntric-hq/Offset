/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

/** What the radio transmits or listens for on one side of a channel. */
sealed interface Squelch {
    /** Carrier squelch: no tone. */
    data object None : Squelch

    /** CTCSS tone in tenths of a hertz, for example 1000 for 100.0 Hz. */
    data class Ctcss(val tenths: Int) : Squelch

    /** DCS code (for example 23 for D023N) and polarity. */
    data class Dcs(val code: Int, val inverted: Boolean = false) : Squelch
}

/** Transmit offset direction. Mirrors CHIRP's duplex values. */
enum class Duplex(val chirp: String) {
    SIMPLEX(""), PLUS("+"), MINUS("-"), SPLIT("split"), OFF("off");

    companion object {
        fun fromChirp(value: String): Duplex = entries.firstOrNull { it.chirp == value.trim().lowercase() } ?: SIMPLEX
    }
}

/**
 * Power setting, highest first. Dual-power radios use HIGH and LOW; tri-power radios add MID.
 * nicFW950 adds VERY_HIGH, VERY_LOW and ZERO (a transmit cycle with almost no output, for
 * testing PTT-ID and roger beeps). Each profile lists the levels its radio has.
 */
enum class Power(val label: String) {
    VERY_HIGH("Very high"), HIGH("High"), MID("Med"), LOW("Low"), VERY_LOW("Very low"), ZERO("Zero"),
}

/**
 * How a channel is received (and sent). [chirp] is CHIRP's Mode column for it. A [receiveOnly]
 * mode is one the radio only listens with (nicFW's second receiver for HF and broadcast FM), so
 * such a channel has transmit off. Each profile lists the modes its radio stores.
 */
enum class Modulation(val label: String, val chirp: String, val receiveOnly: Boolean = false) {
    FM("FM", "FM"),
    AM("AM", "AM"),
    /** Double sideband, nicFW's AM without the carrier. CHIRP has no DSB; it exports as AM. */
    DSB("DSB", "AM"),
    /** nicFW: the mode its band plan sets for the frequency. */
    AUTO("Auto", "Auto"),
    WFM("Broadcast FM", "WFM", receiveOnly = true),
    TUNER_AM("AM (HF receiver)", "AM", receiveOnly = true),
    LSB("LSB", "LSB", receiveOnly = true),
    USB("USB", "USB", receiveOnly = true),
    CW("CW", "CW", receiveOnly = true),
}

enum class PttId(val label: String) { OFF("Off"), BOT("BOT"), EOT("EOT"), BOTH("Both") }

/**
 * One memory channel, independent of the byte layout.
 *
 * Frequencies are in hertz. When [duplex] is [Duplex.SPLIT], [offsetHz] holds the absolute
 * transmit frequency (CHIRP convention); otherwise it is the shift magnitude.
 */
data class Channel(
    val rxFreqHz: Long,
    val duplex: Duplex = Duplex.SIMPLEX,
    val offsetHz: Long = 0,
    val txSquelch: Squelch = Squelch.None,
    val rxSquelch: Squelch = Squelch.None,
    val power: Power = Power.HIGH,
    val wide: Boolean = true,
    val scanAdd: Boolean = true,
    val busyLockout: Boolean = false,
    val pttId: PttId = PttId.OFF,
    /** PTT-ID code slot, 0 based (the radio shows 1 to 15). */
    val pttIdCode: Int = 0,
    val name: String = "",
    /**
     * Set when the radio stores this channel in a digital mode Offset does not program (for
     * example "DMR"). Such a channel is shown and kept byte for byte, never edited.
     */
    val digitalMode: String? = null,
    val mode: Modulation = Modulation.FM,
    /**
     * Settings only some radios store in a channel (scan groups, scrambler, clarifier), keyed by
     * [ChannelOption.key], as declared by the radio's [RadioProfile.channelOptions]. A key that is
     * missing means "keep what the slot already holds" (or the radio's default in an empty slot),
     * so a channel imported from a file never wipes settings Offset was not told about.
     */
    val options: Map<String, String> = emptyMap(),
) {
    /** Transmit frequency in hertz, or null when transmit is inhibited. */
    val txFreqHz: Long?
        get() = when (duplex) {
            Duplex.SIMPLEX -> rxFreqHz
            Duplex.PLUS -> rxFreqHz + offsetHz
            Duplex.MINUS -> rxFreqHz - offsetHz
            Duplex.SPLIT -> offsetHz
            Duplex.OFF -> null
        }

    /** CHIRP's tone mode classification for this channel's squelch pair. */
    val toneMode: ToneMode
        get() = ToneMode.of(txSquelch, rxSquelch)
}

/** CHIRP-style tone mode, used for CSV interchange and for display. */
enum class ToneMode(val chirp: String) {
    NONE(""), TONE("Tone"), TSQL("TSQL"), DTCS("DTCS"), CROSS("Cross");

    companion object {
        fun of(tx: Squelch, rx: Squelch): ToneMode = when {
            tx is Squelch.None && rx is Squelch.None -> NONE
            tx is Squelch.Ctcss && rx is Squelch.None -> TONE
            tx is Squelch.Ctcss && rx is Squelch.Ctcss && tx.tenths == rx.tenths -> TSQL
            tx is Squelch.Dcs && rx is Squelch.Dcs && tx.code == rx.code -> DTCS
            else -> CROSS
        }
    }
}

/** Bands the app treats as amateur repeater bands. */
enum class Band(val label: String, val range: LongRange) {
    TWO_METER("2m", 144_000_000L..148_000_000L),
    SEVENTY_CM("70cm", 420_000_000L..450_000_000L);

    companion object {
        fun of(freqHz: Long): Band? = entries.firstOrNull { freqHz in it.range }
    }
}
