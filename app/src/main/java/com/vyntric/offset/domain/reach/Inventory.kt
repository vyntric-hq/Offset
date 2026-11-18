/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain.reach

import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.propagation.AntennaPattern
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.RfStation
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.pow

/** The line-of-sight bands Reach models. HF is deliberately absent; see docs/REACH.md. */
enum class ReachBand(val label: String, val centerMhz: Double, val lowMhz: Double, val highMhz: Double) {
    SIX_METER("6 m", 52.525, 50.0, 54.0),
    TWO_METER("2 m", 146.52, 144.0, 148.0),
    ONE_TWENTY_FIVE("1.25 m", 223.5, 219.0, 225.0),
    SEVENTY_CM("70 cm", 446.0, 420.0, 450.0),
    GMRS("GMRS", 462.6375, 462.0, 467.8),
    ;

    /** The band whose antenna gain applies when an antenna does not list this one. */
    val gainFallback: ReachBand? get() = if (this == GMRS) SEVENTY_CM else null

    /** Only 6 m can be carried far beyond the horizon by the ionosphere (sporadic E). */
    val hasSkipCaveat: Boolean get() = this == SIX_METER

    companion object {
        fun of(frequencyHz: Long): ReachBand? {
            if (Gmrs.isGmrs(frequencyHz)) return GMRS
            val mhz = frequencyHz / 1e6
            return entries.firstOrNull { it != GMRS && mhz >= it.lowMhz && mhz <= it.highMhz }
        }
    }
}

enum class RadioKind(val label: String) { HANDHELD("Handheld"), MOBILE("Mobile"), BASE("Base") }

/** A radio the user owns: what it can transmit on each band and how well it hears. */
@Serializable
data class Radio(
    val id: Long = 0,
    val name: String,
    val kind: RadioKind,
    /** Highest power per band, in watts. A band missing here is one the radio does not have. */
    val maxWatts: Map<ReachBand, Double>,
    /** Power levels the user can pick (watts), highest first. */
    val levels: List<Double>,
    val sensitivityDbm: Double = DEFAULT_SENSITIVITY_DBM,
    val catalogKey: String? = null,
) {
    val bands: List<ReachBand> get() = ReachBand.entries.filter { it in maxWatts }

    companion object {
        /** 0.2 microvolts for 12 dB SINAD into 50 ohms, the usual figure on amateur spec sheets. */
        const val DEFAULT_SENSITIVITY_DBM = -121.0
    }
}

enum class AntennaKind(val label: String, val directional: Boolean = false) {
    HANDHELD_WHIP("Handheld antenna"),
    VERTICAL("Vertical"),
    MOBILE("Mobile antenna"),
    J_POLE("J-pole"),
    COLLINEAR("Collinear"),
    YAGI("Yagi beam", directional = true),
}

/** An antenna the user owns: gain per band in dBi. A band missing here is one it does not cover. */
@Serializable
data class Antenna(
    val id: Long = 0,
    val name: String,
    val kind: AntennaKind,
    val gainDbi: Map<ReachBand, Double>,
    val catalogKey: String? = null,
) {
    fun gain(band: ReachBand): Double? = gainDbi[band] ?: band.gainFallback?.let { gainDbi[it] }
    fun covers(band: ReachBand): Boolean = gain(band) != null
}

/** Coaxial cable, with matched loss per 100 feet at 144 and 440 MHz from manufacturer tables. */
enum class Feedline(val label: String, private val loss144: Double, private val loss440: Double) {
    RG58("RG-58", 6.1, 11.7),
    RG8X("RG-8X", 4.7, 8.6),
    RG213("RG-213", 2.8, 5.1),
    LMR400("LMR-400", 1.5, 2.7),
    ;

    /** Loss in dB over [lengthM] at [mhz], scaled between the two table points as f^k. */
    fun lossDb(lengthM: Double, mhz: Double): Double {
        val k = ln(loss440 / loss144) / ln(440.0 / 144.0)
        return loss144 * (mhz / 144.0).pow(k) * lengthM / FEET_100_M
    }

    private companion object {
        const val FEET_100_M = 30.48
    }
}

/** Where a station is: wherever the phone is, or a fixed spot (a home station). */
@Serializable
sealed interface StationLocation {
    @Serializable
    data object Gps : StationLocation

    @Serializable
    data class Fixed(val latitude: Double, val longitude: Double, val label: String) : StationLocation
}

/** A saved station: one radio, one antenna, and how they are set up. */
@Serializable
data class Station(
    val id: Long = 0,
    val name: String,
    val radioId: Long,
    val antennaId: Long,
    val watts: Double,
    /** Antenna height above the ground. */
    val heightM: Double,
    val feedline: Feedline? = null,
    val feedlineM: Double = 0.0,
    /** Where a beam points, degrees from true north. Ignored for omnidirectional antennas. */
    val bearingDeg: Double? = null,
    val location: StationLocation = StationLocation.Gps,
    val lastUsedAt: Long = 0,
)

/** A station with its radio and antenna resolved, ready to model on one band. */
data class StationSetup(val station: Station, val radio: Radio, val antenna: Antenna) {
    val bands: List<ReachBand> get() = radio.bands.filter { antenna.covers(it) }

    /** Watts actually transmitted on [band]: the chosen level, capped by what the radio has there. */
    fun watts(band: ReachBand): Double = minOf(station.watts, radio.maxWatts[band] ?: 0.0)

    fun lineLossDb(band: ReachBand): Double = station.feedline?.lossDb(station.feedlineM, band.centerMhz) ?: 0.0

    fun rf(band: ReachBand): RfStation {
        val gain = antenna.gain(band) ?: 0.0
        val pattern = if (antenna.kind.directional && station.bearingDeg != null) {
            AntennaPattern.Beam(station.bearingDeg, AntennaPattern.Beam.beamwidthFor(gain))
        } else AntennaPattern.Omni
        return RfStation(
            heightM = station.heightM,
            txPowerDbm = RfStation.wattsToDbm(watts(band).coerceAtLeast(0.001)),
            antennaGainDbi = gain,
            lineLossDb = lineLossDb(band),
            sensitivityDbm = radio.sensitivityDbm,
            pattern = pattern,
        )
    }
}

/**
 * What Offset assumes about a far station it knows nothing about. Shown on every link card and
 * editable there. Numbers are documented in docs/REACH.md.
 */
@Serializable
enum class FarStation(val label: String, val heightM: Double, val watts: Double, val gainDbi: Double, val lineLossDb: Double, val sensitivityDbm: Double) {
    HANDHELD("Handheld", 1.5, 5.0, 0.0, 0.0, -121.0),
    MOBILE("Mobile", 1.8, 50.0, 2.15, 1.0, -121.0),
    BASE("Base station", 10.0, 50.0, 6.0, 2.0, -121.0),
    REPEATER("Repeater", 30.0, 50.0, 6.0, 4.0, -120.0),
    ;

    fun rf(): RfStation = RfStation(heightM, RfStation.wattsToDbm(watts), gainDbi, lineLossDb, sensitivityDbm)
}

/**
 * Repeater assumptions the user can tune (one set for every repeater, since the directories do
 * not list power or height).
 */
@Serializable
data class RepeaterAssumptions(
    val heightM: Double = FarStation.REPEATER.heightM,
    val watts: Double = FarStation.REPEATER.watts,
    val gainDbi: Double = FarStation.REPEATER.gainDbi,
    val lineLossDb: Double = FarStation.REPEATER.lineLossDb,
    val sensitivityDbm: Double = FarStation.REPEATER.sensitivityDbm,
) {
    fun rf(): RfStation = RfStation(heightM, RfStation.wattsToDbm(watts), gainDbi, lineLossDb, sensitivityDbm)
}

/** Somewhere the user wants to reach: a team member, a friend's house, a hilltop. */
@Serializable
data class Place(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val far: FarStation = FarStation.HANDHELD,
    val createdAt: Long = 0,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** Everything the user has set up for Reach. */
data class Inventory(
    val radios: List<Radio> = emptyList(),
    val antennas: List<Antenna> = emptyList(),
    val stations: List<Station> = emptyList(),
    val places: List<Place> = emptyList(),
    val repeaterAssumptions: RepeaterAssumptions = RepeaterAssumptions(),
) {
    fun setup(station: Station): StationSetup? {
        val radio = radios.firstOrNull { it.id == station.radioId } ?: return null
        val antenna = antennas.firstOrNull { it.id == station.antennaId } ?: return null
        return StationSetup(station, radio, antenna)
    }

    /** Stations most recently used first. */
    val stationsByUse: List<Station> get() = stations.sortedByDescending { it.lastUsedAt }
}
