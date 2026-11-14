/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

/** Radio service a repeater or channel belongs to. */
enum class Service(val label: String) { AMATEUR("Amateur"), GMRS("GMRS") }

/** GMRS and FRS channel plan (47 CFR 95.1763 and 95.563). */
object Gmrs {
    /** Channels 1 to 7: 462.5625 to 462.7125 MHz (shared with FRS). */
    val INTERSTITIAL_462: List<Long> = (0..6).map { 462_562_500L + it * 25_000L }

    /** Channels 8 to 14: 467.5625 to 467.7125 MHz (FRS only, 0.5 W, narrow). */
    val FRS_467: List<Long> = (0..6).map { 467_562_500L + it * 25_000L }

    /** Channels 15 to 22: 462.550 to 462.725 MHz; also the repeater outputs. */
    val MAIN_462: List<Long> = (0..7).map { 462_550_000L + it * 25_000L }

    /** Repeater inputs: 467.550 to 467.725 MHz (main channel + 5 MHz). */
    val REPEATER_INPUTS: List<Long> = MAIN_462.map { it + 5_000_000L }

    private val all: Set<Long> = (INTERSTITIAL_462 + FRS_467 + MAIN_462 + REPEATER_INPUTS).toSet()

    /** True for any GMRS or FRS channel frequency, within 1 kHz. */
    fun isGmrs(freqHz: Long): Boolean = all.any { kotlin.math.abs(it - freqHz) <= 1_000L }

    fun serviceOf(outputHz: Long): Service = if (isGmrs(outputHz)) Service.GMRS else Service.AMATEUR
}
