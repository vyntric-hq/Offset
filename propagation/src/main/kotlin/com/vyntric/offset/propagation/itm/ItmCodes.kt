/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

// NTIA include/itm.h, include/Enums.h, include/Errors.h and include/Warnings.h.

// itm.h macros. MAX, MIN and DIM keep the C ternary semantics (a NaN in x yields y, or 0 for DIM),
// which kotlin.math.max/min do not.

@Suppress("NOTHING_TO_INLINE", "FunctionName")
internal inline fun MAX(x: Double, y: Double): Double = if (x > y) x else y

@Suppress("NOTHING_TO_INLINE", "FunctionName")
internal inline fun MIN(x: Double, y: Double): Double = if (x < y) x else y

@Suppress("NOTHING_TO_INLINE", "FunctionName")
internal inline fun MAX(x: Int, y: Int): Int = if (x > y) x else y

@Suppress("NOTHING_TO_INLINE", "FunctionName")
internal inline fun MIN(x: Int, y: Int): Int = if (x < y) x else y

@Suppress("NOTHING_TO_INLINE", "FunctionName")
internal inline fun DIM(x: Double, y: Double): Double = if (x > y) x - y else 0.0

/** C's fdim(x, y): x - y when x > y, +0 otherwise, NaN if either is NaN. */
internal fun fdim(x: Double, y: Double): Double = if (x.isNaN() || y.isNaN()) Double.NaN else if (x > y) x - y else 0.0

/** pow(x, 2) in NTIA's code. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun sq(x: Double): Double = x * x

internal const val PI = 3.1415926535897932384
internal const val A_0_METER = 6370e3 // a_0__meter
internal const val A_9000_METER = 9000e3 // a_9000__meter
internal const val THIRD = 1.0 / 3.0

internal const val MODE_P2P = 0 // MODE__P2P
internal const val MODE_AREA = 1 // MODE__AREA

// Enums.h: modes of variability (mdvar), polarizations, climates, propagation modes.
internal const val MDVAR_SINGLE_MESSAGE_MODE = 0
internal const val MDVAR_ACCIDENTAL_MODE = 1
internal const val MDVAR_MOBILE_MODE = 2
internal const val MDVAR_BROADCAST_MODE = 3

internal const val POLARIZATION_HORIZONTAL = 0
internal const val POLARIZATION_VERTICAL = 1

internal const val CLIMATE_EQUATORIAL = 1
internal const val CLIMATE_MARITIME_TEMPERATE_OVER_SEA = 7

internal const val MODE_NOT_SET = 0 // MODE__NOT_SET
internal const val MODE_LINE_OF_SIGHT = 1 // MODE__LINE_OF_SIGHT
internal const val MODE_DIFFRACTION = 2 // MODE__DIFFRACTION
internal const val MODE_TROPOSCATTER = 3 // MODE__TROPOSCATTER

/** NTIA Errors.h return codes. */
internal object ItmError {
    const val SUCCESS = 0
    const val SUCCESS_WITH_WARNINGS = 1

    const val TX_TERMINAL_HEIGHT = 1000
    const val RX_TERMINAL_HEIGHT = 1001
    const val INVALID_RADIO_CLIMATE = 1002
    const val INVALID_TIME = 1003
    const val INVALID_LOCATION = 1004
    const val INVALID_SITUATION = 1005
    const val INVALID_CONFIDENCE = 1006
    const val INVALID_RELIABILITY = 1007
    const val REFRACTIVITY = 1008
    const val FREQUENCY = 1009
    const val POLARIZATION = 1010
    const val EPSILON = 1011
    const val SIGMA = 1012
    const val GROUND_IMPEDANCE = 1013
    const val MDVAR = 1014
    const val EFFECTIVE_EARTH = 1016
    const val PATH_DISTANCE = 1017
    const val DELTA_H = 1018
    const val TX_SITING_CRITERIA = 1019
    const val RX_SITING_CRITERIA = 1020
    const val SURFACE_REFRACTIVITY_SMALL = 1021
    const val SURFACE_REFRACTIVITY_LARGE = 1022

    /** NTIA's comment for each code in Errors.h. */
    fun describe(code: Int): String = when (code) {
        TX_TERMINAL_HEIGHT -> "TX terminal height is out of range"
        RX_TERMINAL_HEIGHT -> "RX terminal height is out of range"
        INVALID_RADIO_CLIMATE -> "Invalid value for radio climate"
        INVALID_TIME -> "Time percentage is out of range"
        INVALID_LOCATION -> "Location percentage is out of range"
        INVALID_SITUATION -> "Situation percentage is out of range"
        INVALID_CONFIDENCE -> "Confidence percentage is out of range"
        INVALID_RELIABILITY -> "Reliability percentage is out of range"
        REFRACTIVITY -> "Refractivity is out of range"
        FREQUENCY -> "Frequency is out of range"
        POLARIZATION -> "Invalid value for polarization"
        EPSILON -> "Epsilon is out of range"
        SIGMA -> "Sigma is out of range"
        GROUND_IMPEDANCE -> "The imaginary portion of the complex impedance is larger than the real portion"
        MDVAR -> "Invalid value for mode of variability"
        EFFECTIVE_EARTH -> "Internally computed effective earth radius is invalid"
        PATH_DISTANCE -> "Path distance is out of range"
        DELTA_H -> "Delta H (terrain irregularity parameter) is out of range"
        TX_SITING_CRITERIA -> "Invalid value for TX siting criteria"
        RX_SITING_CRITERIA -> "Invalid value for RX siting criteria"
        SURFACE_REFRACTIVITY_SMALL -> "Internally computed surface refractivity value is too small"
        SURFACE_REFRACTIVITY_LARGE -> "Internally computed surface refractivity value is too large"
        else -> "ITM error $code"
    }
}

/** NTIA Warnings.h flags, OR-ed together in [ItmResult.warnings]. */
internal object ItmWarning {
    const val NO_WARNINGS = 0
    const val TX_TERMINAL_HEIGHT = 0x0001 // TX terminal height is near its limits
    const val RX_TERMINAL_HEIGHT = 0x0002 // RX terminal height is near its limits
    const val FREQUENCY = 0x0004 // Frequency is near its limits
    const val PATH_DISTANCE_TOO_BIG_1 = 0x0008 // Path distance is near its upper limit
    const val PATH_DISTANCE_TOO_BIG_2 = 0x0010 // Path distance is large: care must be taken with result
    const val PATH_DISTANCE_TOO_SMALL_1 = 0x0020 // Path distance is near its lower limit
    const val PATH_DISTANCE_TOO_SMALL_2 = 0x0040 // Path distance is small: care must be taken with result
    const val TX_HORIZON_ANGLE = 0x0080 // TX horizon angle is large: small angle approximations could break down
    const val RX_HORIZON_ANGLE = 0x0100 // RX horizon angle is large: small angle approximations could break down
    const val TX_HORIZON_DISTANCE_1 = 0x0200 // TX horizon distance is less than 1/10 of the smooth earth horizon distance
    const val RX_HORIZON_DISTANCE_1 = 0x0400 // RX horizon distance is less than 1/10 of the smooth earth horizon distance
    const val TX_HORIZON_DISTANCE_2 = 0x0800 // TX horizon distance is greater than 3 times the smooth earth horizon distance
    const val RX_HORIZON_DISTANCE_2 = 0x1000 // RX horizon distance is greater than 3 times the smooth earth horizon distance
    const val EXTREME_VARIABILITIES = 0x2000 // One of the provided variabilities is far in the tail of its distribution
    const val SURFACE_REFRACTIVITY = 0x4000 // Internally computed surface refractivity value is small
}
