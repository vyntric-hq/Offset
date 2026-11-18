/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import com.vyntric.offset.propagation.itm.Climate
import com.vyntric.offset.propagation.itm.Itm
import com.vyntric.offset.propagation.itm.ItmException
import com.vyntric.offset.propagation.itm.Polarization
import com.vyntric.offset.propagation.itm.PropagationMode
import com.vyntric.offset.propagation.itm.VariabilityMode
import kotlin.math.log10

/** Basic transmission loss over a terrain path, with how the signal mainly gets there. */
data class PathLoss(val lossDb: Double, val mode: PropagationMode) {
    /** The model declined the path (too long or out of its range): treat it as no link at all. */
    val modelled: Boolean get() = lossDb < NO_PATH_DB

    companion object {
        /** Loss reported for a path the model rejects: far beyond any radio's margin. */
        const val NO_PATH_DB = 300.0
    }
}

/**
 * Loss over a path given in NTIA's profile format. [profile] may be longer than it needs to be:
 * only `profile[0]` intervals are used, so callers can reuse one buffer.
 */
fun interface PathLossModel {
    fun loss(profile: DoubleArray, txHeightM: Double, rxHeightM: Double, frequencyMhz: Double): PathLoss
}

/**
 * Longley-Rice for VHF/UHF land mobile: median time, location and situation, vertical
 * polarization, average ground and a continental temperate climate (most of the United States).
 */
object ItmPathLoss : PathLossModel {
    const val SURFACE_REFRACTIVITY = 301.0
    const val GROUND_PERMITTIVITY = 15.0
    const val GROUND_CONDUCTIVITY = 0.005

    override fun loss(profile: DoubleArray, txHeightM: Double, rxHeightM: Double, frequencyMhz: Double): PathLoss = try {
        val r = Itm.pointToPoint(
            txHeightM = txHeightM.coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M),
            rxHeightM = rxHeightM.coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M),
            profile = profile,
            climate = Climate.CONTINENTAL_TEMPERATE,
            surfaceRefractivity = SURFACE_REFRACTIVITY,
            frequencyMhz = frequencyMhz,
            polarization = Polarization.VERTICAL,
            groundPermittivity = GROUND_PERMITTIVITY,
            groundConductivity = GROUND_CONDUCTIVITY,
            variability = VariabilityMode.MOBILE,
            timePercent = 50.0,
            locationPercent = 50.0,
            situationPercent = 50.0,
        )
        PathLoss(r.basicTransmissionLossDb, r.mode)
    } catch (e: ItmException) {
        declined(profile, frequencyMhz)
    } catch (e: IllegalArgumentException) {
        // A zero-length profile (a place on top of the station).
        declined(profile, frequencyMhz)
    }

    /**
     * ITM declined the path. Under a kilometre terrain hardly matters and free space applies;
     * anything longer it rejected is out of its range, and is reported as no link rather than
     * the free space figure, which would look far too good.
     */
    private fun declined(profile: DoubleArray, frequencyMhz: Double): PathLoss {
        val d = profile[0] * profile[1]
        return if (d <= SHORT_PATH_M) PathLoss(FreeSpace.lossDb(d, frequencyMhz), PropagationMode.LINE_OF_SIGHT)
        else PathLoss(PathLoss.NO_PATH_DB, PropagationMode.TROPOSCATTER)
    }

    private const val SHORT_PATH_M = 1_000.0

    /** ITM's valid antenna heights (0.5 to 3000 m). */
    private const val MIN_HEIGHT_M = 0.5
    private const val MAX_HEIGHT_M = 3000.0
}

object FreeSpace {
    /** Free space path loss in dB over [distanceM] at [frequencyMhz]. */
    fun lossDb(distanceM: Double, frequencyMhz: Double): Double =
        32.45 + 20 * log10(frequencyMhz) + 20 * log10((distanceM / 1000.0).coerceAtLeast(0.001))
}
