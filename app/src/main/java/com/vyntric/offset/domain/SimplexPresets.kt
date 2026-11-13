/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Power
import com.vyntric.offset.protocol.Squelch

/** Ready made channel sets that are the same everywhere in the US. */
object SimplexPresets {
    data class Preset(val id: String, val title: String, val description: String, val channels: List<Channel>) {
        val includesGmrs: Boolean get() = channels.any { Gmrs.isGmrs(it.rxFreqHz) }
    }

    private fun simplex(mhz: Double, name: String, wide: Boolean = true, power: Power = Power.HIGH) =
        Channel(rxFreqHz = Math.round(mhz * 1_000_000), name = name, wide = wide, power = power)

    val CALLING = Preset(
        "calling", "National calling",
        "146.520 (2m) and 446.000 (70cm) national simplex calling frequencies.",
        listOf(simplex(146.520, "CALL 2M"), simplex(446.000, "CALL70")),
    )

    val SIMPLEX_2M = Preset(
        "simplex-2m", "2m simplex",
        "Common 2m FM simplex frequencies (ARRL band plan).",
        listOf(146.520, 146.550, 146.580, 147.420, 147.450, 147.480, 147.510, 147.540, 147.570)
            .map { simplex(it, "S" + String.format(java.util.Locale.US, "%.2f", it).takeLast(6)) },
    )

    val SIMPLEX_70CM = Preset(
        "simplex-70cm", "70cm simplex",
        "Common 70cm FM simplex frequencies.",
        listOf(446.000, 446.025, 446.050, 446.075, 446.100, 446.125, 446.150, 446.175)
            .map { simplex(it, "S" + String.format(java.util.Locale.US, "%.3f", it).takeLast(6)) },
    )

    /** NOAA weather radio: receive only, so the radio can never transmit there. */
    val NOAA = Preset(
        "noaa", "NOAA weather",
        "WX1 to WX7 (162.400 to 162.550 MHz), receive only.",
        listOf(162.550, 162.400, 162.475, 162.425, 162.450, 162.500, 162.525).mapIndexed { i, f ->
            Channel(rxFreqHz = Math.round(f * 1_000_000), duplex = Duplex.OFF, name = "WX${i + 1}", wide = false)
        },
    )

    /** FRS/GMRS 1 to 22: 1 to 7 and 15 to 22 wide; 8 to 14 narrow and low power. */
    val FRS_GMRS = Preset(
        "frs-gmrs", "FRS/GMRS 1 to 22",
        "The 22 shared channels. Channels 8 to 14 are low power and narrow. Transmitting on GMRS needs a license and a certified radio.",
        Gmrs.INTERSTITIAL_462.mapIndexed { i, f -> Channel(f, name = "GMRS${i + 1}") } +
            Gmrs.FRS_467.mapIndexed { i, f -> Channel(f, name = "FRS${i + 8}", wide = false, power = Power.LOW) } +
            Gmrs.MAIN_462.mapIndexed { i, f -> Channel(f, name = "GMRS${i + 15}") },
    )

    /** GMRS repeater pairs 15R to 22R: output 462.550 to 462.725, input +5 MHz, no tone. */
    val GMRS_REPEATERS = Preset(
        "gmrs-repeaters", "GMRS repeater pairs",
        "15R to 22R with a +5 MHz offset and no tone. Set each repeater's tone after loading.",
        Gmrs.MAIN_462.mapIndexed { i, f -> Channel(f, Duplex.PLUS, 5_000_000, Squelch.None, name = "GMRS${i + 15}R") },
    )

    val ALL = listOf(CALLING, SIMPLEX_2M, SIMPLEX_70CM, NOAA, FRS_GMRS, GMRS_REPEATERS)
}
