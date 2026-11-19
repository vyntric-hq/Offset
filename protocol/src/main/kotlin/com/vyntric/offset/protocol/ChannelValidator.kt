/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

/** Checks a channel against the limits of a radio model. */
object ChannelValidator {

    enum class Field { FREQUENCY, OFFSET, TX_FREQUENCY, NAME, TX_TONE, RX_TONE, POWER, PTT_ID_CODE, MODE, OPTION }

    /** [optionKey] names the [ChannelOption] for a [Field.OPTION] problem. */
    data class Problem(val field: Field, val message: String, val optionKey: String? = null)

    /** Largest shift CHIRP still treats as a +/- offset; anything larger is a split. */
    const val MAX_OFFSET_HZ = 70_000_000L

    fun validate(channel: Channel, model: RadioProfile): List<Problem> {
        val problems = ArrayList<Problem>()
        if (!model.inBand(channel.rxFreqHz)) {
            problems += Problem(Field.FREQUENCY, "Receive frequency must be within ${bands(model)}.")
        }
        if (model.fineTuning) {
            if (channel.rxFreqHz % 10L != 0L) problems += Problem(Field.FREQUENCY, "Frequency must be a multiple of 10 Hz.")
        } else if (channel.rxFreqHz % 2_500L != 0L && channel.rxFreqHz % 6_250L != 0L) {
            problems += Problem(Field.FREQUENCY, "Frequency must be a multiple of 2.5 kHz or 6.25 kHz.")
        }
        when (channel.duplex) {
            Duplex.PLUS, Duplex.MINUS -> if (channel.offsetHz <= 0 || channel.offsetHz > MAX_OFFSET_HZ) {
                problems += Problem(Field.OFFSET, "Offset must be between 0 and 70 MHz. Use split for larger shifts.")
            }
            else -> Unit
        }
        val tx = channel.txFreqHz
        if (tx != null && channel.duplex != Duplex.SIMPLEX && !model.inBand(tx)) {
            problems += Problem(Field.TX_FREQUENCY, "Transmit frequency must be within ${bands(model)}.")
        }
        if (channel.name.length > model.nameLength) {
            problems += Problem(Field.NAME, "Names are at most ${model.nameLength} characters.")
        }
        val shown = if (model.uppercaseNames) channel.name.uppercase() else channel.name
        if (shown.any { it !in model.nameCharset }) {
            problems += Problem(
                Field.NAME,
                if (model.nameCharset == ChannelCodec.NAME_CHARSET) "Names may use A to Z, 0 to 9, space and !@#\$%^&*()+-=[]:\";'<>?,./"
                else "Names may use letters, digits, space and ASCII punctuation.",
            )
        }
        tone(channel.txSquelch)?.let { problems += Problem(Field.TX_TONE, it) }
        tone(channel.rxSquelch)?.let { problems += Problem(Field.RX_TONE, it) }
        if (channel.power !in model.powerLevels) {
            problems += Problem(Field.POWER, "${model.displayName} has no ${channel.power.label} power level.")
        }
        if (model.pttIdCodes > 0 && channel.pttIdCode !in 0 until model.pttIdCodes) {
            problems += Problem(Field.PTT_ID_CODE, "PTT-ID code must be 1 to ${model.pttIdCodes}.")
        }
        if (channel.digitalMode == null && channel.mode !in model.modes) {
            problems += Problem(Field.MODE, "${model.displayName} has no ${channel.mode.label} mode.")
        } else if (channel.mode.receiveOnly && channel.duplex != Duplex.OFF) {
            problems += Problem(Field.MODE, "${channel.mode.label} only receives. Set transmit to off.")
        }
        for (option in model.channelOptions) {
            val value = channel.options[option.key] ?: continue
            option.check(value)?.let { problems += Problem(Field.OPTION, it, option.key) }
        }
        return problems
    }

    private fun tone(s: Squelch): String? = when (s) {
        Squelch.None -> null
        is Squelch.Ctcss -> if (s.tenths in 600..2600) null else "CTCSS tone must be between 60.0 and 260.0 Hz."
        is Squelch.Dcs -> if (Tones.isValidDcs(s.code)) null else "D${Tones.formatDcs(s.code)} is not a supported DCS code."
    }

    private fun bands(model: RadioProfile): String {
        fun mhz(v: Long) = (v / 1_000_000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        return model.bands.joinToString(", ", limit = 6) { "${mhz(it.first)} to ${mhz(it.last + 1)} MHz" }
            .let { if (model.bands.size == 2) it.replace(", ", " or ") else it }
    }
}
