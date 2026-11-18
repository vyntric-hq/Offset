/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.propagation

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** How an antenna's gain varies with direction in the horizontal plane. */
sealed interface AntennaPattern {
    /** Gain relative to the peak, in dB (0 or negative), toward [bearingDeg]. */
    fun relativeDb(bearingDeg: Double): Double

    /** The same gain all round: verticals, whips, collinears, J-poles. */
    data object Omni : AntennaPattern {
        override fun relativeDb(bearingDeg: Double) = 0.0
    }

    /**
     * A beam (Yagi) pointed at [aimDeg]. The main lobe falls off as 12 (angle / beamwidth)^2 dB,
     * the usual parabolic approximation, and never below the front to back ratio.
     */
    data class Beam(val aimDeg: Double, val beamwidthDeg: Double, val frontToBackDb: Double = 15.0) : AntennaPattern {
        override fun relativeDb(bearingDeg: Double): Double {
            val off = abs(((bearingDeg - aimDeg) % 360.0 + 540.0) % 360.0 - 180.0)
            return -minOf(12.0 * (off / beamwidthDeg).pow(2), frontToBackDb)
        }

        companion object {
            /** Half power beamwidth for a beam of [gainDbi] with a roughly round main lobe. */
            fun beamwidthFor(gainDbi: Double): Double = sqrt(41_253.0 / 10.0.pow(gainDbi / 10.0)).coerceIn(20.0, 180.0)
        }
    }
}

/** One end of a link: everything that decides what it sends and what it can hear. */
data class RfStation(
    val heightM: Double,
    val txPowerDbm: Double,
    val antennaGainDbi: Double,
    /** Feedline, connectors and duplexer, in dB. */
    val lineLossDb: Double,
    /** Weakest signal heard reliably (12 dB SINAD), in dBm. */
    val sensitivityDbm: Double,
    val pattern: AntennaPattern = AntennaPattern.Omni,
) {
    /** Effective radiated power toward [bearingDeg], in dBm (relative to an isotropic antenna). */
    fun eirpDbm(bearingDeg: Double): Double = txPowerDbm - lineLossDb + antennaGainDbi + pattern.relativeDb(bearingDeg)

    /** What reaches the receiver input from a signal of [fieldDbm] arriving from [bearingDeg]. */
    fun receivedDbm(fieldDbm: Double, bearingDeg: Double): Double =
        fieldDbm + antennaGainDbi + pattern.relativeDb(bearingDeg) - lineLossDb

    companion object {
        fun wattsToDbm(watts: Double): Double = 10 * log10(watts * 1000.0)
    }
}

/** Which way a link is being judged. */
enum class Direction {
    /** My transmitter, their receiver: "Who hears me". */
    TALK,

    /** Their transmitter, my receiver: "Who I hear". */
    LISTEN,
}

/** Margin bands: what a margin means for actually making the contact. */
enum class Verdict {
    LIKELY, MARGINAL, UNLIKELY;

    companion object {
        /** Margin over the receiver's sensitivity at which a contact is likely, in dB. */
        const val LIKELY_DB = 10.0

        fun of(marginDb: Double): Verdict = when {
            marginDb >= LIKELY_DB -> LIKELY
            marginDb >= 0.0 -> MARGINAL
            else -> UNLIKELY
        }
    }
}

/** One direction of a link, worked out. */
data class LinkBudget(
    val direction: Direction,
    val eirpDbm: Double,
    val pathLossDb: Double,
    val receivedDbm: Double,
    val sensitivityDbm: Double,
) {
    val marginDb: Double get() = receivedDbm - sensitivityDbm
    val verdict: Verdict get() = Verdict.of(marginDb)

    companion object {
        /**
         * [me] at one end, [them] at the other, [bearingDeg] from me to them, and the basic
         * transmission loss of the path. ITM is reciprocal, so one loss serves both directions.
         */
        fun of(direction: Direction, me: RfStation, them: RfStation, bearingDeg: Double, pathLossDb: Double): LinkBudget {
            val back = (bearingDeg + 180.0) % 360.0
            return when (direction) {
                Direction.TALK -> {
                    val eirp = me.eirpDbm(bearingDeg)
                    LinkBudget(direction, eirp, pathLossDb, them.receivedDbm(eirp - pathLossDb, back), them.sensitivityDbm)
                }
                Direction.LISTEN -> {
                    val eirp = them.eirpDbm(back)
                    LinkBudget(direction, eirp, pathLossDb, me.receivedDbm(eirp - pathLossDb, bearingDeg), me.sensitivityDbm)
                }
            }
        }
    }
}
