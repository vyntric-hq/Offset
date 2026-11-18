/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

// NTIA Variability.cpp (with Curve) and InverseComplementaryCumulativeDistributionFunction.cpp.
// The constant tables are private and never written, so sharing them across threads is safe.

// Asymptotic values from TN101, Fig 10.13, approximate to TN101v2 Eqn III.69 & III.70, to describe
// the curves for each climate. NTIA's all_year[5][7], one array per row.
private val ALL_YEAR_0 = doubleArrayOf(-9.67, -0.62, 1.26, -9.21, -0.62, -0.39, 3.15)
private val ALL_YEAR_1 = doubleArrayOf(12.7, 9.19, 15.5, 9.05, 9.19, 2.86, 857.9)
private val ALL_YEAR_2 = doubleArrayOf(144.9e3, 228.9e3, 262.6e3, 84.1e3, 228.9e3, 141.7e3, 2222.0e3)
private val ALL_YEAR_3 = doubleArrayOf(190.3e3, 205.2e3, 185.2e3, 101.1e3, 205.2e3, 315.9e3, 164.8e3)
private val ALL_YEAR_4 = doubleArrayOf(133.8e3, 143.6e3, 99.8e3, 98.6e3, 143.6e3, 167.4e3, 116.3e3)

private val BSM1 = doubleArrayOf(2.13, 2.66, 6.11, 1.98, 2.68, 6.86, 8.51)
private val BSM2 = doubleArrayOf(159.5, 7.67, 6.65, 13.11, 7.16, 10.38, 169.8)
private val XSM1 = doubleArrayOf(762.2e3, 100.4e3, 138.2e3, 139.1e3, 93.7e3, 187.8e3, 609.8e3)
private val XSM2 = doubleArrayOf(123.6e3, 172.5e3, 242.2e3, 132.7e3, 186.8e3, 169.6e3, 119.9e3)
private val XSM3 = doubleArrayOf(94.5e3, 136.4e3, 178.6e3, 193.5e3, 133.5e3, 108.9e3, 106.6e3)

private val BSP1 = doubleArrayOf(2.11, 6.87, 10.08, 3.68, 4.75, 8.58, 8.43)
private val BSP2 = doubleArrayOf(102.3, 15.53, 9.60, 159.3, 8.12, 13.97, 8.19)
private val XSP1 = doubleArrayOf(636.9e3, 138.7e3, 165.3e3, 464.4e3, 93.2e3, 216.0e3, 136.2e3)
private val XSP2 = doubleArrayOf(134.8e3, 143.7e3, 225.7e3, 93.1e3, 135.9e3, 152.0e3, 188.5e3)
private val XSP3 = doubleArrayOf(95.6e3, 98.6e3, 129.7e3, 94.2e3, 113.4e3, 122.7e3, 122.9e3)

private val C_D = doubleArrayOf(1.224, 0.801, 1.380, 1.000, 1.224, 1.518, 1.518) // [Algorithm, Table 5.1], C_d
private val Z_D = doubleArrayOf(1.282, 2.161, 1.282, 20.0, 1.282, 1.282, 1.282) // [Algorithm, Table 5.1], z_d

private val BFM1 = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.92, 1.0, 1.0)
private val BFM2 = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.25, 0.0, 0.0)
private val BFM3 = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.77, 0.0, 0.0)

private val BFP1 = doubleArrayOf(1.0, 0.93, 1.0, 0.93, 0.93, 1.0, 1.0)
private val BFP2 = doubleArrayOf(0.0, 0.31, 0.0, 0.19, 0.31, 0.0, 0.0)
private val BFP3 = doubleArrayOf(0.0, 2.00, 0.0, 1.79, 2.00, 0.0, 0.0)

/** NTIA Curve (in Variability.cpp). */
internal fun curve(c1: Double, c2: Double, x1: Double, x2: Double, x3: Double, d_e__meter: Double): Double =
    (c1 + c2 / (1.0 + sq((d_e__meter - x2) / x3))) * (sq(d_e__meter / x1)) / (1.0 + (sq(d_e__meter / x1)))

/**
 * NTIA Variability: applies time, location and situation variability to A_ref__db, returning
 * the loss relative to free space in dB. ORs WARN__EXTREME_VARIABILITIES into [ws].
 */
internal fun variability(
    time: Double, location: Double, situation: Double, h_e__meter: DoubleArray, delta_h__meter: Double,
    f__mhz: Double, d__meter: Double, A_ref__db: Double, climate: Int, mdvar: Int, ws: ItmWorkspace,
): Double {
    var z_T = inverseComplementaryCumulativeDistributionFunction(time / 100)
    var z_L = inverseComplementaryCumulativeDistributionFunction(location / 100)
    val z_S = inverseComplementaryCumulativeDistributionFunction(situation / 100)

    val climate_idx = climate - 1 // 0-based indexes

    val wn = f__mhz / 47.7

    // compute the effective distance [Algorithm, Eqn 5.3]
    val d_ex__meter = sqrt(2 * A_9000_METER * h_e__meter[0]) + sqrt(2 * A_9000_METER * h_e__meter[1]) +
        (575.7e12 / wn).pow(THIRD)

    val d_e__meter = if (d__meter < d_ex__meter) {
        130e3 * d__meter / d_ex__meter
    } else {
        130e3 + d__meter - d_ex__meter
    }

    // situation variability calcs

    // if mdvar >= 20, then "Direct situation variability is to be eliminated as it should when
    // considering interference problems. Note that there may still be a small residual situation
    // variability" [Hufford, 1982]
    var mdvar_internal = mdvar // internal copy to modify
    val plus20 = mdvar_internal >= 20
    if (plus20) mdvar_internal -= 20

    val sigma_S = if (plus20) {
        0.0
    } else {
        val D__meter = 100e3 // Scale distance, D = 100 km
        5.0 + 3.0 * exp(-d_e__meter / D__meter) // [Algorithm, Eqn 5.10]
    }

    val plus10 = mdvar_internal >= 10
    if (plus10) mdvar_internal -= 10

    val V_med__db = curve(
        ALL_YEAR_0[climate_idx], ALL_YEAR_1[climate_idx], ALL_YEAR_2[climate_idx], ALL_YEAR_3[climate_idx],
        ALL_YEAR_4[climate_idx], d_e__meter,
    )

    when (mdvar_internal) {
        MDVAR_SINGLE_MESSAGE_MODE -> {
            z_T = z_S
            z_L = z_S
        }
        MDVAR_ACCIDENTAL_MODE -> z_L = z_S
        MDVAR_MOBILE_MODE -> z_L = z_T
        // else using Broadcast Mode (no additional operations)
    }

    if (abs(z_T) > 3.10 || abs(z_L) > 3.10 || abs(z_S) > 3.10) {
        ws.warnings = ws.warnings or ItmWarning.EXTREME_VARIABILITIES
    }

    // location variability calcs

    val sigma_L = if (plus10) {
        0.0
    } else {
        val delta_h_d__meter = terrainRoughness(d__meter, delta_h__meter)
        10.0 * wn * delta_h_d__meter / (wn * delta_h_d__meter + 13.0) // Context of [Algorithm, Eqn 5.9]
    }
    val Y_L = sigma_L * z_L

    // time variability calcs

    val q = ln(0.133 * wn)
    val g_minus = BFM1[climate_idx] + BFM2[climate_idx] / (sq(BFM3[climate_idx] * q) + 1.0)
    val g_plus = BFP1[climate_idx] + BFP2[climate_idx] / (sq(BFP3[climate_idx] * q) + 1.0)

    val sigma_T_minus = curve(
        BSM1[climate_idx], BSM2[climate_idx], XSM1[climate_idx], XSM2[climate_idx], XSM3[climate_idx], d_e__meter,
    ) * g_minus
    val sigma_T_plus = curve(
        BSP1[climate_idx], BSP2[climate_idx], XSP1[climate_idx], XSP2[climate_idx], XSP3[climate_idx], d_e__meter,
    ) * g_plus

    val sigma_TD = C_D[climate_idx] * sigma_T_plus
    val tgtd = (sigma_T_plus - sigma_TD) * Z_D[climate_idx]

    val sigma_T = if (z_T < 0.0) {
        sigma_T_minus
    } else if (z_T <= Z_D[climate_idx]) {
        sigma_T_plus
    } else {
        sigma_TD + tgtd / z_T
    }
    val Y_T = sigma_T * z_T

    // Part of [Algorithm, Eqn 5.11]
    val Y_S_temp = sq(sigma_S) + sq(Y_T) / (7.8 + sq(z_S)) + sq(Y_L) / (24.0 + sq(z_S))

    val Y_R: Double
    val Y_S: Double
    when (mdvar_internal) {
        MDVAR_SINGLE_MESSAGE_MODE -> {
            Y_R = 0.0
            Y_S = sqrt(sq(sigma_T) + sq(sigma_L) + Y_S_temp) * z_S
        }
        MDVAR_ACCIDENTAL_MODE -> {
            Y_R = Y_T
            Y_S = sqrt(sq(sigma_L) + Y_S_temp) * z_S
        }
        MDVAR_MOBILE_MODE -> {
            Y_R = sqrt(sq(sigma_T) + sq(sigma_L)) * z_T
            Y_S = sqrt(Y_S_temp) * z_S
        }
        else -> { // BROADCAST_MODE
            Y_R = Y_T + Y_L
            Y_S = sqrt(Y_S_temp) * z_S
        }
    }

    var result = A_ref__db - V_med__db - Y_R - Y_S

    // [Algorithm, Eqn 52]
    if (result < 0.0) result = result * (29.0 - result) / (29.0 - 10.0 * result)

    return result
}

/** NTIA InverseComplementaryCumulativeDistributionFunction: the standard normal Q^-1(q), 0 < q < 1. */
internal fun inverseComplementaryCumulativeDistributionFunction(q: Double): Double {
    val C_0 = 2.515516
    val C_1 = 0.802853
    val C_2 = 0.010328
    val D_1 = 1.432788
    val D_2 = 0.189269
    val D_3 = 0.001308

    var x = q
    if (q > 0.5) x = 1.0 - x

    val T_x = sqrt(-2.0 * ln(x))

    val zeta_x = ((C_2 * T_x + C_1) * T_x + C_0) / (((D_3 * T_x + D_2) * T_x + D_1) * T_x + 1.0)

    var Q_q = T_x - zeta_x

    if (q > 0.5) Q_q = -Q_q

    return Q_q
}
