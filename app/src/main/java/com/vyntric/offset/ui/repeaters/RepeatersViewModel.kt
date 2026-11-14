/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.repeaters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.BandFilter
import com.vyntric.offset.data.Fix
import com.vyntric.offset.data.Settings
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.ChannelOrigin
import com.vyntric.offset.domain.County
import com.vyntric.offset.domain.Geo
import com.vyntric.offset.domain.Maidenhead
import com.vyntric.offset.domain.DataSummary
import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterFilters
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.Service
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.ui.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchMode(val label: String) { NEAR("Near me"), STATE("By state"), TEXT("Search") }

enum class ServiceFilter(val label: String, val services: Set<Service>) {
    AMATEUR("Amateur", setOf(Service.AMATEUR)),
    GMRS("GMRS", setOf(Service.GMRS)),
    BOTH("Both", setOf(Service.AMATEUR, Service.GMRS)),
}

/** Where "near me" searches from. */
enum class LocationMode(val label: String) { GPS("GPS"), GRID("Grid"), LATLON("Lat/Long"), COUNTY("County") }

/** The point a search is centred on, whatever its source. */
data class SearchCenter(val latitude: Double, val longitude: Double, val label: String) {
    val grid: String get() = Maidenhead.fromLatLon(latitude, longitude)
    val pair: Pair<Double, Double> get() = latitude to longitude
}

/** What the map shows: nothing (list view), the current results, or everything on the phone. */
enum class MapMode { NONE, RESULTS, ALL }

/** Adding repeaters or channels waits for this when they include GMRS and the warning is unread. */
sealed interface PendingGmrs {
    data class Repeaters(val repeaters: List<Repeater>) : PendingGmrs
    data class Manual(val channel: Channel) : PendingGmrs
}

data class RepeatersUiState(
    val mode: SearchMode = SearchMode.NEAR,
    val service: ServiceFilter = ServiceFilter.AMATEUR,
    val radius: Int = 25,
    val band: BandFilter = BandFilter.BOTH,
    val hideMixed: Boolean = false,
    val units: Units = Units.MILES,
    val provider: RepeaterSource = RepeaterSource.HEARHAM,
    val supportsCounty: Boolean = false,
    val fix: Fix? = null,
    val locating: Boolean = false,
    val locationError: String? = null,
    val state: UsState? = null,
    val county: String? = null,
    val counties: List<String> = emptyList(),
    val text: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<Repeater>? = null,
    val fetchedAt: Long? = null,
    val fromCache: Boolean = false,
    val cachedAt: Long? = null,
    val summary: DataSummary? = null,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
    val map: MapMode = MapMode.NONE,
    val mapAll: List<Repeater>? = null,
    val mapLoading: Boolean = false,
    val pendingGmrs: PendingGmrs? = null,
    val locationMode: LocationMode = LocationMode.GPS,
    val gridText: String = "",
    val latLonText: String = "",
    val countyState: UsState? = null,
    val countyPick: County? = null,
    val countyOptions: List<County> = emptyList(),
    val manualError: String? = null,
    val liveTracking: Boolean = false,
) {
    /** The centre of a "near me" search: the GPS fix or the typed or chosen place. */
    val center: SearchCenter?
        get() = when (locationMode) {
            LocationMode.GPS -> fix?.let { SearchCenter(it.latitude, it.longitude, "You") }
            LocationMode.GRID -> Maidenhead.toLatLon(gridText)?.let { SearchCenter(it.first, it.second, gridText.trim().uppercase().let { g -> g.take(4) + g.drop(4).lowercase() }) }
            LocationMode.LATLON -> Maidenhead.parseLatLon(latLonText)?.let { SearchCenter(it.first, it.second, "Point") }
            LocationMode.COUNTY -> countyPick?.let { SearchCenter(it.latitude, it.longitude, it.name) }
        }

    val selectedRepeaters: List<Repeater> get() = results.orEmpty().filter { it.id in selected }
    val filters: RepeaterFilters get() = RepeaterFilters(service.services, band.bands, hideMixed)
    val mapRepeaters: List<Repeater> get() = if (map == MapMode.ALL) mapAll.orEmpty() else results.orEmpty()
}

class RepeatersViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        c.settings.settings.value.let { s ->
            RepeatersUiState(radius = s.defaultRadius, band = s.defaultBand, units = s.units, provider = s.provider)
        }
    )
    val state: StateFlow<RepeatersUiState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var quietRefreshTried = false
    private var trackingJob: Job? = null

    init {
        viewModelScope.launch {
            c.settings.settings.map { it.provider to it.units }.distinctUntilChanged().collect { (provider, units) ->
                val changed = provider != _state.value.provider
                _state.update {
                    it.copy(
                        provider = provider, units = units, supportsCounty = c.provider(provider).supportsCounty,
                        results = if (changed) null else it.results, selected = if (changed) emptySet() else it.selected,
                        error = if (changed) null else it.error, county = if (changed) null else it.county,
                        mapAll = if (changed) null else it.mapAll,
                    )
                }
                refreshSummary()
            }
        }
    }

    fun setMode(m: SearchMode) = _state.update { it.copy(mode = m, results = null, error = null, selected = emptySet()) }
    fun setService(s: ServiceFilter) = refilter { it.copy(service = s) }
    fun setRadius(r: Int) = refilter { it.copy(radius = r) }
    fun setBand(b: BandFilter) = refilter { it.copy(band = b) }
    fun setHideMixed(on: Boolean) = refilter { it.copy(hideMixed = on) }
    fun setUsState(s: UsState) { _state.update { it.copy(state = s, county = null, counties = emptyList()) }; search() }
    fun setCounty(county: String?) { _state.update { it.copy(county = county) }; search() }
    fun setText(t: String) = _state.update { it.copy(text = t) }

    /** Filter changes rerun the search (cheap: providers serve from cache) and the all-USA map. */
    private fun refilter(change: (RepeatersUiState) -> RepeatersUiState) {
        _state.update(change)
        if (_state.value.results != null) search()
        if (_state.value.map == MapMode.ALL) loadAll()
    }

    fun setLocationMode(m: LocationMode) {
        _state.update { it.copy(locationMode = m, manualError = null, results = null, selected = emptySet()) }
        if (m != LocationMode.GPS) setLiveTracking(false)
    }

    fun setGridText(t: String) = _state.update { it.copy(gridText = t.filter { c -> c.isLetterOrDigit() }.take(8), manualError = null) }
    fun setLatLonText(t: String) = _state.update { it.copy(latLonText = t, manualError = null) }

    fun setCountyState(s: UsState) = _state.update {
        it.copy(countyState = s, countyPick = null, countyOptions = c.counties.inState(s.abbr), manualError = null)
    }

    fun pickCounty(county: County) {
        _state.update { it.copy(countyPick = county, manualError = null) }
        search()
    }

    /** Re-locates every 30 seconds while on and re-searches after moving more than 500 m. */
    fun setLiveTracking(on: Boolean) {
        _state.update { it.copy(liveTracking = on) }
        trackingJob?.cancel()
        if (!on) return
        trackingJob = viewModelScope.launch {
            while (true) {
                val before = _state.value.fix
                val fix = c.location.current(timeoutMs = 15_000)
                if (fix != null) {
                    _state.update { it.copy(fix = fix) }
                    val moved = before == null || Geo.distanceKm(before.latitude, before.longitude, fix.latitude, fix.longitude) > 0.5
                    if (moved && _state.value.mode == SearchMode.NEAR && _state.value.locationMode == LocationMode.GPS) search()
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun locationDenied() = _state.update { it.copy(locationError = "Location permission is off. Allow it, or search from a grid square, coordinates or county.") }

    /** Gets a fix, then searches when in near-me mode. */
    fun locateAndSearch() {
        if (_state.value.locating) return
        viewModelScope.launch {
            if (!c.location.isEnabled()) {
                _state.update { it.copy(locationError = "Location is turned off on this phone. Turn it on, or search from a grid square, coordinates or county.") }
                return@launch
            }
            _state.update { it.copy(locating = true, locationError = null) }
            val fix = c.location.current()
            _state.update {
                it.copy(locating = false, fix = fix ?: it.fix, locationError = if (fix == null) "Could not get a location fix. Try again outdoors, or search from a grid square, coordinates or county." else null)
            }
            if (fix != null && _state.value.mode == SearchMode.NEAR && _state.value.locationMode == LocationMode.GPS) search()
        }
    }

    fun search(force: Boolean = false) {
        val s = _state.value
        val origin = s.center?.pair ?: s.fix?.let { it.latitude to it.longitude }
        val query: RepeaterQuery = when (s.mode) {
            SearchMode.NEAR -> {
                val center = s.center ?: return when (s.locationMode) {
                    LocationMode.GPS -> locateAndSearch()
                    LocationMode.GRID -> _state.update { it.copy(manualError = "Enter a grid square such as EM78 or EM78gh.") }
                    LocationMode.LATLON -> _state.update { it.copy(manualError = "Enter latitude and longitude, for example 38.25, -85.76.") }
                    LocationMode.COUNTY -> _state.update { it.copy(manualError = "Choose a state and county.") }
                }
                if (s.radius == Settings.CLOSEST) {
                    RepeaterQuery.Near(center.latitude, center.longitude, null, s.filters, limit = Settings.CLOSEST_COUNT)
                } else {
                    RepeaterQuery.Near(center.latitude, center.longitude, Fmt.radiusKm(s.radius, s.units), s.filters)
                }
            }
            SearchMode.STATE -> {
                val st = s.state ?: return _state.update { it.copy(error = null, results = null) }
                RepeaterQuery.ByState(st, s.county, s.filters, origin)
            }
            SearchMode.TEXT -> {
                if (s.text.isBlank()) return _state.update { it.copy(error = null, results = null) }
                RepeaterQuery.Text(s.text, s.filters, origin)
            }
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val result = c.provider(s.provider).search(query, force)
                val counties = if (query is RepeaterQuery.ByState && query.county == null) {
                    result.repeaters.mapNotNull { it.county }.distinct().sorted()
                } else _state.value.counties
                _state.update { st ->
                    st.copy(
                        loading = false, results = result.repeaters, fetchedAt = result.fetchedAtMillis, fromCache = result.fromCache,
                        counties = counties, selected = st.selected.filter { id -> result.repeaters.any { it.id == id } }.toSet(),
                    )
                }
                if (result.stale && !force && !quietRefreshTried && s.provider == RepeaterSource.HEARHAM) quietRefresh(query)
            } catch (e: RepeaterException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
            refreshSummary()
        }
    }

    /**
     * Results were answered from data older than a week (often the bundled snapshot). Try once
     * per session to fetch newer data in the background; offline, the local results simply stay.
     */
    private fun quietRefresh(query: RepeaterQuery) {
        quietRefreshTried = true
        viewModelScope.launch {
            val provider = _state.value.provider
            val fresh = runCatching { c.provider(provider).search(query, forceRefresh = true) }.getOrNull() ?: return@launch
            _state.update { st ->
                if (st.provider != provider || st.loading) st else st.copy(
                    results = fresh.repeaters, fetchedAt = fresh.fetchedAtMillis, fromCache = false,
                    selected = st.selected.filter { id -> fresh.repeaters.any { it.id == id } }.toSet(),
                    message = "Repeater data updated from ${provider.displayName}.",
                )
            }
            refreshSummary()
        }
    }

    fun showMap(mode: MapMode) {
        _state.update { it.copy(map = mode) }
        if (mode == MapMode.ALL) loadAll()
    }

    private fun loadAll() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(mapLoading = true) }
        try {
            val r = c.provider(s.provider).search(RepeaterQuery.All(s.filters, s.center?.pair))
            _state.update { it.copy(mapAll = r.repeaters, mapLoading = false) }
        } catch (e: RepeaterException) {
            _state.update { it.copy(mapLoading = false, message = e.message, map = MapMode.NONE) }
        }
        refreshSummary()
    }

    fun toggle(id: String) = _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = _state.update { it.copy(selected = it.results.orEmpty().map { r -> r.id }.toSet()) }
    fun selectNone() = _state.update { it.copy(selected = emptySet()) }

    private fun gmrsNeedsAck() = !c.settings.settings.value.gmrsAcknowledged

    fun addToStaging(repeaters: List<Repeater>) {
        if (gmrsNeedsAck() && repeaters.any { it.service == Service.GMRS }) {
            _state.update { it.copy(pendingGmrs = PendingGmrs.Repeaters(repeaters)) }
            return
        }
        viewModelScope.launch {
            val target = c.stagingTarget()
            val r = c.staging.update { list -> c.buildStaging.addRepeaters(list, repeaters, target).let { it.staging to it } }
            val parts = listOfNotNull(
                "Added ${r.added} to staging.",
                r.duplicates.takeIf { it > 0 }?.let { "$it already staged." },
                r.overflow.takeIf { it > 0 }?.let { "$it did not fit (${target.capacity} channel limit)." },
            )
            _state.update { it.copy(selected = emptySet(), message = parts.joinToString(" ")) }
        }
    }

    fun addManual(channel: Channel) {
        if (gmrsNeedsAck() && Gmrs.isGmrs(channel.rxFreqHz)) {
            _state.update { it.copy(pendingGmrs = PendingGmrs.Manual(channel)) }
            return
        }
        viewModelScope.launch {
            val target = c.stagingTarget()
            val r = c.staging.update { list -> c.buildStaging.add(list, listOf(channel), ChannelOrigin.MANUAL, target).let { it.staging to it } }
            _state.update {
                it.copy(message = when {
                    r.overflow > 0 -> "Staging is full (${target.capacity} channels)."
                    r.duplicates > 0 -> "That channel is already staged."
                    else -> "Added ${channel.name.ifBlank { Fmt.mhz(channel.rxFreqHz) }} to staging."
                })
            }
        }
    }

    /** The user accepted the GMRS warning: remember it and finish the add. */
    fun confirmGmrs() {
        val pending = _state.value.pendingGmrs ?: return
        c.settings.update { it.copy(gmrsAcknowledged = true) }
        _state.update { it.copy(pendingGmrs = null) }
        when (pending) {
            is PendingGmrs.Repeaters -> addToStaging(pending.repeaters)
            is PendingGmrs.Manual -> addManual(pending.channel)
        }
    }

    fun cancelGmrs() = _state.update { it.copy(pendingGmrs = null) }

    fun importCsv(text: String, fileName: String) = viewModelScope.launch {
        try {
            val summary = c.csvProvider.import(text, fileName)
            val warn = if (summary.errors.isNotEmpty()) " ${summary.errors.size} lines skipped." else ""
            _state.update { it.copy(message = "Imported ${summary.entries} entries from $fileName.$warn", results = null) }
            if (c.settings.settings.value.provider != RepeaterSource.CSV) c.settings.update { it.copy(provider = RepeaterSource.CSV) }
            refreshSummary()
            val s = _state.value
            if (s.mode == SearchMode.NEAR && s.fix != null || s.mode == SearchMode.STATE && s.state != null) search()
        } catch (e: RepeaterException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun importFailed(message: String) = _state.update { it.copy(error = message) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun refreshSummary() {
        val p = c.provider(_state.value.provider)
        val summary = p.summary()
        _state.update { it.copy(summary = summary, cachedAt = summary?.fetchedAtMillis ?: p.cachedAtMillis()) }
    }
}
