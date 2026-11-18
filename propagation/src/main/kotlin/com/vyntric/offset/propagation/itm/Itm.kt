/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

enum class Climate(val code: Int) {
    EQUATORIAL(1),
    CONTINENTAL_SUBTROPICAL(2),
    MARITIME_SUBTROPICAL(3),
    DESERT(4),
    CONTINENTAL_TEMPERATE(5),
    MARITIME_TEMPERATE_LAND(6),
    MARITIME_TEMPERATE_SEA(7),
}

enum class Polarization(val code: Int) { HORIZONTAL(0), VERTICAL(1) }

enum class PropagationMode { LINE_OF_SIGHT, DIFFRACTION, TROPOSCATTER }

/** NTIA's mdvar: 0 single message, 1 accidental, 2 mobile, 3 broadcast (plus 10/20 flags). */
enum class VariabilityMode(val code: Int) { SINGLE_MESSAGE(0), ACCIDENTAL(1), MOBILE(2), BROADCAST(3) }

/** [warnings] holds NTIA's Warnings.h flags (0 when none). */
data class ItmResult(val basicTransmissionLossDb: Double, val mode: PropagationMode, val warnings: Int)

/** One of NTIA's Errors.h codes (1000 and up), raised when the inputs are out of the model's range. */
class ItmException(val code: Int, message: String) : Exception(message)

object Itm {
    /**
     * Scratch arrays for one call, one set per thread. Nothing in here outlives a call, so
     * concurrent calls on different threads never see each other's state.
     */
    private val workspaces = ThreadLocal.withInitial { ItmWorkspace() }

    /**
     * NTIA ITM_P2P_TLS. [profile] is NTIA's pfl format: [0] = number of intervals n,
     * [1] = spacing in metres, [2..n+2] = n+1 terrain elevations in metres from Tx to Rx.
     * Percentages are 0 < p < 100.
     *
     * The profile length comes only from `profile[0]`: the array may be longer than n+3 (a
     * reused buffer) and anything past index n+2 is ignored. The array is never modified.
     *
     * @throws ItmException on NTIA's error codes (invalid inputs).
     * @throws IllegalArgumentException if [profile] is not a usable pfl (n < 1, spacing not
     *   positive, or fewer than n+3 entries). NTIA does not check this.
     */
    fun pointToPoint(
        txHeightM: Double, rxHeightM: Double, profile: DoubleArray,
        climate: Climate, surfaceRefractivity: Double, frequencyMhz: Double, polarization: Polarization,
        groundPermittivity: Double, groundConductivity: Double, variability: VariabilityMode,
        timePercent: Double, locationPercent: Double, situationPercent: Double,
    ): ItmResult = pointToPointRaw(
        txHeightM, rxHeightM, profile, climate.code, surfaceRefractivity, frequencyMhz, polarization.code,
        groundPermittivity, groundConductivity, variability.code, timePercent, locationPercent, situationPercent,
    )

    /**
     * [pointToPoint] with NTIA's raw integer codes, so every NTIA input (including mdvar's +10
     * and +20 flags and invalid codes) can be exercised.
     */
    internal fun pointToPointRaw(
        txHeightM: Double, rxHeightM: Double, profile: DoubleArray,
        climate: Int, n0: Double, frequencyMhz: Double, pol: Int,
        epsilon: Double, sigma: Double, mdvar: Int,
        time: Double, location: Double, situation: Double,
    ): ItmResult {
        requireUsableProfile(profile)
        return itmP2pTls(
            txHeightM, rxHeightM, profile, climate, n0, frequencyMhz, pol, epsilon, sigma, mdvar,
            time, location, situation, workspaces.get(),
        )
    }

    private fun requireUsableProfile(pfl: DoubleArray) {
        require(pfl.size >= 2) { "profile must hold at least n and the spacing" }
        val n = pfl[0].toInt()
        require(n >= 1) { "profile needs at least one interval, got n=${pfl[0]}" }
        require(pfl[1] > 0.0) { "profile spacing must be positive, got ${pfl[1]}" }
        require(pfl.size >= n + 3) { "profile says n=$n so needs ${n + 3} entries, has ${pfl.size}" }
    }
}

/**
 * Per-call scratch and outputs of the NTIA helper functions that return through pointers.
 * Owned by one thread at a time (see [Itm]); reset by [itmP2pTls] before use.
 */
internal class ItmWorkspace {
    val h = DoubleArray(2) // h__meter[2]
    val thetaHzn = DoubleArray(2) // theta_hzn[2]
    val dHzn = DoubleArray(2) // d_hzn__meter[2]
    val hE = DoubleArray(2) // h_e__meter[2]
    val fit = DoubleArray(2) // LinearLeastSquaresFit's *fit_y1, *fit_y2
    val deltaHS = DoubleArray(DELTA_H_SAMPLES_MAX + 2) // ComputeDeltaH's s[247]
    val deltaHDiffs = DoubleArray(DELTA_H_SAMPLES_MAX) // ComputeDeltaH's diffs
    val h0 = DoubleArray(1) // TroposcatterLoss's in/out *h0

    // InitializePointToPoint outputs
    var zgRe = 0.0 // Z_g.real()
    var zgIm = 0.0 // Z_g.imag()
    var gammaE = 0.0 // gamma_e
    var nS = 0.0 // N_s

    // QuickPfl outputs
    var deltaH = 0.0 // delta_h__meter
    var d = 0.0 // d__meter

    // LongleyRice outputs
    var aRef = 0.0 // A_ref__db
    var propMode = MODE_NOT_SET // propmode
    var warnings = ItmWarning.NO_WARNINGS // *warnings
}

/**
 * NTIA itm_p2p.cpp ITM_P2P_TLS_Ex. Returns the result or throws [ItmException] for any NTIA
 * error code; SUCCESS_WITH_WARNINGS is a success with [ItmResult.warnings] set.
 */
internal fun itmP2pTls(
    h_tx__meter: Double, h_rx__meter: Double, pfl: DoubleArray, climate: Int, N_0: Double, f__mhz: Double,
    pol: Int, epsilon: Double, sigma: Double, mdvar: Int, time: Double, location: Double, situation: Double,
    ws: ItmWorkspace,
): ItmResult {
    ws.warnings = ItmWarning.NO_WARNINGS // Initialize to no warnings
    ws.aRef = 0.0
    ws.propMode = MODE_NOT_SET

    // initial input validation check: some validation occurs later in calculations
    var rtn = validateInputs(
        h_tx__meter, h_rx__meter, climate, time, location, situation, N_0, f__mhz, pol, epsilon, sigma, mdvar, ws,
    )
    if (rtn != ItmError.SUCCESS) throw ItmException(rtn, ItmError.describe(rtn))

    val np = pfl[0].toInt() // number of points in the pfl

    // compute the average path height, ignoring first and last 10%
    val p10 = (0.1 * np).toInt() // 10% of np
    var h_sys__meter = 0.0 // Height of the system above mean sea level
    for (i in p10..np - p10) h_sys__meter += pfl[i + 2]
    h_sys__meter /= (np - 2 * p10 + 1)

    initializePointToPoint(f__mhz, h_sys__meter, N_0, pol, epsilon, sigma, ws)

    ws.h[0] = h_tx__meter
    ws.h[1] = h_rx__meter
    quickPfl(pfl, ws.gammaE, ws.h, ws.thetaHzn, ws.dHzn, ws.hE, ws)

    rtn = longleyRice(
        ws.thetaHzn, f__mhz, ws.zgRe, ws.zgIm, ws.dHzn, ws.hE, ws.gammaE, ws.nS, ws.deltaH, ws.h, ws.d, MODE_P2P, ws,
    )
    if (rtn != ItmError.SUCCESS) throw ItmException(rtn, ItmError.describe(rtn))

    val A_fs__db = freeSpaceLoss(ws.d, f__mhz)

    val A__db = variability(
        time, location, situation, ws.hE, ws.deltaH, f__mhz, ws.d, ws.aRef, climate, mdvar, ws,
    ) + A_fs__db

    val mode = when (ws.propMode) {
        MODE_LINE_OF_SIGHT -> PropagationMode.LINE_OF_SIGHT
        MODE_DIFFRACTION -> PropagationMode.DIFFRACTION
        MODE_TROPOSCATTER -> PropagationMode.TROPOSCATTER
        else -> error("LongleyRice returned SUCCESS without a propagation mode")
    }
    return ItmResult(A__db, mode, ws.warnings)
}

/**
 * NTIA InitializePointToPoint.cpp. Writes Z_g, gamma_e and N_s to [ws].
 * `h_sys__meter`: average height of the path above mean sea level.
 */
internal fun initializePointToPoint(
    f__mhz: Double, h_sys__meter: Double, N_0: Double, pol: Int, epsilon: Double, sigma: Double, ws: ItmWorkspace,
) {
    // gamma_a is the curvature of the actual earth, ~1 / 6370 km
    val gamma_a = 157e-9

    // scale the refractivity based on the elevation above mean sea level
    val N_s = if (h_sys__meter == 0.0) N_0 else N_0 * exp(-h_sys__meter / 9460.0) // [TN101, Eq 4.3]
    ws.nS = N_s

    // gamma_e is the curvature of the effective earth
    ws.gammaE = gamma_a * (1.0 - 0.04665 * exp(N_s / 179.3)) // [TN101, Eq 4.4], reworked

    // complex relative permittivity: ep_r = complex(epsilon, 18000 * sigma / f__mhz)
    val epRe = epsilon
    val epIm = 18000 * sigma / f__mhz

    // Z_g = sqrt(ep_r - 1.0): ground impedance (horizontal polarization), principal square root
    val x = epRe - 1.0
    val y = epIm
    val mag = sqrt(x * x + y * y)
    var zRe: Double
    var zIm: Double
    if (mag == 0.0) {
        zRe = 0.0
        zIm = 0.0
    } else if (x >= 0.0) {
        zRe = sqrt(0.5 * (mag + x))
        zIm = y / (2.0 * zRe)
    } else {
        val t = sqrt(0.5 * (mag - x))
        zRe = abs(y) / (2.0 * t)
        zIm = if (y < 0.0) -t else t
    }

    if (pol == POLARIZATION_VERTICAL) { // adjust for vertical polarization: Z_g = Z_g / ep_r
        val den = epRe * epRe + epIm * epIm
        val re = (zRe * epRe + zIm * epIm) / den
        val im = (zIm * epRe - zRe * epIm) / den
        zRe = re
        zIm = im
    }
    ws.zgRe = zRe
    ws.zgIm = zIm
}

/** NTIA ValidateInputs.cpp. Returns an Errors.h code and ORs warnings into [ws]. */
internal fun validateInputs(
    h_tx__meter: Double, h_rx__meter: Double, climate: Int, time: Double, location: Double, situation: Double,
    N_0: Double, f__mhz: Double, pol: Int, epsilon: Double, sigma: Double, mdvar: Int, ws: ItmWorkspace,
): Int {
    if (h_tx__meter < 1.0 || h_tx__meter > 1000.0) ws.warnings = ws.warnings or ItmWarning.TX_TERMINAL_HEIGHT
    if (h_tx__meter < 0.5 || h_tx__meter > 3000.0) return ItmError.TX_TERMINAL_HEIGHT

    if (h_rx__meter < 1.0 || h_rx__meter > 1000.0) ws.warnings = ws.warnings or ItmWarning.RX_TERMINAL_HEIGHT
    if (h_rx__meter < 0.5 || h_rx__meter > 3000.0) return ItmError.RX_TERMINAL_HEIGHT

    if (climate < CLIMATE_EQUATORIAL || climate > CLIMATE_MARITIME_TEMPERATE_OVER_SEA) return ItmError.INVALID_RADIO_CLIMATE

    if (N_0 < 250 || N_0 > 400) return ItmError.REFRACTIVITY

    if (f__mhz < 40.0 || f__mhz > 10000.0) ws.warnings = ws.warnings or ItmWarning.FREQUENCY
    if (f__mhz < 20 || f__mhz > 20000) return ItmError.FREQUENCY

    if (pol != POLARIZATION_HORIZONTAL && pol != POLARIZATION_VERTICAL) return ItmError.POLARIZATION

    if (epsilon < 1) return ItmError.EPSILON

    if (sigma <= 0) return ItmError.SIGMA

    if ((mdvar < 0) ||
        (mdvar > 3 && mdvar < 10) ||
        (mdvar > 13 && mdvar < 20) ||
        (mdvar > 23 && mdvar < 30) ||
        (mdvar > 33)
    ) return ItmError.MDVAR

    if (situation <= 0 || situation >= 100) return ItmError.INVALID_SITUATION

    if (time <= 0 || time >= 100) return ItmError.INVALID_TIME

    if (location <= 0 || location >= 100) return ItmError.INVALID_LOCATION

    return ItmError.SUCCESS
}
