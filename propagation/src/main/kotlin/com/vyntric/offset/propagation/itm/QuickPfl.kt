/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import kotlin.math.exp
import kotlin.math.sqrt

// Terrain profile processing: NTIA QuickPfl.cpp, FindHorizons.cpp, ComputeDeltaH.cpp and
// LinearLeastSquaresFit.cpp. Every function reads pfl[0..n+2] only and never writes to pfl.

/** Largest n in ComputeDeltaH (10 * 25 - 5); its s[] holds n + 2 values. */
internal const val DELTA_H_SAMPLES_MAX = 245

/**
 * NTIA QuickPfl: extract parameters from the terrain pfl.
 * Outputs theta_hzn, d_hzn__meter, h_e__meter in place, and delta_h__meter, d__meter in [ws].
 */
internal fun quickPfl(
    pfl: DoubleArray, gamma_e: Double, h__meter: DoubleArray, theta_hzn: DoubleArray,
    d_hzn__meter: DoubleArray, h_e__meter: DoubleArray, ws: ItmWorkspace,
) {
    var q: Double

    val d__meter = pfl[0] * pfl[1]
    ws.d = d__meter

    val np = pfl[0].toInt()

    val a_e__meter = 1 / gamma_e // effective earth radius

    findHorizons(pfl, a_e__meter, h__meter, theta_hzn, d_hzn__meter)

    // "In our own work we have sometimes said that consideration of terrain elevations should begin at a point
    // about 15 times the tower height" [Hufford, 1982] Page 25
    val d_start__meter = MIN(15.0 * h__meter[0], 0.1 * d_hzn__meter[0]) // lesser of 10% of horizon distance or 15x terminal height
    val d_end__meter = d__meter - MIN(15.0 * h__meter[1], 0.1 * d_hzn__meter[1]) // ditto, measured from the far end of the link

    val delta_h__meter = computeDeltaH(pfl, d_start__meter, d_end__meter, ws)
    ws.deltaH = delta_h__meter

    val fit = ws.fit
    if (d_hzn__meter[0] + d_hzn__meter[1] > 1.5 * d__meter) {
        // The combined horizon distance is at least 50% larger than the total path distance,
        // so we are well within the line-of-sight range

        linearLeastSquaresFit(pfl, d_start__meter, d_end__meter, fit)
        val fit_tx = fit[0]
        val fit_rx = fit[1]

        h_e__meter[0] = h__meter[0] + fdim(pfl[2], fit_tx)
        h_e__meter[1] = h__meter[1] + fdim(pfl[np + 2], fit_rx)

        for (i in 0..1) {
            d_hzn__meter[i] = sqrt(2.0 * h_e__meter[i] * a_e__meter) *
                exp(-0.07 * sqrt(delta_h__meter / MAX(h_e__meter[i], 5.0)))
        }

        val combined_horizons__meter = d_hzn__meter[0] + d_hzn__meter[1]
        if (combined_horizons__meter <= d__meter) {
            q = sq(d__meter / combined_horizons__meter)

            for (i in 0..1) {
                h_e__meter[i] = h_e__meter[i] * q
                d_hzn__meter[i] = sqrt(2.0 * h_e__meter[i] * a_e__meter) *
                    exp(-0.07 * sqrt(delta_h__meter / MAX(h_e__meter[i], 5.0)))
            }
        }

        for (i in 0..1) {
            q = sqrt(2.0 * h_e__meter[i] * a_e__meter)
            theta_hzn[i] = (0.65 * delta_h__meter * (q / d_hzn__meter[i] - 1.0) - 2.0 * h_e__meter[i]) / q
        }
    } else {
        linearLeastSquaresFit(pfl, d_start__meter, 0.9 * d_hzn__meter[0], fit)
        val fit_tx = fit[0]
        h_e__meter[0] = h__meter[0] + fdim(pfl[2], fit_tx)

        linearLeastSquaresFit(pfl, d__meter - 0.9 * d_hzn__meter[1], d_end__meter, fit)
        val fit_rx = fit[1]
        h_e__meter[1] = h__meter[1] + fdim(pfl[np + 2], fit_rx)
    }
}

/**
 * NTIA FindHorizons: the terminals' radio horizon angles (radians) and distances (metres),
 * written to theta_hzn and d_hzn__meter.
 */
internal fun findHorizons(
    pfl: DoubleArray, a_e__meter: Double, h__meter: DoubleArray, theta_hzn: DoubleArray, d_hzn__meter: DoubleArray,
) {
    val np = pfl[0].toInt()
    val xi = pfl[1]

    val d__meter = pfl[0] * pfl[1]

    // compute radials (ignore radius of earth since it cancels out in the later math)
    val z_tx__meter = pfl[2] + h__meter[0]
    val z_rx__meter = pfl[np + 2] + h__meter[1]

    // set the terminal horizon angles as if the terminals are line-of-sight [TN101, Eq 6.15]
    theta_hzn[0] = (z_rx__meter - z_tx__meter) / d__meter - d__meter / (2 * a_e__meter)
    theta_hzn[1] = -(z_rx__meter - z_tx__meter) / d__meter - d__meter / (2 * a_e__meter)

    d_hzn__meter[0] = d__meter
    d_hzn__meter[1] = d__meter

    var d_tx__meter = 0.0
    var d_rx__meter = d__meter

    var theta_tx: Double
    var theta_rx: Double

    for (i in 1 until np) {
        d_tx__meter += xi
        d_rx__meter -= xi

        theta_tx = (pfl[i + 2] - z_tx__meter) / d_tx__meter - d_tx__meter / (2 * a_e__meter)
        theta_rx = -(z_rx__meter - pfl[i + 2]) / d_rx__meter - d_rx__meter / (2 * a_e__meter)

        if (theta_tx > theta_hzn[0]) {
            theta_hzn[0] = theta_tx
            d_hzn__meter[0] = d_tx__meter
        }

        if (theta_rx > theta_hzn[1]) {
            theta_hzn[1] = theta_rx
            d_hzn__meter[1] = d_rx__meter
        }
    }
}

/**
 * NTIA ComputeDeltaH: the terrain irregularity parameter delta_h, in metres, between
 * d_start__meter and d_end__meter into the profile. Uses [ws]'s scratch arrays.
 */
internal fun computeDeltaH(pfl: DoubleArray, d_start__meter: Double, d_end__meter: Double, ws: ItmWorkspace): Double {
    val s = ws.deltaHS // Temp pfl data array, NTIA's s[247]

    val np = pfl[0].toInt()
    var x_start = d_start__meter / pfl[1] // index to start considering terrain points
    var x_end = d_end__meter / pfl[1] // index to stop considering terrain points

    // if there are less than 2 terrain points, return delta_h = 0
    if (x_end - x_start < 2.0) return 0.0

    var p10 = (0.1 * (x_end - x_start + 8.0)).toInt()
    p10 = MIN(MAX(4, p10), 25) // 10% index

    val n = 10 * p10 - 5
    val p90 = n - p10 // 90% index

    val np_s = (n - 1).toDouble()
    s[0] = np_s
    s[1] = 1.0

    x_end = (x_end - x_start) / np_s
    var i = x_start.toInt()
    x_start -= (i + 1.0).toFloat().toDouble() // NTIA: x_start -= float(i + 1.0)

    for (j in 0 until n) {
        while (x_start > 0.0 && (i + 1) < np) {
            x_start--
            i++
        }

        s[j + 2] = pfl[i + 3] + (pfl[i + 3] - pfl[i + 2]) * x_start

        x_start += x_end
    }

    val fit = ws.fit
    linearLeastSquaresFit(s, 0.0, np_s, fit)
    var fit_y1 = fit[0]
    val fit_y2 = (fit[1] - fit_y1) / np_s

    // compute the difference between fitted line and actual data
    val diffs = ws.deltaHDiffs
    for (j in 0 until n) {
        diffs[j] = s[j + 2] - fit_y1
        fit_y1 += fit_y2
    }

    // NTIA picks the p10-th largest (q10) and the (p90 + 1)-th largest (q90) with nth_element and
    // std::greater. In ascending order those sit at n - p10 and n - 1 - p90 = p10 - 1.
    diffs.sort(0, n)
    val q10 = diffs[n - p10]
    val q90 = diffs[n - 1 - p90]

    val delta_h_d__meter = q10 - q90

    // [ERL 79-ITS 67, Eqn 3], inverted
    return delta_h_d__meter / (1.0 - 0.8 * exp(-(d_end__meter - d_start__meter) / 50e3))
}

/**
 * NTIA LinearLeastSquaresFit over pfl-format data between d_start and d_end.
 * Writes fit_y1 to fit[0] and fit_y2 to fit[1].
 */
internal fun linearLeastSquaresFit(pfl: DoubleArray, d_start: Double, d_end: Double, fit: DoubleArray) {
    val np = pfl[0].toInt()

    var i_start = fdim(d_start / pfl[1], 0.0).toInt()
    var i_end = np - fdim(np.toDouble(), d_end / pfl[1]).toInt()

    if (i_end <= i_start) {
        i_start = fdim(i_start.toDouble(), 1.0).toInt()
        i_end = np - fdim(np.toDouble(), i_end + 1.0).toInt()
    }

    val x_length = (i_end - i_start).toDouble()

    var mid_shifted_index = -0.5 * x_length
    val mid_shifted_end = i_end + mid_shifted_index

    var sum_y = 0.5 * (pfl[i_start + 2] + pfl[i_end + 2])
    var scaled_sum_y = 0.5 * (pfl[i_start + 2] - pfl[i_end + 2]) * mid_shifted_index

    var i = 2
    while (i <= x_length) {
        i_start++
        mid_shifted_index++

        sum_y += pfl[i_start + 2]
        scaled_sum_y += pfl[i_start + 2] * mid_shifted_index
        i++
    }

    sum_y /= x_length
    scaled_sum_y = scaled_sum_y * 12.0 / ((x_length * x_length + 2.0) * x_length)

    fit[0] = sum_y - scaled_sum_y * mid_shifted_end
    fit[1] = sum_y + scaled_sum_y * (np - mid_shifted_end)
}
