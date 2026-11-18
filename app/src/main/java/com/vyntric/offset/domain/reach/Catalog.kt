/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain.reach

import com.vyntric.offset.domain.reach.ReachBand.GMRS
import com.vyntric.offset.domain.reach.ReachBand.ONE_TWENTY_FIVE
import com.vyntric.offset.domain.reach.ReachBand.SEVENTY_CM
import com.vyntric.offset.domain.reach.ReachBand.SIX_METER
import com.vyntric.offset.domain.reach.ReachBand.TWO_METER

/**
 * Common radios and antennas, so a station takes a few taps to set up. Powers are the
 * manufacturers' high power figures; gains are typical published figures, converted to dBi
 * (dBd + 2.15). Sources and caveats are in docs/ASSUMPTIONS.md. Every value can be edited.
 */
object Catalog {
    private fun radio(key: String, name: String, kind: RadioKind, watts: Map<ReachBand, Double>, levels: List<Double>) =
        Radio(name = name, kind = kind, maxWatts = watts, levels = levels, catalogKey = key)

    private fun uv(w: Double) = mapOf(TWO_METER to w, SEVENTY_CM to w)

    val radios: List<Radio> = listOf(
        radio("uv5r", "Baofeng UV-5R", RadioKind.HANDHELD, uv(4.0), listOf(4.0, 1.0)),
        radio("bf-f8hp", "Baofeng BF-F8HP", RadioKind.HANDHELD, uv(8.0), listOf(8.0, 4.0, 1.0)),
        radio("dm32uv", "Baofeng DM-32UV", RadioKind.HANDHELD, uv(5.0), listOf(5.0, 2.5, 1.0)),
        radio("rt950pro", "Radtel RT-950 Pro", RadioKind.HANDHELD, uv(10.0), listOf(10.0, 5.0, 1.0)),
        radio("ft60r", "Yaesu FT-60R", RadioKind.HANDHELD, uv(5.0), listOf(5.0, 2.0, 0.5)),
        radio("thd75", "Kenwood TH-D75", RadioKind.HANDHELD, mapOf(TWO_METER to 5.0, ONE_TWENTY_FIVE to 5.0, SEVENTY_CM to 5.0), listOf(5.0, 2.0, 0.5)),
        radio("id52", "Icom ID-52A", RadioKind.HANDHELD, uv(5.0), listOf(5.0, 2.5, 1.0, 0.5)),
        radio("ht5", "Other handheld (5 W)", RadioKind.HANDHELD, uv(5.0), listOf(5.0, 1.0)),
        radio("gmrs-ht", "GMRS handheld (5 W)", RadioKind.HANDHELD, mapOf(GMRS to 5.0), listOf(5.0, 1.0)),
        radio("ftm400", "Yaesu FTM-400", RadioKind.MOBILE, mapOf(TWO_METER to 50.0, SEVENTY_CM to 50.0), listOf(50.0, 20.0, 5.0)),
        radio("mobile50", "Other mobile (50 W)", RadioKind.MOBILE, mapOf(TWO_METER to 50.0, SEVENTY_CM to 40.0), listOf(50.0, 25.0, 10.0, 5.0)),
        radio("gmrs-mobile", "GMRS mobile (50 W)", RadioKind.MOBILE, mapOf(GMRS to 50.0), listOf(50.0, 20.0, 5.0)),
        radio("x6200", "Xiegu X6200 (6 m only here)", RadioKind.BASE, mapOf(SIX_METER to 8.0), listOf(8.0, 5.0, 1.0)),
    )

    private fun antenna(key: String, name: String, kind: AntennaKind, gains: Map<ReachBand, Double>) =
        Antenna(name = name, kind = kind, gainDbi = gains, catalogKey = key)

    val antennas: List<Antenna> = listOf(
        antenna("stock-duck", "Stock rubber duck", AntennaKind.HANDHELD_WHIP, mapOf(TWO_METER to -4.0, SEVENTY_CM to -2.0)),
        antenna("na771", "Nagoya NA-771 whip", AntennaKind.HANDHELD_WHIP, mapOf(TWO_METER to 0.0, SEVENTY_CM to 1.0)),
        antenna("gmrs-stock", "GMRS stock antenna", AntennaKind.HANDHELD_WHIP, mapOf(GMRS to -1.0)),
        antenna("rollup-jpole", "Roll-up J-pole (slim jim)", AntennaKind.J_POLE, mapOf(TWO_METER to 2.15, SEVENTY_CM to 2.15)),
        antenna("quarter-mag", "Quarter-wave mag mount", AntennaKind.MOBILE, mapOf(TWO_METER to 2.15, SEVENTY_CM to 2.15, GMRS to 2.15)),
        antenna("five-eighth", "5/8-wave mobile", AntennaKind.MOBILE, mapOf(TWO_METER to 5.0, SEVENTY_CM to 7.0)),
        antenna("jpole", "J-pole", AntennaKind.J_POLE, mapOf(TWO_METER to 2.15, SEVENTY_CM to 2.15)),
        antenna("x50", "Dual-band collinear, 1.7 m (X50 class)", AntennaKind.COLLINEAR, mapOf(TWO_METER to 6.65, SEVENTY_CM to 9.35)),
        antenna("x510", "Dual-band collinear, 5.2 m (X510 class)", AntennaKind.COLLINEAR, mapOf(TWO_METER to 10.45, SEVENTY_CM to 13.85)),
        antenna("gmrs-base", "GMRS base collinear", AntennaKind.COLLINEAR, mapOf(GMRS to 6.0)),
        antenna("yagi3", "3-element 2 m Yagi", AntennaKind.YAGI, mapOf(TWO_METER to 7.0)),
        antenna("yagi5-70", "5-element 70 cm Yagi", AntennaKind.YAGI, mapOf(SEVENTY_CM to 9.0)),
        antenna("six-vertical", "6 m half-wave vertical", AntennaKind.VERTICAL, mapOf(SIX_METER to 2.15)),
    )

    /** Common antenna heights above ground, for one-tap choices. */
    val heightPresets: List<Pair<String, Double>> = listOf(
        "In hand" to 1.5,
        "Car roof" to 1.6,
        "Second floor" to 6.0,
        "Mast" to 10.0,
        "Tower" to 20.0,
    )

    /** Antennas sensible to suggest for [radio]: covering a band it has, handheld ones first for handhelds. */
    fun antennasFor(radio: Radio): List<Antenna> {
        val fits = antennas.filter { a -> radio.bands.any { a.covers(it) } }
        val preferred = when (radio.kind) {
            RadioKind.HANDHELD -> setOf(AntennaKind.HANDHELD_WHIP, AntennaKind.J_POLE)
            RadioKind.MOBILE -> setOf(AntennaKind.MOBILE)
            RadioKind.BASE -> setOf(AntennaKind.COLLINEAR, AntennaKind.J_POLE, AntennaKind.YAGI, AntennaKind.VERTICAL)
        }
        return fits.sortedBy { if (it.kind in preferred) 0 else 1 }
    }

    /** A first guess at height for a new station with [radio]. */
    fun defaultHeightM(radio: Radio): Double = when (radio.kind) {
        RadioKind.HANDHELD -> 1.5
        RadioKind.MOBILE -> 1.6
        RadioKind.BASE -> 10.0
    }
}
