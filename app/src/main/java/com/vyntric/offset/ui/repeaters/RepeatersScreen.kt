/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.repeaters

import androidx.compose.ui.text.style.TextOverflow
import com.vyntric.offset.ui.components.SourceBadge
import com.vyntric.offset.ui.components.CreditLine
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.BandFilter
import com.vyntric.offset.data.Settings
import com.vyntric.offset.domain.Geo
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.GmrsWarningSheet
import com.vyntric.offset.ui.map.RepeaterMapScreen
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.ToggleRow
import com.vyntric.offset.ui.channel.ChannelEditorSheet
import com.vyntric.offset.ui.channel.ChoiceRow
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ActionOutcome
import com.vyntric.offset.ui.components.ActionRow
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.DetailRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.LargeTitleScreen
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SectionHeader
import com.vyntric.offset.ui.components.SegmentedControl
import com.vyntric.offset.ui.components.SegmentedRow
import com.vyntric.offset.ui.components.StateMessage
import com.vyntric.offset.ui.components.groupedItems
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RepeatersScreen(container: AppContainer) {
    val vm: RepeatersViewModel = viewModel { RepeatersViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val colors = OffsetTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var detail by remember { mutableStateOf<Repeater?>(null) }
    var manual by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.locateAndSearch() else vm.locationDenied()
    }
    val locate = {
        if (container.location.hasPermission()) vm.locateAndSearch()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "list.csv"
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) vm.importFailed("Could not read that file.") else vm.importCsv(text, name)
        }
    }

    LaunchedEffect(s.message) {
        if (s.message != null) {
            kotlinx.coroutines.delay(4_000)
            vm.dismissMessage()
        }
    }

    LargeTitleScreen(
        title = "Repeaters",
        actions = {
            if (RepeaterSource.CSV in s.providers) BarAction("Import") { csvPicker.launch(arrayOf("text/*", "application/csv", "*/*")) }
            BarAction("Add") { manual = true }
        },
        bottomBarHeight = if (s.selected.isNotEmpty()) 82.dp else 0.dp,
        toast = s.message,
        bottomBar = if (s.selected.isEmpty()) null else {
            {
                Box(Modifier.fillMaxWidth().background(colors.chrome).padding(horizontal = Dimens.gutter, vertical = 16.dp)) {
                    PrimaryButton("Add ${s.selected.size} to staging", onClick = { haptics.success(); vm.addToStaging(s.selectedRepeaters) })
                }
            }
        },
    ) {
        item(key = "mode") {
            Box(Modifier.padding(horizontal = Dimens.gutter)) {
                SegmentedControl(SearchMode.entries.map { it.label }, s.mode.ordinal, { vm.setMode(SearchMode.entries[it]) })
            }
        }
        item(key = "summary") { Summary(s) }
        item(key = "filters") { Filters(s, vm, locate) }
        item(key = "map-actions") {
            InsetSection {
                cell {
                    ButtonRow("Show results on map", enabled = !s.results.isNullOrEmpty()) { vm.showMap(MapMode.RESULTS) }
                }
                cell { ButtonRow("View all on map") { vm.showMap(MapMode.ALL) } }
            }
        }


        when {
            s.loading -> item(key = "loading") {
                StateMessage(
                    title = "Searching",
                    message = null,
                    loading = true,
                )
            }
            s.error != null -> item(key = "error") {
                StateMessage(title = "Search failed", message = s.error, icon = Icons.Rounded.CloudOff, actionLabel = "Try again", onAction = { vm.search() })
            }
            s.results == null -> item(key = "idle") {
                StateMessage(
                    title = if (s.csvOnlyEmpty) "Import a CHIRP CSV" else "Find repeaters",
                    message = when {
                        s.csvOnlyEmpty -> "Tap Import to load a coordinator or personal list."
                        s.mode == SearchMode.NEAR -> "Uses this phone's GPS. Your position never leaves the phone."
                        s.mode == SearchMode.TEXT -> "Type a callsign, city or frequency, then tap Search."
                        else -> "Pick a state to list its repeaters."
                    },
                    icon = Icons.Rounded.CellTower,
                    actionLabel = when {
                        s.csvOnlyEmpty -> "Import CSV"
                        s.mode == SearchMode.NEAR -> "Search near me"
                        else -> null
                    },
                    onAction = {
                        if (s.csvOnlyEmpty) csvPicker.launch(arrayOf("text/*", "*/*")) else locate()
                    },
                )
            }
            s.results!!.isEmpty() -> item(key = "empty") {
                StateMessage(
                    title = "No repeaters found",
                    message = if (s.mode == SearchMode.NEAR && s.radius != Settings.CLOSEST) "Nothing within ${s.radius} ${Fmt.unitLabel(s.units)}. Try a larger distance or other filters." else "Nothing matches these filters.",
                    icon = Icons.Rounded.SearchOff,
                )
            }
            else -> {
                val results = s.results!!
                item(key = "results-header") {
                    SectionHeader("${results.size} repeaters") {
                        Text(
                            if (s.selected.size == results.size) "Select none" else "Select all",
                            style = OffsetTheme.type.footnote, color = colors.accent,
                            modifier = Modifier.tappable { if (s.selected.size == results.size) vm.selectNone() else vm.selectAll() },
                        )
                    }
                }
                groupedItems(results, key = { it.id }, separatorInset = 56.dp) { r ->
                    RepeaterRow(r, r.id in s.selected, s, onToggle = { haptics.select(); vm.toggle(r.id) }, onOpen = { detail = r })
                }
            }
        }
        item(key = "attribution") { Attribution(s) { vm.search(force = true) } }
    }

    if (s.map != MapMode.NONE) {
        RepeaterMapScreen(
            title = if (s.map == MapMode.ALL) "All repeaters" else "Repeater map",
            repeaters = s.mapRepeaters,
            me = s.center?.pair ?: s.fix?.let { it.latitude to it.longitude },
            meLabel = s.center?.label ?: "You",
            nationwide = s.map == MapMode.ALL,
            loading = s.map == MapMode.ALL && s.mapLoading,
            sources = s.providers,
            onBack = { vm.showMap(MapMode.NONE) },
            onRepeater = { detail = it },
        )
    }
    detail?.let { r ->
        RepeaterDetailSheet(r, s, onDismiss = { detail = null }, onAdd = { vm.addToStaging(listOf(r)); detail = null })
    }
    if (s.pendingGmrs != null) {
        GmrsWarningSheet(onCancel = vm::cancelGmrs, onAccept = vm::confirmGmrs)
    }
    if (manual) {
        ChannelEditorSheet(
            title = "Manual channel",
            initial = null,
            model = container.stagingTarget().profile,
            onSave = { vm.addManual(it); manual = false },
            onDismiss = { manual = false },
        )
    }
}

@Composable
private fun Summary(s: RepeatersUiState) {
    val sum = s.summary ?: return
    val parts = listOfNotNull(
        "${String.format(java.util.Locale.US, "%,d", sum.amateur)} amateur",
        "${String.format(java.util.Locale.US, "%,d", sum.gmrs)} GMRS",
    ).joinToString(" + ")
    val updated = sum.fetchedAtMillis?.let { ", updated ${Fmt.date(it)}" }.orEmpty()
    val where = when (s.providers.singleOrNull()) {
        RepeaterSource.REPEATERBOOK -> " (states loaded so far)"
        RepeaterSource.CSV -> " (imported list)"
        RepeaterSource.HEARHAM -> if (sum.bundled) " included with the app" else ""
        else -> ""
    }
    SectionFooter("$parts$where$updated", Modifier.padding(top = 10.dp))
}

@Composable
private fun Filters(s: RepeatersUiState, vm: RepeatersViewModel, locate: () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val unit = Fmt.unitLabel(s.units)
    val footer = listOfNotNull(
        s.locationError?.takeIf { s.mode == SearchMode.NEAR && s.locationMode == LocationMode.GPS },
        if (s.service != ServiceFilter.AMATEUR && s.providers == setOf(RepeaterSource.HEARHAM))
            "Hearham lists very few GMRS repeaters. Use RepeaterBook or an imported CSV for GMRS." else null,
        if (s.mode == SearchMode.NEAR && s.radius == Settings.CLOSEST) "Closest shows the ${Settings.CLOSEST_COUNT} nearest repeaters." else null,
        if (s.mode == SearchMode.TEXT) "Searches callsign, city, county, state or frequency in the data already on this phone." else null,
    ).joinToString(" ").ifBlank { null }
    InsetSection(header = s.mode.label, footer = footer) {
        when (s.mode) {
            SearchMode.NEAR -> {
                cell {
                    SegmentedRow("Search from", LocationMode.entries.map { it.label }, s.locationMode.ordinal) {
                        vm.setLocationMode(LocationMode.entries[it])
                    }
                }
                when (s.locationMode) {
                    LocationMode.GPS -> {
                        cell {
                            CellRow(
                                "Location",
                                subtitle = s.fix?.let { "${it.provider.uppercase().ifBlank { "Device" }} fix, ${Fmt.dateTime(it.timeMillis)}" },
                                value = when {
                                    s.locating -> "Locating"
                                    s.fix != null -> String.format(java.util.Locale.US, "%.3f, %.3f", s.fix.latitude, s.fix.longitude)
                                    else -> "Tap to locate"
                                },
                                chevron = true,
                                onClick = locate,
                                onClickLabel = "Update location and search",
                            )
                        }
                        cell {
                            ToggleRow("Live tracking", s.liveTracking, vm::setLiveTracking,
                                subtitle = "Keep updating as you move, while this screen is open.")
                        }
                    }
                    LocationMode.GRID -> cell {
                        TextFieldRow("Grid", s.gridText, vm::setGridText, placeholder = "EM78gh", labelWidth = 80.dp,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                            error = s.manualError)
                    }
                    LocationMode.LATLON -> cell {
                        TextFieldRow("Lat, long", s.latLonText, vm::setLatLonText, placeholder = "38.25, -85.76", labelWidth = 96.dp,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                            error = s.manualError)
                    }
                    LocationMode.COUNTY -> {
                        cell {
                            ChoiceRow("State", s.countyState, UsState.entries.toList(), { it?.displayName ?: "Choose" }) { it?.let(vm::setCountyState) }
                        }
                        cell {
                            ChoiceRow("County", s.countyPick, s.countyOptions, { it?.name ?: if (s.countyState == null) "Choose a state first" else "Choose" }) {
                                it?.let(vm::pickCounty)
                            }
                        }
                    }
                }
                s.center?.let { c ->
                    cell { CellRow(if (s.locationMode == LocationMode.GPS) "Your grid square" else "Grid square", value = c.grid) }
                }
                cell {
                    SegmentedRow(
                        "Distance",
                        Settings.RADIUS_OPTIONS.map { if (it == Settings.CLOSEST) "Closest" else "$it $unit" },
                        Settings.RADIUS_OPTIONS.indexOf(s.radius),
                    ) { vm.setRadius(Settings.RADIUS_OPTIONS[it]) }
                }
            }
            SearchMode.STATE -> {
                cell {
                    ChoiceRow("State", s.state, UsState.entries.toList(), { it?.displayName ?: "Choose" }) { it?.let(vm::setUsState) }
                }
                if (s.supportsCounty) {
                    cell {
                        ChoiceRow("County", s.county, listOf<String?>(null) + s.counties, { it ?: "All counties" }) { vm.setCounty(it) }
                    }
                }
            }
            SearchMode.TEXT -> cell {
                TextFieldRow("Search", s.text, vm::setText, placeholder = "Callsign, city or 146.94", labelWidth = 80.dp,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }))
            }
        }
        cell {
            SegmentedRow("Service", ServiceFilter.entries.map { it.label }, s.service.ordinal) { vm.setService(ServiceFilter.entries[it]) }
        }
        if (s.service != ServiceFilter.GMRS) {
            cell {
                SegmentedRow("Ham band", BandFilter.entries.map { it.label }, s.band.ordinal) { vm.setBand(BandFilter.entries[it]) }
            }
        }
        cell {
            ToggleRow(
                "Hide mixed-mode repeaters", s.hideMixed, vm::setHideMixed,
                subtitle = "Repeaters that also carry Fusion, DMR, P25, D-STAR or NXDN. They still work in FM.",
            )
        }
        cell {
            ActionRow(
                if (s.results == null && s.error == null) "Search" else "Search again",
                pending = s.loading || s.locating,
                pendingTitle = if (s.locating) "Finding your location..." else "Searching...",
                outcome = when {
                    s.locationError != null && s.mode == SearchMode.NEAR && s.locationMode == LocationMode.GPS ->
                        ActionOutcome("No location", ok = false)
                    s.searchedFor != s.searchInputs -> null
                    s.error != null -> ActionOutcome("Search failed", ok = false)
                    s.results != null -> ActionOutcome(Fmt.repeaterCount(s.results.size), ok = true)
                    else -> null
                },
            ) {
                if (s.mode == SearchMode.NEAR && s.locationMode == LocationMode.GPS && s.fix == null) locate() else vm.search()
            }
        }
    }
}

@Composable
private fun RepeaterRow(r: Repeater, selected: Boolean, s: RepeatersUiState, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colors = OffsetTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .semantics {
                    contentDescription = "Select ${r.callsign}"
                    stateDescription = if (selected) "Selected" else "Not selected"
                }
                .tappable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.tertiaryLabel,
            )
        }
        Column(Modifier.weight(1f).tappable("Show details", onOpen).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The callsign gives way first, so the frequency and source badges always show.
                Text(r.callsign, style = OffsetTheme.type.headline, color = colors.label, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ${Fmt.mhz(r.outputHz)}", style = OffsetTheme.type.mono, color = colors.label)
                // Which chosen sources list it, when more than one is on.
                if (s.providers.size > 1) {
                    r.listings.map { it.source }.filter { it in s.providers }.distinct().forEach { src ->
                        SourceBadge(src, Modifier.padding(start = 6.dp))
                    }
                }
            }
            Text(Fmt.repeaterSummary(r), style = OffsetTheme.type.subhead, color = colors.secondaryLabel, maxLines = 2)
        }
        if (r.distanceKm != null) {
            Column(Modifier.width(76.dp).padding(end = Dimens.gutter), horizontalAlignment = Alignment.End) {
                Text(Fmt.distance(r.distanceKm, s.units), style = OffsetTheme.type.subhead, color = colors.secondaryLabel, textAlign = TextAlign.End)
                r.bearingDeg?.takeIf { r.distanceKm >= 0.1 }?.let { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Navigation, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(14.dp).rotate(b.toFloat()))
                        Text(" ${Geo.compass(b)}", style = OffsetTheme.type.footnote, color = colors.secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun Attribution(s: RepeatersUiState, onRefresh: () -> Unit) {
    val colors = OffsetTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter * 2, vertical = 20.dp)) {
        CreditLine(Fmt.credits(s.providers, s.results.orEmpty()), OffsetTheme.type.footnote)
        val at = s.fetchedAt ?: s.cachedAt
        if (at != null) {
            Row(Modifier.padding(top = 4.dp)) {
                val label = when {
                    s.providers == setOf(RepeaterSource.CSV) -> "Imported"
                    s.summary?.bundled == true && s.fromCache -> "Snapshot included with the app, from"
                    else -> "Fetched"
                }
                // The date wraps; Refresh keeps its width so it never breaks one letter per line.
                Text(
                    "$label ${Fmt.dateTime(at)}",
                    style = OffsetTheme.type.footnote,
                    color = colors.secondaryLabel,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.providers != setOf(RepeaterSource.CSV) && s.results != null) {
                    Text("  Refresh", style = OffsetTheme.type.footnote, color = colors.accent, modifier = Modifier.tappable("Refresh repeater data", onRefresh))
                }
            }
        }
    }
}

/** Explains how well backed the listing is, then credits each directory. */
private fun listingFooter(r: Repeater): String {
    val v = r.verification
    val confidence = when {
        v.tonesDisagree -> "The directories disagree on the access tone. Offset uses ${r.source.displayName}'s; check on the air."
        v.corroborated -> "Listed independently by ${v.independentSources} directories."
        else -> "Listed by one directory. Check on the air before relying on it."
    }
    return (listOf(confidence) + v.sources.map { it.attribution }).joinToString("\n")
}

@Composable
private fun RepeaterDetailSheet(r: Repeater, s: RepeatersUiState, onDismiss: () -> Unit, onAdd: () -> Unit) {
    val uri = LocalUriHandler.current
    OffsetSheet(onDismiss = onDismiss, title = r.callsign, trailingAction = { BarAction("Done", onClick = onDismiss) }) {
        InsetSection(header = "Frequencies") {
            cell { DetailRow("Output", "${Fmt.mhz(r.outputHz)} MHz") }
            cell { DetailRow("Input", r.inputHz?.let { "${Fmt.mhz(it)} MHz" } ?: "Unknown") }
            cell { DetailRow("Service", if (r.service == com.vyntric.offset.domain.Service.GMRS) "GMRS" else "Amateur ${r.band?.label.orEmpty()}") }
            if (r.mixedMode) cell { DetailRow("Modes", "FM plus digital") }
            cell { DetailRow("Offset", r.offsetHz?.let { if (it == 0L) "Simplex" else (if (it > 0) "+" else "-") + Fmt.shift(kotlin.math.abs(it)) } ?: "Unknown") }
        }
        InsetSection(header = "Tones") {
            cell { DetailRow("Tone mode", r.toneMode.chirp.ifEmpty { "None" }) }
            cell { DetailRow("Uplink tone", Fmt.squelch(r.uplinkTone)) }
            cell { DetailRow("Downlink tone", Fmt.squelch(r.downlinkTone)) }
        }
        InsetSection(header = "Location") {
            cell { DetailRow("City", r.city ?: "Unknown") }
            if (r.county != null) cell { DetailRow("County", r.county) }
            cell { DetailRow("State", r.state?.let { UsState.fromAbbr(it)?.displayName ?: it } ?: "Unknown") }
            if (r.latitude != null && r.longitude != null) {
                cell { DetailRow("Coordinates", String.format(java.util.Locale.US, "%.4f, %.4f", r.latitude, r.longitude)) }
            }
            if (r.distanceKm != null) {
                cell { DetailRow("Distance", Fmt.distance(r.distanceKm, s.units) + (r.bearingDeg?.takeIf { r.distanceKm >= 0.1 }?.let { " ${Geo.compass(it)} (${it.toInt()}\u00B0)" } ?: "")) }
            }
        }
        val v = r.verification
        InsetSection(header = "Listed by", footer = listingFooter(r)) {
            r.listings.forEach { l ->
                cell {
                    CellRow(
                        l.source.displayName,
                        subtitle = listOfNotNull(
                            "Copied from ${l.origin.displayName}".takeIf { l.origin != l.source },
                            "Tone ${Fmt.squelch(l.uplinkTone)}".takeIf { v.tonesDisagree },
                            l.lastReportMillis?.let { "Node last reported ${Fmt.dateTime(it)}" },
                        ).joinToString(" · ").ifEmpty { null },
                        chevron = l.detailUrl != null,
                        onClickLabel = "View on ${l.source.displayName}",
                        onClick = l.detailUrl?.let { url -> { uri.openUri(url) } },
                    )
                }
            }
            r.lastUpdated?.let { cell { DetailRow("Last updated", it) } }
        }
        r.notes?.takeIf { it.isNotBlank() }?.let { SectionFooter(it, Modifier.padding(top = 12.dp)) }
        Column(Modifier.padding(Dimens.gutter)) { PrimaryButton("Add to staging", onClick = onAdd) }
    }
}
