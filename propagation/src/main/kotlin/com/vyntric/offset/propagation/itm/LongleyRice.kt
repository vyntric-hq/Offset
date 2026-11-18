/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// NTIA LongleyRice.cpp and the loss functions it calls: LineOfSightLoss, DiffractionLoss,
// KnifeEdgeDiffraction, SmoothEarthDiffraction (with HeightFunction), FresnelIntegral,
// TroposcatterLoss (with FFunction), H0Function (with H0Curve), TerrainRoughness,
// SigmaHFunction and FreeSpaceLoss. NTIA's complex<double> Z_g is passed as (Z_g_re, Z_g_im).

private val SQRT2 = sqrt(2.0)

/**
 * NTIA LongleyRice: the reference attenuation. Writes A_ref__db, propmode and warnings to [ws]
 * and returns an Errors.h code.
 */
internal fun longleyRice(
    theta_hzn: DoubleArray, f__mhz: Double, Z_g_re: Double, Z_g_im: Double, d_hzn__meter: DoubleArray,
    h_e__meter: DoubleArray, gamma_e: Double, N_s: Double, delta_h__meter: Double, h__meter: DoubleArray,
    d__meter: Double, mode: Int, ws: ItmWorkspace,
): Int {
    var warnings = ws.warnings

    // effective earth radius
    val a_e__meter = 1 / gamma_e

    // Terrestrial smooth earth horizon distance approximation
    val d_hzn_s__meter_0 = sqrt(2.0 * h_e__meter[0] * a_e__meter)
    val d_hzn_s__meter_1 = sqrt(2.0 * h_e__meter[1] * a_e__meter)

    // Maximum line-of-sight distance for smooth earth
    val d_sML__meter = d_hzn_s__meter_0 + d_hzn_s__meter_1

    // Maximum line-of-sight distance for actual path
    val d_ML__meter = d_hzn__meter[0] + d_hzn__meter[1]

    // Angular distance of line-of-sight region
    val theta_los = -MAX(theta_hzn[0] + theta_hzn[1], -d_ML__meter / a_e__meter)

    // Check validity of small angle approximation
    if (abs(theta_hzn[0]) > 200e-3) warnings = warnings or ItmWarning.TX_HORIZON_ANGLE
    if (abs(theta_hzn[1]) > 200e-3) warnings = warnings or ItmWarning.RX_HORIZON_ANGLE

    // Checks that the actual horizon distance can't be less than 1/10 of the smooth earth horizon distance
    if (d_hzn__meter[0] < 0.1 * d_hzn_s__meter_0) warnings = warnings or ItmWarning.TX_HORIZON_DISTANCE_1
    if (d_hzn__meter[1] < 0.1 * d_hzn_s__meter_1) warnings = warnings or ItmWarning.RX_HORIZON_DISTANCE_1

    // Checks that the actual horizon distance can't be greater than 3 times the smooth earth horizon distance
    if (d_hzn__meter[0] > 3.0 * d_hzn_s__meter_0) warnings = warnings or ItmWarning.TX_HORIZON_DISTANCE_2
    if (d_hzn__meter[1] > 3.0 * d_hzn_s__meter_1) warnings = warnings or ItmWarning.RX_HORIZON_DISTANCE_2

    ws.warnings = warnings

    // Check the surface refractivity
    if (N_s < 150) return ItmError.SURFACE_REFRACTIVITY_SMALL
    if (N_s > 400) return ItmError.SURFACE_REFRACTIVITY_LARGE
    if (N_s < 250) warnings = warnings or ItmWarning.SURFACE_REFRACTIVITY // 150 <= N_s < 250

    ws.warnings = warnings

    // Check effective earth size
    if (a_e__meter < 4000000 || a_e__meter > 13333333) return ItmError.EFFECTIVE_EARTH

    // Check ground impedance
    if (Z_g_re <= abs(Z_g_im)) return ItmError.GROUND_IMPEDANCE

    // Select two distances far in the diffraction region
    val d_3__meter = MAX(d_sML__meter, d_ML__meter + 5.0 * (sq(a_e__meter) / f__mhz).pow(1.0 / 3.0))
    val d_4__meter = d_3__meter + 10.0 * (sq(a_e__meter) / f__mhz).pow(1.0 / 3.0)

    // Compute the diffraction loss at the two distances
    val A_3__db = diffractionLoss(
        d_3__meter, d_hzn__meter, h_e__meter, Z_g_re, Z_g_im, a_e__meter, delta_h__meter, h__meter, mode,
        theta_los, d_sML__meter, f__mhz,
    )
    val A_4__db = diffractionLoss(
        d_4__meter, d_hzn__meter, h_e__meter, Z_g_re, Z_g_im, a_e__meter, delta_h__meter, h__meter, mode,
        theta_los, d_sML__meter, f__mhz,
    )

    // Compute the slope and intercept of the diffraction line
    val M_d = (A_4__db - A_3__db) / (d_4__meter - d_3__meter)
    val A_d0__db = A_3__db - M_d * d_3__meter

    val d_min__meter = abs(h_e__meter[0] - h_e__meter[1]) / 200e-3

    if (d__meter < d_min__meter) warnings = warnings or ItmWarning.PATH_DISTANCE_TOO_SMALL_1
    if (d__meter < 1e3) warnings = warnings or ItmWarning.PATH_DISTANCE_TOO_SMALL_2
    if (d__meter > 1000e3) warnings = warnings or ItmWarning.PATH_DISTANCE_TOO_BIG_1
    if (d__meter > 2000e3) warnings = warnings or ItmWarning.PATH_DISTANCE_TOO_BIG_2
    ws.warnings = warnings

    var A_ref__db: Double

    // if the path distance is less than the maximum smooth earth line of sight distance...
    if (d__meter < d_sML__meter) {
        // Compute the diffraction loss at the maximum smooth earth line of sight distance
        val A_sML__db = d_sML__meter * M_d + A_d0__db

        // [ERL 79-ITS 67, Eqn 3.16a], in meters instead of km and with MIN() part below
        var d_0__meter = 0.04 * f__mhz * h_e__meter[0] * h_e__meter[1]

        val d_1__meter: Double
        if (A_d0__db >= 0.0) {
            d_0__meter = MIN(d_0__meter, 0.5 * d_ML__meter) // other part of [ERL 79-ITS 67, Eqn 3.16a]
            d_1__meter = d_0__meter + 0.25 * (d_ML__meter - d_0__meter) // [ERL 79-ITS 67, Eqn 3.16d]
        } else {
            d_1__meter = MAX(-A_d0__db / M_d, 0.25 * d_ML__meter)
        }

        val A_1__db = lineOfSightLoss(d_1__meter, h_e__meter, Z_g_re, Z_g_im, delta_h__meter, M_d, A_d0__db, d_sML__meter, f__mhz)

        var flag = false

        var kHat_1__db_per_meter = 0.0
        var kHat_2__db_per_meter = 0.0

        if (d_0__meter < d_1__meter) {
            val A_0__db = lineOfSightLoss(d_0__meter, h_e__meter, Z_g_re, Z_g_im, delta_h__meter, M_d, A_d0__db, d_sML__meter, f__mhz)

            val q = ln(d_sML__meter / d_0__meter)

            // [ERL 79-ITS 67, Eqn 3.20]
            kHat_2__db_per_meter = MAX(
                0.0,
                ((d_sML__meter - d_0__meter) * (A_1__db - A_0__db) - (d_1__meter - d_0__meter) * (A_sML__db - A_0__db)) /
                    ((d_sML__meter - d_0__meter) * ln(d_1__meter / d_0__meter) - (d_1__meter - d_0__meter) * q),
            )

            flag = A_d0__db > 0.0 || kHat_2__db_per_meter > 0.0

            if (flag) {
                // [ERL 79-ITS 67, Eqn 3.21]
                kHat_1__db_per_meter = (A_sML__db - A_0__db - kHat_2__db_per_meter * q) / (d_sML__meter - d_0__meter)

                if (kHat_1__db_per_meter < 0.0) {
                    kHat_1__db_per_meter = 0.0
                    kHat_2__db_per_meter = DIM(A_sML__db, A_0__db) / q

                    if (kHat_2__db_per_meter == 0.0) kHat_1__db_per_meter = M_d
                }
            }
        }

        if (!flag) {
            kHat_1__db_per_meter = DIM(A_sML__db, A_1__db) / (d_sML__meter - d_1__meter)
            kHat_2__db_per_meter = 0.0

            if (kHat_1__db_per_meter == 0.0) kHat_1__db_per_meter = M_d
        }

        val A_o__db = A_sML__db - kHat_1__db_per_meter * d_sML__meter - kHat_2__db_per_meter * ln(d_sML__meter)

        // [ERL 79-ITS 67, Eqn 3.19]
        A_ref__db = A_o__db + kHat_1__db_per_meter * d__meter + kHat_2__db_per_meter * ln(d__meter)
        ws.propMode = MODE_LINE_OF_SIGHT
    } else { // this is a trans-horizon path
        // select two points far into the troposcatter region
        val d_5__meter = d_ML__meter + 200e3
        val d_6__meter = d_ML__meter + 400e3

        // Compute the troposcatter loss at the two distances
        val h0 = ws.h0
        h0[0] = -1.0
        val A_6__db = troposcatterLoss(d_6__meter, theta_hzn, d_hzn__meter, h_e__meter, a_e__meter, N_s, f__mhz, theta_los, h0)
        val A_5__db = troposcatterLoss(d_5__meter, theta_hzn, d_hzn__meter, h_e__meter, a_e__meter, N_s, f__mhz, theta_los, h0)

        val M_s: Double
        val A_s0__db: Double
        val d_x__meter: Double

        // if we got a reasonable prediction value back...
        if (A_5__db < 1000.0) {
            // Compute the slope of the troposcatter line
            M_s = (A_6__db - A_5__db) / 200e3

            // Find the diffraction-troposcatter transition distance
            d_x__meter = MAX(
                MAX(d_sML__meter, d_ML__meter + 1.088 * (sq(a_e__meter) / f__mhz).pow(1.0 / 3.0) * ln(f__mhz)),
                (A_5__db - A_d0__db - M_s * d_5__meter) / (M_d - M_s),
            )

            // Compute the intercept of the troposcatter line
            A_s0__db = (M_d - M_s) * d_x__meter + A_d0__db
        } else {
            // troposcatter gives no real results, so use diffraction line parameters for tropo line
            M_s = M_d
            A_s0__db = A_d0__db
            d_x__meter = 10e6
        }

        // Determine if its diffraction or troposcatter and compute the loss
        if (d__meter > d_x__meter) {
            A_ref__db = M_s * d__meter + A_s0__db
            ws.propMode = MODE_TROPOSCATTER
        } else {
            A_ref__db = M_d * d__meter + A_d0__db
            ws.propMode = MODE_DIFFRACTION
        }
    }

    // Don't allow a negative loss
    ws.aRef = MAX(A_ref__db, 0.0)

    return ItmError.SUCCESS
}

/** NTIA LineOfSightLoss: loss in the line-of-sight region, in dB. */
internal fun lineOfSightLoss(
    d__meter: Double, h_e__meter: DoubleArray, Z_g_re: Double, Z_g_im: Double, delta_h__meter: Double,
    M_d: Double, A_d0: Double, d_sML__meter: Double, f__mhz: Double,
): Double {
    val delta_h_d__meter = terrainRoughness(d__meter, delta_h__meter)

    val sigma_h_d__meter = sigmaHFunction(delta_h_d__meter)

    // wavenumber, k
    val wn = f__mhz / 47.7

    // [Algorithm, Eqn 4.46]
    val sin_psi = (h_e__meter[0] + h_e__meter[1]) / sqrt(sq(d__meter) + sq(h_e__meter[0] + h_e__meter[1]))

    // [Algorithm, Eqn 4.47]: R_e = (sin_psi - Z_g) / (sin_psi + Z_g) * exp(-MIN(10.0, wn * sigma_h_d__meter * sin_psi))
    val numRe = sin_psi - Z_g_re
    val numIm = -Z_g_im
    val denRe = sin_psi + Z_g_re
    val denIm = Z_g_im
    val den = denRe * denRe + denIm * denIm
    val scale = exp(-MIN(10.0, wn * sigma_h_d__meter * sin_psi))
    var R_e_re = (numRe * denRe + numIm * denIm) / den * scale
    var R_e_im = (numIm * denRe - numRe * denIm) / den * scale

    // q = Magnitude of R_e', [Algorithm, Eqn 4.48]
    val q = sq(R_e_re) + sq(R_e_im)
    if (q < 0.25 || q < sin_psi) {
        val k = sqrt(sin_psi / q)
        R_e_re *= k
        R_e_im *= k
    }

    // phase difference between rays, [Algorithm, Eqn 4.49]
    var delta_phi = wn * 2.0 * h_e__meter[0] * h_e__meter[1] / d__meter

    // [Algorithm, Eqn 4.50]
    if (delta_phi > PI / 2.0) delta_phi = PI - sq(PI / 2.0) / delta_phi

    // Two-ray attenuation: rr = complex(cos(delta_phi), -sin(delta_phi)) + R_e
    val rr_re = cos(delta_phi) + R_e_re
    val rr_im = -sin(delta_phi) + R_e_im
    val A_t__db = -10 * log10(sq(rr_re) + sq(rr_im))

    // Extended diffraction attenuation
    val A_d__db = M_d * d__meter + A_d0

    // weighting factor
    val w = 1 / (1 + f__mhz * delta_h__meter / MAX(10e3, d_sML__meter))

    return w * A_t__db + (1 - w) * A_d__db
}

/** NTIA DiffractionLoss: combined knife-edge, smooth-earth and clutter loss, in dB. */
internal fun diffractionLoss(
    d__meter: Double, d_hzn__meter: DoubleArray, h_e__meter: DoubleArray, Z_g_re: Double, Z_g_im: Double,
    a_e__meter: Double, delta_h__meter: Double, h__meter: DoubleArray, mode: Int, theta_los: Double,
    d_sML__meter: Double, f__mhz: Double,
): Double {
    val A_k__db = knifeEdgeDiffraction(d__meter, f__mhz, a_e__meter, theta_los, d_hzn__meter)

    val A_se__db = smoothEarthDiffraction(d__meter, f__mhz, a_e__meter, theta_los, d_hzn__meter, h_e__meter, Z_g_re, Z_g_im)

    // Terrain clutter

    // Terrain roughness term, using d_sML__meter, per [ERL 79-ITS 67, page 3-13]
    val delta_h_dsML__meter = terrainRoughness(d_sML__meter, delta_h__meter)

    val sigma_h_d__meter = sigmaHFunction(delta_h_dsML__meter)

    // Clutter factor [ERL 79-ITS 67, Eqn 3.38c]
    val A_fo__db = MIN(15.0, 5 * log10(1.0 + 1e-5 * h__meter[0] * h__meter[1] * f__mhz * sigma_h_d__meter))

    // Combined diffraction losses

    // compute the weighting factor in the following calculations
    val delta_h_d__meter = terrainRoughness(d__meter, delta_h__meter)

    var q = h__meter[0] * h__meter[1]
    val qk = h_e__meter[0] * h_e__meter[1] - q

    // For low antennas with known path parameters, C ~= 10 [ERL 79-ITS 67, page 3-8]
    if (mode == MODE_P2P) q += 10.0

    val term1 = sqrt(1.0 + qk / q) // square root term in [ERL 79-ITS 67, Eqn 3.23]

    val d_ML__meter = d_hzn__meter[0] + d_hzn__meter[1] // Maximum line-of-sight distance for actual path
    q = (term1 + (-theta_los * a_e__meter + d_ML__meter) / d__meter) * MIN(delta_h_d__meter * f__mhz / 47.7, 6283.2)

    // weighting factor [ERL 17-ITS 67, Eqn 3.23]
    val w = 25.1 / (25.1 + sqrt(q))

    return w * A_se__db + (1.0 - w) * A_k__db + A_fo__db
}

/** NTIA KnifeEdgeDiffraction: double knife-edge loss, in dB. */
internal fun knifeEdgeDiffraction(
    d__meter: Double, f__mhz: Double, a_e__meter: Double, theta_los: Double, d_hzn__meter: DoubleArray,
): Double {
    val d_ML__meter = d_hzn__meter[0] + d_hzn__meter[1] // Maximum line-of-sight distance for actual path
    val theta_nlos = d__meter / a_e__meter - theta_los // Angular distance of diffraction region [Algorithm, Eqn 4.12]

    val d_nlos__meter = d__meter - d_ML__meter // Diffraction distance, in meters

    // 1 / (4 pi) = 0.0795775 [TN101, Eqn I.7]
    val v_1 = 0.0795775 * (f__mhz / 47.7) * sq(theta_nlos) * d_hzn__meter[0] * d_nlos__meter / (d_nlos__meter + d_hzn__meter[0])
    val v_2 = 0.0795775 * (f__mhz / 47.7) * sq(theta_nlos) * d_hzn__meter[1] * d_nlos__meter / (d_nlos__meter + d_hzn__meter[1])

    return fresnelIntegral(v_1) + fresnelIntegral(v_2) // [TN101, Eqn I.1]
}

/** NTIA SmoothEarthDiffraction (Vogler 1964), in dB. NTIA's 3-element arrays are unrolled. */
internal fun smoothEarthDiffraction(
    d__meter: Double, f__mhz: Double, a_e__meter: Double, theta_los: Double, d_hzn__meter: DoubleArray,
    h_e__meter: DoubleArray, Z_g_re: Double, Z_g_im: Double,
): Double {
    val theta_nlos = d__meter / a_e__meter - theta_los // [Algorithm, Eqn 4.12]
    val d_ML__meter = d_hzn__meter[0] + d_hzn__meter[1] // Maximum line-of-sight distance for actual path

    // compute 3 radii
    val a__meter_0 = (d__meter - d_ML__meter) / (d__meter / a_e__meter - theta_los) // a_e__meter when theta_los = d_ML__meter / a_e__meter
    val a__meter_1 = 0.5 * sq(d_hzn__meter[0]) / h_e__meter[0] // effective earth radius for terminal j, [Vogler 1964, Eqn 3] re-arranged
    val a__meter_2 = 0.5 * sq(d_hzn__meter[1]) / h_e__meter[1] // effective earth radius for terminal j, [Vogler 1964, Eqn 3] re-arranged

    val d__km_0 = (a__meter_0 * theta_nlos) / 1000.0 // angular distance of the "diffraction path"
    val d__km_1 = d_hzn__meter[0] / 1000.0
    val d__km_2 = d_hzn__meter[1] / 1000.0

    val abs_Z_g = sqrt(Z_g_re * Z_g_re + Z_g_im * Z_g_im)
    val f_third = f__mhz.pow(THIRD)
    val f_minus_third = f__mhz.pow(-THIRD)

    // C_0 = (4 / 3k) ^ (1 / 3) [Vogler 1964, Eqn 2]: ratio of the 4/3 earth to effective earth
    val C_0_0 = ((4.0 / 3.0) * A_0_METER / a__meter_0).pow(THIRD)
    val C_0_1 = ((4.0 / 3.0) * A_0_METER / a__meter_1).pow(THIRD)
    val C_0_2 = ((4.0 / 3.0) * A_0_METER / a__meter_2).pow(THIRD)

    // [Vogler 1964, Eqn 6a / 7a]
    val K_0 = 0.017778 * C_0_0 * f_minus_third / abs_Z_g
    val K_1 = 0.017778 * C_0_1 * f_minus_third / abs_Z_g
    val K_2 = 0.017778 * C_0_2 * f_minus_third / abs_Z_g

    // compute B_0 for each radius [Vogler 1964, Fig 4]
    val B_0_0 = 1.607 - K_0
    val B_0_1 = 1.607 - K_1
    val B_0_2 = 1.607 - K_2

    // compute x__km for each radius [Vogler 1964, Eqn 2]
    val x__km_1 = B_0_1 * sq(C_0_1) * f_third * d__km_1
    val x__km_2 = B_0_2 * sq(C_0_2) * f_third * d__km_2
    val x__km_0 = B_0_0 * sq(C_0_0) * f_third * d__km_0 + x__km_1 + x__km_2

    // compute height gain functions
    val F_x__db_0 = heightFunction(x__km_1, K_1)
    val F_x__db_1 = heightFunction(x__km_2, K_2)

    // compute distance function [TN101, Eqn 8.4] & [Vogler 1964, Eqn 13]
    val G_x__db = 0.05751 * x__km_0 - 10.0 * log10(x__km_0)

    return G_x__db - F_x__db_0 - F_x__db_1 - 20 // [Algorithm, Eqn 4.20] & [Vogler 1964]
}

/** NTIA HeightFunction (in SmoothEarthDiffraction.cpp): height gain, in dB. */
internal fun heightFunction(x__km: Double, K: Double): Double {
    val w: Double
    var result: Double

    if (x__km < 200.0) {
        w = -ln(K)

        if (K < 1e-5 || x__km * w.pow(3) > 5495.0) {
            result = -117.0

            if (x__km > 1.0) result += 17.372 * ln(x__km)
        } else {
            result = 2.5e-5 * sq(x__km) / K - 8.686 * w - 15.0
        }
    } else {
        result = 0.05751 * x__km - 4.343 * ln(x__km)

        if (x__km < 2000) {
            w = 0.0134 * x__km * exp(-0.005 * x__km)
            result = (1.0 - w) * result + w * (17.372 * ln(x__km) - 117.0)
        }
    }

    return result
}

/** NTIA FresnelIntegral. Note: v2 is v^2, so 5.76 is actually comparing v to 2.4. */
internal fun fresnelIntegral(v2: Double): Double =
    if (v2 < 5.76) {
        6.02 + 9.11 * sqrt(v2) - 1.27 * v2 // [TN101v2, Eqn III.24b] and [ERL 79-ITS 67, Eqn 3.27a & 3.27b]
    } else {
        12.953 + 10 * log10(v2) // [TN101v2, Eqn III.24c] and [ERL 79-ITS 67, Eqn 3.27a & 3.27b]
    }

/** NTIA FFunction (in TroposcatterLoss.cpp), constants from [Algorithm, 6.9]. */
internal fun fFunction(td: Double): Double {
    // select the set of values to use
    return if (td <= 10e3) { // <= 10 km
        133.4 + 0.332e-3 * td + -10.0 * log10(td)
    } else if (td <= 70e3) { // 10 km to 70 km
        104.6 + 0.212e-3 * td + -2.5 * log10(td)
    } else { // > 70 km
        71.8 + 0.157e-3 * td + 5.0 * log10(td)
    } // F_0 = a[i] + b[i] * td + c[i] * log10(td), [Algorithm, 6.9]
}

/**
 * NTIA TroposcatterLoss, in dB. h0[0] is NTIA's in/out *h0: the H_0 value from a previous call,
 * reused when it already exceeds 15 dB. Returns 1001 when A_scat is undefined.
 */
internal fun troposcatterLoss(
    d__meter: Double, theta_hzn: DoubleArray, d_hzn__meter: DoubleArray, h_e__meter: DoubleArray,
    a_e__meter: Double, N_s: Double, f__mhz: Double, theta_los: Double, h0: DoubleArray,
): Double {
    var H_0: Double

    // wavenumber, k
    val wn = f__mhz / 47.7

    if (h0[0] > 15.0) { // short-circuit calculations if already greater than 15 dB
        H_0 = h0[0]
    } else {
        var ad = d_hzn__meter[0] - d_hzn__meter[1]
        var rr = h_e__meter[1] / h_e__meter[0]

        if (ad < 0.0) { // ensure correct frame of reference
            ad = -ad
            rr = 1.0 / rr
        }

        val theta = theta_hzn[0] + theta_hzn[1] + d__meter / a_e__meter // angular distance, in radians

        // [TN101, Eqn 9.4a]
        val r_1 = 2.0 * wn * theta * h_e__meter[0]
        val r_2 = 2.0 * wn * theta * h_e__meter[1]

        // "If both r_1 and r_2 are less than 0.2 the function A_scat is not defined (or is infinite)" [Algorithm, page 11]
        if (r_1 < 0.2 && r_2 < 0.2) return 1001.0

        var s = (d__meter - ad) / (d__meter + ad) // asymmetry parameter

        // "In all of this, we truncate the values of s and q at 0.1 and 10" [Algorithm, page 16]
        val q = MIN(MAX(0.1, rr / s), 10.0) // TN101, Eqn 9.5
        s = MAX(0.1, s) // TN101, Eqn 9.5

        val h_0__meter = (d__meter - ad) * (d__meter + ad) * theta * 0.25 / d__meter // height of cross-over, [Algorithm, 4.66] [TN101v1, 9.3b]

        val Z_0__meter = 1.7556e3 // Scale height, [Algorithm, 4.67]
        val Z_1__meter = 8.0e3 // [Algorithm, 4.67]
        // Scattering efficiency factor, eta_s [TN101 Eqn 9.3a]
        val eta_s = (h_0__meter / Z_0__meter) *
            (1.0 + (0.031 - N_s * 2.32e-3 + sq(N_s) * 5.67e-6) * exp(-MIN(1.7, h_0__meter / Z_1__meter).pow(6)))

        val H_00 = (h0Function(r_1, eta_s) + h0Function(r_2, eta_s)) / 2 // First term in TN101v1, Eqn 9.5
        val Delta_H_0 = MIN(H_00, 6.0 * (0.6 - log10(MAX(eta_s, 1.0))) * log10(s) * log10(q))

        H_0 = H_00 + Delta_H_0 // TN101, Eqn 9.5
        H_0 = MAX(H_0, 0.0) // "If Delta_H_0 would make H_0 negative, use H_0 = 0" [TN101v1, p9.4]

        if (eta_s < 1.0) { // if <=1, interpolate with the special case of eta_s = 0
            H_0 = eta_s * H_0 + (1.0 - eta_s) * 10 *
                log10(sq((1.0 + SQRT2 / r_1) * (1.0 + SQRT2 / r_2)) * (r_1 + r_2) / (r_1 + r_2 + 2 * SQRT2))
        }

        // "If, at d_5, calculations show that H_0 will exceed 15 dB, they are replaced by the value it has at d_6"
        // [Algorithm, page 12]
        if (H_0 > 15.0 && h0[0] >= 0.0) H_0 = h0[0]
    }

    h0[0] = H_0
    val th = d__meter / a_e__meter - theta_los

    val D_0__meter = 40e3 // [Algorithm, 6.8]
    val H__meter = 47.7 // [Algorithm, 4.63]
    // [Algorithm, 4.63]
    return fFunction(th * d__meter) + 10 * log10(wn * H__meter * th.pow(4)) -
        0.1 * (N_s - 301.0) * exp(-th * d__meter / D_0__meter) + H_0
}

/** NTIA H0Curve (in H0Function.cpp), values from [Algorithm, 6.13]. */
internal fun h0Curve(j: Int, r: Double): Double {
    val a = H0_A[j]
    val b = H0_B[j]
    // related to TN101v2, Eqn III.49, but from [Algorithm, 6.13]
    return 10 * log10(1 + a * (1 / r).pow(4) + b * sq(1.0 / r))
}

private val H0_A = doubleArrayOf(25.0, 80.0, 177.0, 395.0, 705.0)
private val H0_B = doubleArrayOf(24.0, 45.0, 68.0, 80.0, 105.0)

/** NTIA H0Function: interpolates between the H0 curves for 1 <= eta_s <= 5. */
internal fun h0Function(r: Double, eta_s_in: Double): Double {
    val eta_s = MIN(MAX(eta_s_in, 1.0), 5.0) // range 1 <= eta_s <= 5

    val i = eta_s.toInt() // integer part of eta_s
    val q = eta_s - i // decimal part of eta_s

    var result = h0Curve(i - 1, r)

    if (q != 0.0) result = (1.0 - q) * result + q * h0Curve(i, r) // interpolate with next curve, if needed

    return result
}

/** NTIA TerrainRoughness: [ERL 79-ITS 67, Eqn 3], with distance in meters instead of kilometers. */
internal fun terrainRoughness(d__meter: Double, delta_h__meter: Double): Double =
    delta_h__meter * (1.0 - 0.8 * exp(-d__meter / 50e3))

/**
 * NTIA SigmaHFunction: "RMS deviation of terrain and terrain clutter within the limits of the first
 * Fresnel zone in the dominant reflecting plane" [ERL 79-ITS 67, Eqn 3.6a].
 */
internal fun sigmaHFunction(delta_h__meter: Double): Double =
    0.78 * delta_h__meter * exp(-0.5 * delta_h__meter.pow(0.25))

/** NTIA FreeSpaceLoss, in dB. */
internal fun freeSpaceLoss(d__meter: Double, f__mhz: Double): Double =
    32.45 + 20.0 * log10(f__mhz) + 20.0 * log10(d__meter / 1000.0)
