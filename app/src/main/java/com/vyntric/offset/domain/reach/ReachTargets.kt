/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain.reach

import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.propagation.Direction
import com.vyntric.offset.propagation.Earth
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.PathCheck
import com.vyntric.offset.propagation.PathLossModel
import com.vyntric.offset.propagation.RfStation
import com.vyntric.offset.propagation.Terrain
import com.vyntric.offset.propagation.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A repeater or place on the Reach map, with its link to the station worked out both ways. */
data class ReachTarget(
    val key: String,
    val name: String,
    val point: GeoPoint,
    /** What Offset assumed about the far end, for the link card. */
    val far: RfStation,
    val farLabel: String,
    val check: PathCheck,
    val repeater: Repeater? = null,
    val place: Place? = null,
    /** Set when the terrain along this path is incomplete, so the answer leans on smooth ground. */
    val terrainNote: String? = null,
) {
    fun marginDb(direction: Direction): Double = when (direction) {
        Direction.TALK -> check.talk.marginDb
        Direction.LISTEN -> check.listen.marginDb
    }

    fun verdict(direction: Direction): Verdict = Verdict.of(marginDb(direction))
}

object ReachTargets {
    /**
     * The repeater search for a coverage map on [band] around [origin], filtered to the band
     * before the [MAX_REPEATERS] limit so other bands cannot crowd it out. Null on bands the
     * directories do not carry (they hold 2 m, 70 cm and GMRS).
     */
    fun repeaterQuery(origin: GeoPoint, radiusM: Double, band: ReachBand): com.vyntric.offset.domain.RepeaterQuery? {
        val filters = when (band) {
            ReachBand.TWO_METER -> com.vyntric.offset.domain.RepeaterFilters(setOf(com.vyntric.offset.domain.Service.AMATEUR), setOf(com.vyntric.offset.protocol.Band.TWO_METER))
            ReachBand.SEVENTY_CM -> com.vyntric.offset.domain.RepeaterFilters(setOf(com.vyntric.offset.domain.Service.AMATEUR), setOf(com.vyntric.offset.protocol.Band.SEVENTY_CM))
            ReachBand.GMRS -> com.vyntric.offset.domain.RepeaterFilters(setOf(com.vyntric.offset.domain.Service.GMRS), com.vyntric.offset.protocol.Band.entries.toSet())
            ReachBand.SIX_METER, ReachBand.ONE_TWENTY_FIVE -> return null
        }
        return com.vyntric.offset.domain.RepeaterQuery.Near(origin.latitude, origin.longitude, radiusM / 1000.0, filters, limit = MAX_REPEATERS)
    }

    /** At most this many repeaters are checked, the nearest first as providers sort them. */
    const val MAX_REPEATERS = 250

    /**
     * Works out the link from [me] at [origin] to each repeater with coordinates on [band], and
     * to each place. Repeaters use [assumptions]; places use their own far station type.
     * [terrain] covers the mapped area (repeaters are searched within it); a place beyond it gets
     * its path's terrain from [placeTerrain].
     */
    fun check(
        terrain: Terrain,
        model: PathLossModel,
        origin: GeoPoint,
        me: RfStation,
        band: ReachBand,
        repeaters: List<Repeater>,
        places: List<Place>,
        assumptions: RepeaterAssumptions,
        stepM: Double,
        placeTerrain: (Place) -> Terrain = { terrain },
    ): List<ReachTarget> {
        val onBand = repeaters.filter { ReachBand.of(it.outputHz) == band && it.latitude != null && it.longitude != null }
            .take(MAX_REPEATERS)
        val repeaterFar = assumptions.rf()
        val fromRepeaters = onBand.map { r ->
            val point = GeoPoint(r.latitude!!, r.longitude!!)
            ReachTarget(
                key = "r:${r.id}",
                name = r.callsign,
                point = point,
                far = repeaterFar,
                farLabel = "Repeater",
                check = PathCheck.of(terrain, model, origin, me, point, repeaterFar, r.outputHz / 1e6, stepM),
                repeater = r,
            )
        }
        val fromPlaces = places.map { p ->
            val far = p.far.rf()
            ReachTarget(
                key = "p:${p.id}",
                name = p.name,
                point = p.point,
                far = far,
                farLabel = p.far.label,
                check = PathCheck.of(placeTerrain(p), model, origin, me, p.point, far, band.centerMhz, stepM),
                place = p,
            )
        }
        return fromPlaces + fromRepeaters
    }

    /** Terrain fetched for a path, and whether all of it could be had. */
    data class PathTerrain(val terrain: Terrain, val complete: Boolean)

    /** At most this many places outside the mapped area get terrain of their own, the nearest first. */
    const val MAX_FAR_PLACES = 20

    /** Terrain loads for far places run at most this many at a time. */
    const val FAR_PLACE_LOADS = 2

    /** Places further than this are modelled over smooth ground: no VHF/UHF path that long closes over land. */
    const val MAX_PLACE_PATH_M = 300_000.0

    /** Extra terrain loaded beside a long path to a place, so its ends are covered. */
    const val PATH_MARGIN_M = 2_000.0

    /**
     * Checks every repeater and place, nearest work first. Everything inside the mapped area
     * ([mapped], half side [coveredM] around [origin]) is checked at once and handed to [onNear],
     * so the list never waits on a download. Places beyond it get terrain of their own along the
     * path from [loadTerrain] (a box centred on [GeoPoint] of half side [Double] metres), a few at
     * a time, and the full list is returned. Places too far to matter, or past the cap, are
     * checked over smooth ground and say so.
     */
    suspend fun checkAll(
        mapped: Terrain,
        coveredM: Double,
        model: PathLossModel,
        origin: GeoPoint,
        me: RfStation,
        band: ReachBand,
        repeaters: List<Repeater>,
        places: List<Place>,
        assumptions: RepeaterAssumptions,
        stepM: Double,
        loadTerrain: suspend (GeoPoint, Double) -> PathTerrain,
        onNear: (List<ReachTarget>) -> Unit = {},
    ): List<ReachTarget> {
        fun distance(p: Place) = Earth.distanceM(origin, p.point)
        val outside = places.filter { distance(it) > coveredM }
        val loadable = outside.filter { distance(it) <= MAX_PLACE_PATH_M }.sortedBy(::distance).take(MAX_FAR_PLACES)
        val smoothOnly = outside - loadable.toSet()
        val inside = places - outside.toSet()

        val near = withContext(Dispatchers.Default) {
            check(mapped, model, origin, me, band, repeaters, inside, assumptions, stepM) +
                check(Terrain.FLAT, model, origin, me, band, emptyList(), smoothOnly, assumptions, stepM).map {
                    it.copy(terrainNote = if (distance(it.place!!) > MAX_PLACE_PATH_M) "Too far for VHF or UHF over land; modelled over smooth ground."
                    else "Terrain for this path was not loaded (too many distant places); modelled over smooth ground.")
                }
        }
        onNear(near)
        if (loadable.isEmpty()) return near

        val gate = Semaphore(FAR_PLACE_LOADS)
        val loaded = coroutineScope {
            loadable.map { p ->
                async {
                    gate.withPermit {
                        val d = distance(p)
                        val mid = Earth.destination(origin, Earth.bearingDeg(origin, p.point), d / 2)
                        p.id to loadTerrain(mid, d / 2 + PATH_MARGIN_M)
                    }
                }
            }.awaitAll().toMap()
        }
        val far = withContext(Dispatchers.Default) {
            check(mapped, model, origin, me, band, emptyList(), loadable, assumptions, stepM) { loaded.getValue(it.id).terrain }.map { t ->
                if (loaded.getValue(t.place!!.id).complete) t
                else t.copy(terrainNote = "Some terrain along this path could not be loaded; those parts assume smooth ground.")
            }
        }
        return far + near
    }
}
