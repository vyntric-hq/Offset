/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.reach.Antenna
import com.vyntric.offset.domain.reach.CoverageSweep
import com.vyntric.offset.domain.reach.FarStation
import com.vyntric.offset.domain.reach.Inventory
import com.vyntric.offset.domain.reach.Place
import com.vyntric.offset.domain.reach.Radio
import com.vyntric.offset.domain.reach.ReachBand
import com.vyntric.offset.domain.reach.ReachTarget
import com.vyntric.offset.domain.reach.ReachTargets
import com.vyntric.offset.domain.reach.RepeaterAssumptions
import com.vyntric.offset.domain.reach.Station
import com.vyntric.offset.domain.reach.StationLocation
import com.vyntric.offset.domain.reach.StationSetup
import com.vyntric.offset.propagation.CoverageGrid
import com.vyntric.offset.propagation.Direction
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.ItmPathLoss
import com.vyntric.offset.propagation.MarginFunction
import com.vyntric.offset.propagation.Terrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the coverage calculation is. */
sealed interface ReachPhase {
    data object Idle : ReachPhase
    data object Locating : ReachPhase
    data class Terrain(val done: Int, val total: Int) : ReachPhase
    data class Modelling(val fraction: Float) : ReachPhase
    data object Ready : ReachPhase
}

/** The sheet showing over the map, if any. */
sealed interface ReachSheet {
    data object Stations : ReachSheet
    data class EditStation(val station: Station?) : ReachSheet
    data class AddPlace(val point: GeoPoint?, val place: Place? = null) : ReachSheet
    data object PastePlaces : ReachSheet
    data class Link(val key: String) : ReachSheet
    data object Assumptions : ReachSheet
    data class MapPress(val point: GeoPoint) : ReachSheet
}

data class ReachUiState(
    val inventory: Inventory? = null,
    val stationId: Long? = null,
    val band: ReachBand? = null,
    val direction: Direction = Direction.TALK,
    val far: FarStation = FarStation.HANDHELD,
    /** Where the station is being modelled from, and how that was decided. */
    val origin: GeoPoint? = null,
    val originLabel: String = "",
    /** The user moved the station on the map; GPS no longer moves it. */
    val originPinned: Boolean = false,
    val phase: ReachPhase = ReachPhase.Idle,
    /** How far the station reaches (marginal or better) in its best direction, once modelled. */
    val reachM: Double? = null,
    /** Bumped whenever the coverage grid gains radials, so the map redraws. */
    val gridVersion: Int = 0,
    /** Terrain for part of the area was missing: the map is a smooth-earth estimate there. */
    val terrainMissing: Boolean = false,
    val targets: List<ReachTarget> = emptyList(),
    val repeatersNote: String? = null,
    /** The station follows GPS but Offset may not read the location yet. */
    val needsLocationPermission: Boolean = false,
    val sheet: ReachSheet? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val setup: StationSetup?
        get() = inventory?.let { inv -> inv.stations.firstOrNull { it.id == stationId }?.let(inv::setup) }

    val bands: List<ReachBand> get() = setup?.bands.orEmpty()

    fun target(key: String): ReachTarget? = targets.firstOrNull { it.key == key }

    /** Targets best first for the current direction. */
    val ranked: List<ReachTarget> get() = targets.sortedByDescending { it.marginDb(direction) }
}

class ReachViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ReachUiState())
    val state: StateFlow<ReachUiState> = _state.asStateFlow()

    /** The coverage being drawn. Mutated by worker threads; [ReachUiState.gridVersion] says when. */
    @Volatile
    var grid: CoverageGrid? = null
        private set

    private var terrain: Terrain = Terrain.FLAT
    private val sweep = CoverageSweep(
        loadTerrain = { center, radius, progress -> c.terrain.load(center, radius, progress).let { CoverageSweep.Loaded(it.terrain, it.complete) } },
        model = ItmPathLoss,
    )
    private var stepM = 250.0

    /** Half the side of the square [terrain] covers around the origin. */
    private var coveredRadiusM = 0.0
    private var computeJob: Job? = null
    private var targetsJob: Job? = null
    private var repeaters: List<Repeater> = emptyList()

    init {
        viewModelScope.launch {
            c.inventory.observe().collect { inv ->
                val before = _state.value
                val stationId = before.stationId?.takeIf { id -> inv.stations.any { it.id == id } }
                    ?: inv.stationsByUse.firstOrNull()?.id
                _state.update { it.copy(inventory = inv, stationId = stationId) }
                fixBand()
                val after = _state.value
                val placesChanged = before.inventory?.places != inv.places ||
                    before.inventory?.repeaterAssumptions != inv.repeaterAssumptions
                val setupChanged = before.setup != after.setup
                when {
                    after.setup == null -> {
                        // No station (the last one was deleted): nothing of the old one stays on the map.
                        computeJob?.cancel()
                        targetsJob?.cancel()
                        grid = null
                        repeaters = emptyList()
                        _state.update { it.copy(targets = emptyList(), reachM = null, phase = ReachPhase.Idle, origin = null, gridVersion = it.gridVersion + 1) }
                    }
                    // Anything that moves the path losses (where, how high, which band) needs a new sweep.
                    before.inventory == null || stationIdChanged(before, after) || heightChanged(before, after) ||
                        locationChanged(before, after) || before.band != after.band ||
                        // A taller assumed repeater widens the area searched and modelled.
                        before.inventory?.repeaterAssumptions?.heightM != inv.repeaterAssumptions.heightM -> recompute()
                    // Power, gain, feedline or beam only change margins: redraw, re-check, re-measure reach.
                    setupChanged || placesChanged -> {
                        recheckTargets()
                        _state.update { it.copy(gridVersion = it.gridVersion + 1) }
                        updateReach()
                    }
                }
            }
        }
        // A different repeater provider in Settings means different repeaters on the map.
        viewModelScope.launch {
            c.settings.settings.map { it.provider }.distinctUntilChanged().drop(1).collect {
                repeaters = emptyList()
                if (_state.value.setup != null) recompute()
            }
        }
    }

    private fun stationIdChanged(a: ReachUiState, b: ReachUiState) = a.stationId != b.stationId
    private fun heightChanged(a: ReachUiState, b: ReachUiState) = a.setup?.station?.heightM != b.setup?.station?.heightM
    private fun locationChanged(a: ReachUiState, b: ReachUiState) = a.setup?.station?.location != b.setup?.station?.location

    /** Keeps the chosen band one the station can use. */
    private fun fixBand() = _state.update { s ->
        val bands = s.bands
        s.copy(band = s.band?.takeIf { it in bands } ?: bands.firstOrNull())
    }

    /** The margin function for drawing the map in the current direction. */
    fun margins(): MarginFunction? {
        val s = _state.value
        val setup = s.setup ?: return null
        val band = s.band ?: return null
        return MarginFunction.of(s.direction, setup.rf(band), s.far.rf())
    }

    fun selectStation(id: Long) {
        _state.update { it.copy(stationId = id, sheet = null, originPinned = false) }
        fixBand()
        viewModelScope.launch { _state.value.inventory?.stations?.firstOrNull { it.id == id }?.let { c.inventory.markUsed(it) } }
        recompute()
    }

    fun setBand(band: ReachBand) {
        if (band == _state.value.band) return
        _state.update { it.copy(band = band) }
        recompute()
    }

    /** Direction only changes how loss becomes margin: no recomputation. */
    fun setDirection(d: Direction) {
        _state.update { it.copy(direction = d, gridVersion = it.gridVersion + 1) }
        updateReach()
    }

    /** Recomputes how far the coverage reaches after something that only changes margins. */
    private fun updateReach() {
        val g = grid ?: return
        if (_state.value.phase != ReachPhase.Ready) return
        val m = margins() ?: return
        _state.update { it.copy(reachM = g.reachM(m)) }
    }

    fun setFar(far: FarStation) {
        if (far == _state.value.far) return
        val sameHeight = far.heightM == _state.value.far.heightM
        _state.update { it.copy(far = far, gridVersion = it.gridVersion + 1) }
        if (!sameHeight) recompute()
    }

    fun refresh() = recompute(relocate = true)

    /** Models the station from [point] instead of its usual location. */
    fun moveStation(point: GeoPoint) {
        _state.update { it.copy(origin = point, originLabel = "Dropped pin", originPinned = true, sheet = null) }
        recompute()
    }

    fun followGps() {
        _state.update { it.copy(originPinned = false) }
        recompute(relocate = true)
    }

    fun locationGranted() {
        _state.update { it.copy(needsLocationPermission = false) }
        recompute(relocate = true)
    }

    fun locationDenied() = _state.update {
        it.copy(
            needsLocationPermission = false, phase = ReachPhase.Idle,
            error = "Location permission is off. Allow it, or long press the map to place your station.",
        )
    }

    // Sheets

    fun open(sheet: ReachSheet) = _state.update { it.copy(sheet = sheet) }
    fun closeSheet() = _state.update { it.copy(sheet = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    // Inventory edits

    /** Saves a station with its radio and antenna (adding them to the inventory when new) and switches to it. */
    fun saveStation(station: Station, radio: Radio, antenna: Antenna) = viewModelScope.launch {
        val id = c.inventory.saveStation(station.copy(lastUsedAt = c.clock.millis()), radio, antenna)
        // Room may emit the new inventory before this line runs, so switch to the station
        // explicitly; a second recompute from the inventory collector only replaces this one.
        _state.update { it.copy(stationId = id, sheet = null, message = "Saved ${station.name}.", originPinned = false) }
        fixBand()
        recompute()
    }

    fun deleteStation(station: Station) = viewModelScope.launch {
        c.inventory.delete(station.id)
        _state.update { it.copy(sheet = ReachSheet.Stations, message = "Deleted ${station.name}.") }
    }

    fun savePlace(place: Place) = viewModelScope.launch {
        if (place.id == 0L && (_state.value.inventory?.places?.size ?: 0) >= MAX_PLACES) {
            _state.update { it.copy(message = "Reach keeps up to $MAX_PLACES places. Remove one to add another.") }
            return@launch
        }
        c.inventory.save(place)
        _state.update { it.copy(sheet = null, message = if (place.id == 0L) "Added ${place.name}." else "Saved ${place.name}.") }
    }

    fun addPlaces(places: List<Place>) = viewModelScope.launch {
        val room = (MAX_PLACES - (_state.value.inventory?.places?.size ?: 0)).coerceAtLeast(0)
        val kept = places.take(room)
        c.inventory.saveAll(kept)
        val skipped = places.size - kept.size
        _state.update {
            it.copy(sheet = null, message = "Added ${kept.size} ${if (kept.size == 1) "place" else "places"}." +
                (if (skipped > 0) " $skipped not added: Reach keeps up to $MAX_PLACES places." else ""))
        }
    }

    /** Changes what kind of station a place has, keeping its link card open. */
    fun setPlaceFar(place: Place, far: FarStation) = viewModelScope.launch { c.inventory.save(place.copy(far = far)) }

    fun deletePlace(place: Place) = viewModelScope.launch {
        c.inventory.delete(place.id)
        _state.update { it.copy(sheet = null, message = "Removed ${place.name}.") }
    }

    fun saveAssumptions(a: RepeaterAssumptions) = viewModelScope.launch {
        c.inventory.save(a)
        _state.update { it.copy(sheet = null) }
    }

    /** Adds the repeater to staging, as the Repeaters tab does. */
    fun addToStaging(repeater: Repeater) = viewModelScope.launch {
        val target = c.stagingTarget()
        val r = c.staging.update { list -> c.buildStaging.addRepeaters(list, listOf(repeater), target).let { it.staging to it } }
        _state.update {
            it.copy(message = when {
                r.added > 0 -> "Added ${repeater.callsign} to staging."
                r.duplicates > 0 -> "${repeater.callsign} is already staged."
                else -> "Staging is full (${target.capacity} channels)."
            })
        }
    }

    // Calculation

    private fun recompute(relocate: Boolean = false) {
        computeJob?.cancel()
        targetsJob?.cancel()
        computeJob = viewModelScope.launch {
            try {
                run(relocate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = ReachPhase.Idle, error = "Could not model coverage: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun run(relocate: Boolean) {
        val s0 = _state.value
        val setup = s0.setup ?: return
        val band = s0.band ?: return
        // A new run starts clean, before anything can fail: no coverage or pins from the last
        // station, place or band linger under an error.
        grid = null
        repeaters = emptyList()
        targetsJob?.cancel()
        _state.update { it.copy(error = null, reachM = null, targets = emptyList(), gridVersion = it.gridVersion + 1) }
        val origin = resolveOrigin(setup, relocate) ?: return
        val me = setup.rf(band)
        val sweepSetup = CoverageSweep.Setup(
            origin, me.heightM, s0.far.heightM, _state.value.inventory?.repeaterAssumptions?.heightM ?: 0.0, band.centerMhz,
        )
        _state.update { it.copy(phase = ReachPhase.Terrain(0, 0)) }
        // Repeaters load (the bundled directory takes a few seconds to parse) while coverage is
        // modelled; both are children of this run, so a new run cancels both.
        val finished = coroutineScope {
            sweep.run(sweepSetup) { p ->
                when (p) {
                    is CoverageSweep.Progress.Terrain -> _state.update { it.copy(phase = ReachPhase.Terrain(p.done, p.total)) }
                    is CoverageSweep.Progress.Ready -> {
                        terrain = p.area.terrain
                        coveredRadiusM = p.area.radiusM
                        stepM = p.area.stepM
                        _state.update { it.copy(terrainMissing = !p.area.terrainComplete) }
                        recheckTargets()
                        launch {
                            loadRepeaters(origin, p.area.radiusM, band)
                            recheckTargets()
                        }
                    }
                    is CoverageSweep.Progress.Modelling -> {
                        grid = p.grid
                        _state.update { it.copy(phase = ReachPhase.Modelling(p.fraction), gridVersion = it.gridVersion + 1) }
                    }
                }
            }
        }
        val reach = margins()?.let { finished.reachM(it) }
        _state.update { it.copy(phase = ReachPhase.Ready, gridVersion = it.gridVersion + 1, reachM = reach) }
    }

    private suspend fun resolveOrigin(setup: StationSetup, relocate: Boolean): GeoPoint? {
        val s = _state.value
        val loc = setup.station.location
        if (s.originPinned && s.origin != null) return s.origin
        if (loc is StationLocation.Fixed) {
            val p = GeoPoint(loc.latitude, loc.longitude)
            _state.update { it.copy(origin = p, originLabel = loc.label) }
            return p
        }
        if (!relocate && s.origin != null && s.originLabel == GPS_LABEL) return s.origin
        if (!c.location.hasPermission()) {
            _state.update { it.copy(phase = ReachPhase.Idle, needsLocationPermission = true, origin = null) }
            return null
        }
        if (!c.location.isEnabled()) {
            _state.update { it.copy(phase = ReachPhase.Idle, origin = null, error = "Location is off on this phone. Turn it on, or long press the map to place your station.") }
            return null
        }
        _state.update { it.copy(phase = ReachPhase.Locating) }
        val fix = c.location.current()
        if (fix == null) {
            _state.update { it.copy(phase = ReachPhase.Idle, origin = null, error = "Could not get a location fix. Try again outdoors, or long press the map to place your station.") }
            return null
        }
        val p = GeoPoint(fix.latitude, fix.longitude)
        _state.update { it.copy(origin = p, originLabel = GPS_LABEL) }
        return p
    }

    private suspend fun loadRepeaters(origin: GeoPoint, radiusM: Double, band: ReachBand) {
        val provider = c.settings.settings.value.provider
        val query = ReachTargets.repeaterQuery(origin, radiusM, band) ?: run {
            repeaters = emptyList()
            _state.update { it.copy(repeatersNote = "Offset's repeater directories cover 2 m, 70 cm and GMRS, so no ${band.label} repeaters are shown.") }
            return
        }
        repeaters = try {
            c.provider(provider).search(query).repeaters
                .also { _state.update { it.copy(repeatersNote = null) } }
        } catch (e: RepeaterException) {
            _state.update { it.copy(repeatersNote = "Repeaters from ${provider.displayName} are not shown: ${e.message}") }
            emptyList()
        }
    }

    /** Re-checks every repeater and place against the current station: fast, no new terrain. */
    private fun recheckTargets() {
        targetsJob?.cancel()
        val s = _state.value
        val setup = s.setup ?: return
        val band = s.band ?: return
        val origin = s.origin ?: return
        val inv = s.inventory ?: return
        val near = terrain
        val covered = coveredRadiusM
        targetsJob = viewModelScope.launch {
            val targets = ReachTargets.checkAll(
                near, covered, ItmPathLoss, origin, setup.rf(band), band, repeaters, inv.places, inv.repeaterAssumptions, stepM,
                loadTerrain = { center, half -> c.terrain.load(center, half).let { ReachTargets.PathTerrain(it.terrain, it.complete) } },
                onNear = { first -> _state.update { it.copy(targets = first) } },
            )
            _state.update { it.copy(targets = targets) }
        }
    }

    companion object {
        const val GPS_LABEL = "You"

        /** Most places kept, well inside the inventory's row limit so nothing is ever hidden. */
        const val MAX_PLACES = 500

    }
}
