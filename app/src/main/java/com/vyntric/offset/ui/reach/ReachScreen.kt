/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import com.vyntric.offset.ui.components.CreditLine
import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.ThemeMode
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.reach.FarStation
import com.vyntric.offset.domain.reach.ReachBand
import com.vyntric.offset.domain.reach.ReachTarget
import com.vyntric.offset.propagation.AntennaPattern
import com.vyntric.offset.propagation.Direction
import com.vyntric.offset.propagation.Verdict
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.LocalBottomInset
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SegmentedControl
import com.vyntric.offset.ui.components.ThinProgress
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.map.RepeaterMapController
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RASTER_PX = 512

@Composable
fun ReachScreen(container: AppContainer) {
    val vm: ReachViewModel = viewModel { ReachViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val units = settings.units
    val colors = OffsetTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var darkMap by rememberSaveable { mutableStateOf(colors.isDark) }
    val controller = remember { ReachMapController(context) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.locationGranted() else vm.locationDenied()
    }
    LaunchedEffect(s.needsLocationPermission) {
        if (s.needsLocationPermission) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller.onPinTap = { key -> haptics.select(); vm.open(ReachSheet.Link(key)) }
        controller.onLongPress = { p -> haptics.select(); vm.open(ReachSheet.MapPress(p)) }
        onDispose {
            lifecycle.removeObserver(observer)
            controller.onDestroy()
        }
    }

    val likely = colors.success
    val marginal = colors.warning
    LaunchedEffect(darkMap, likely, marginal) {
        controller.setStyle(
            if (darkMap) RepeaterMapController.STYLE_DARK else RepeaterMapController.STYLE_LIGHT,
            ReachMapController.Palette(likely.toArgb(), marginal.toArgb(), ReachColors.Unlikely.toArgb(), colors.accent.toArgb(), colors.accent.toArgb(), darkMap),
        )
    }

    // Coverage raster: redrawn as radials land, and when anything that turns loss into margin changes.
    val ramp = remember(likely, marginal) { ReachColors.Ramp(likely, marginal) }
    LaunchedEffect(s.gridVersion, s.direction, s.far, s.setup, s.band, ramp) {
        val grid = vm.grid
        val margins = vm.margins()
        if (grid == null || margins == null) {
            controller.setCoverage(null, null)
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.Default) {
            val px = ramp.paint(grid.sample(RASTER_PX, RASTER_PX, margins), RASTER_PX, RASTER_PX)
            Bitmap.createBitmap(px, RASTER_PX, RASTER_PX, Bitmap.Config.ARGB_8888)
        }
        controller.setCoverage(bitmap, grid.bounds())
    }

    // Station pin and beam wedge.
    LaunchedEffect(s.origin, s.setup, s.band, s.originLabel) {
        val setup = s.setup
        val band = s.band
        val beam = if (setup != null && band != null) (setup.rf(band).pattern as? AntennaPattern.Beam)?.let {
            ReachMapController.Beam(it.aimDeg, it.beamwidthDeg, (vm.grid?.radiusM ?: 30_000.0) * 0.6)
        } else null
        controller.setStation(s.origin, setup?.station?.name ?: s.originLabel, beam, vm.grid?.radiusM ?: 30_000.0)
    }

    // Once modelled, frame what the station actually reaches (not the whole modelled square).
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(s.reachM) {
        val reach = s.reachM ?: return@LaunchedEffect
        with(density) { controller.setFramePadding(top = 250.dp.roundToPx(), bottom = 240.dp.roundToPx(), sides = 24.dp.roundToPx()) }
        controller.frame(reach)
    }

    // Pins, coloured by the verdict in the current direction.
    val selectedKey = (s.sheet as? ReachSheet.Link)?.key
    LaunchedEffect(s.targets, s.direction, selectedKey) {
        controller.setPins(
            s.targets.map { t ->
                ReachMapController.Pin(t.key, t.name, t.point, if (t.place != null) "place" else "repeater", t.verdict(s.direction).name.lowercase())
            },
            selectedKey,
        )
    }

    val bottom = LocalBottomInset.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
        AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize().padding(bottom = bottom))

        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.setup != null) {
                ControlCard(s, units, onStations = { vm.open(ReachSheet.Stations) }, onDirection = vm::setDirection, onBand = vm::setBand, onFar = vm::setFar)
            }
            StatusPill(s)
        }

        // Map buttons, right side.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(if (darkMap) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (darkMap) "Light map" else "Dark map") { darkMap = !darkMap }
            MapButton(Icons.Rounded.CenterFocusStrong, "Show the whole area") { controller.recenter() }
            if (s.originPinned) {
                MapButton(Icons.Rounded.GpsFixed, "Model from my location again") { vm.followGps() }
            } else if (s.setup?.station?.location is com.vyntric.offset.domain.reach.StationLocation.Gps) {
                MapButton(Icons.Rounded.MyLocation, "Update my location") { vm.refresh() }
            }
        }

        if (s.inventory != null && s.inventory!!.stations.isEmpty()) {
            Welcome(Modifier.align(Alignment.Center).padding(bottom = bottom)) { vm.open(ReachSheet.EditStation(null)) }
        } else if (s.setup != null) {
            SummaryPanel(
                s, units, settings.providers,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom + 12.dp),
                onTarget = { vm.open(ReachSheet.Link(it.key)) },
                onAddPlace = { vm.open(ReachSheet.AddPlace(s.origin)) },
                onPaste = { vm.open(ReachSheet.PastePlaces) },
            )
        }
    }

    Sheets(vm, s, units)

    s.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2600)
            vm.dismissMessage()
        }
        Box(Modifier.fillMaxSize().padding(bottom = bottom + 150.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                msg, style = OffsetTheme.type.subhead, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6202022)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Sheets(vm: ReachViewModel, s: ReachUiState, units: Units) {
    val inv = s.inventory ?: return
    when (val sheet = s.sheet) {
        null -> Unit
        ReachSheet.Stations -> StationsSheet(inv, s.stationId, units, onPick = vm::selectStation,
            onEdit = { vm.open(ReachSheet.EditStation(it)) }, onNew = { vm.open(ReachSheet.EditStation(null)) }, onDismiss = vm::closeSheet)
        is ReachSheet.EditStation -> StationEditorSheet(sheet.station, inv, units, s.origin, onSave = { st, r, a -> vm.saveStation(st, r, a) },
            onDelete = { vm.deleteStation(it) }, onDismiss = vm::closeSheet)
        is ReachSheet.AddPlace -> PlaceSheet(sheet.point, sheet.place, onSave = { vm.savePlace(it) }, onDismiss = vm::closeSheet)
        ReachSheet.PastePlaces -> PastePlacesSheet(onAdd = { vm.addPlaces(it) }, onDismiss = vm::closeSheet)
        ReachSheet.Assumptions -> AssumptionsSheet(inv.repeaterAssumptions, units, onSave = { vm.saveAssumptions(it) }, onDismiss = vm::closeSheet)
        is ReachSheet.MapPress -> MapPressSheet(sheet.point, onAddPlace = { vm.open(ReachSheet.AddPlace(sheet.point)) },
            onMoveStation = { vm.moveStation(sheet.point) }, onDismiss = vm::closeSheet)
        is ReachSheet.Link -> {
            val t = s.target(sheet.key)
            if (t == null) vm.closeSheet() else LinkSheet(
                t, s.direction, s.setup?.station?.name ?: "You", units,
                onDismiss = vm::closeSheet,
                onAddToStaging = { t.repeater?.let(vm::addToStaging) },
                onEditAssumptions = { vm.open(ReachSheet.Assumptions) },
                onFarChange = { far -> t.place?.let { vm.setPlaceFar(it, far) } },
                onEditPlace = { t.place?.let { vm.open(ReachSheet.AddPlace(null, it)) } },
                onRemovePlace = { t.place?.let(vm::deletePlace) },
            )
        }
    }
}

@Composable
private fun ControlCard(
    s: ReachUiState,
    units: Units,
    onStations: () -> Unit,
    onDirection: (Direction) -> Unit,
    onBand: (ReachBand) -> Unit,
    onFar: (FarStation) -> Unit,
) {
    val colors = OffsetTheme.colors
    val setup = s.setup ?: return
    Column(
        Modifier.fillMaxWidth().widthIn(max = 560.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(colors.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().tappable("Choose station", onStations), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SettingsInputAntenna, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(setup.station.name, style = OffsetTheme.type.headline, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text(ReachFmt.station(setup, units), style = OffsetTheme.type.footnote, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.tertiaryLabel)
        }
        SegmentedControl(listOf("Who hears me", "Who I hear"), s.direction.ordinal, { onDirection(Direction.entries[it]) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.bands.forEach { b -> Pill(b.label, selected = b == s.band) { onBand(b) } }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(1.dp).height(20.dp).background(colors.separator))
            Spacer(Modifier.width(4.dp))
            var open by remember { mutableStateOf(false) }
            Box {
                Pill("To ${s.far.label.lowercase()}s ▾", selected = false) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    FarStation.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text("${f.label}s · ${ReachFmt.height(f.heightM, units)} up, ${ReachFmt.watts(f.watts)}", style = OffsetTheme.type.body) },
                            onClick = { open = false; onFar(f) },
                        )
                    }
                }
            }
        }
        if (s.band?.hasSkipCaveat == true) {
            Text("6 m can also skip far beyond this map through sporadic E openings, which Reach does not model.",
                style = OffsetTheme.type.caption, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Text(
        text,
        style = OffsetTheme.type.footnote,
        color = if (selected) colors.onAccent else colors.label,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) colors.accent else colors.fill)
            .tappable(text, onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** What is happening: locating, terrain, modelling, or a problem. */
@Composable
private fun StatusPill(s: ReachUiState) {
    val colors = OffsetTheme.colors
    val (text, fraction) = when (val p = s.phase) {
        ReachPhase.Locating -> "Finding your location" to null
        is ReachPhase.Terrain -> (if (p.total == 0) "Loading terrain" else "Loading terrain ${p.done} of ${p.total}") to
            (if (p.total > 0) p.done.toFloat() / p.total else null)
        is ReachPhase.Modelling -> "Modelling coverage over the terrain" to p.fraction
        else -> null to null
    }
    val notes = listOfNotNull(
        s.error,
        "Some terrain could not be loaded, so part of the map assumes smooth ground. Connect once to fetch it.".takeIf { s.terrainMissing },
        s.repeatersNote,
    )
    AnimatedVisibility(text != null || notes.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(colors.surface).padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (text != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (s.phase is ReachPhase.Terrain) Icons.Rounded.Terrain else Icons.Rounded.Radar, contentDescription = null,
                        tint = colors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = OffsetTheme.type.footnote, color = colors.label)
                }
                ThinProgress(fraction)
            }
            notes.forEach { Text(it, style = OffsetTheme.type.footnote, color = colors.secondaryLabel) }
        }
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Box(
        Modifier.size(46.dp).shadow(3.dp, RoundedCornerShape(13.dp)).clip(RoundedCornerShape(13.dp)).background(colors.surface)
            .tappable(label, onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
    }
}

/** The bottom panel: legend, counts, place actions, and the ranked list when expanded. */
@Composable
private fun SummaryPanel(
    s: ReachUiState,
    units: Units,
    providers: Set<com.vyntric.offset.domain.RepeaterSource>,
    modifier: Modifier,
    onTarget: (ReachTarget) -> Unit,
    onAddPlace: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = OffsetTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ranked = s.ranked
    val repeaters = ranked.filter { it.repeater != null }
    val places = ranked.filter { it.place != null }
    fun count(list: List<ReachTarget>, v: Verdict) = list.count { it.verdict(s.direction) == v }
    val summary = if (repeaters.isEmpty()) "No repeaters on this band nearby" else buildString {
        append("${count(repeaters, Verdict.LIKELY)} of ${repeaters.size} repeaters likely")
        count(repeaters, Verdict.MARGINAL).takeIf { it > 0 }?.let { append(", $it marginal") }
    }
    val detail = listOfNotNull(
        places.takeIf { it.isNotEmpty() }?.let { p -> "${p.count { it.verdict(s.direction) != Verdict.UNLIKELY }} of ${p.size} places in range" },
        s.reachM?.let { "reaches about ${ReachFmt.distance(it, units)} at best" },
    ).joinToString(" \u00B7 ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    Column(
        modifier.fillMaxWidth().widthIn(max = 560.dp).shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(colors.surface).animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().tappable(if (expanded) "Hide list" else "Show list") { expanded = !expanded }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary, style = OffsetTheme.type.headline, color = colors.label, maxLines = 2)
                    if (detail != null) Text(detail, style = OffsetTheme.type.footnote, color = colors.secondaryLabel, maxLines = 2)
                }
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.tertiaryLabel)
            }
            Legend(s.direction)
        }
        AnimatedVisibility(expanded) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(ranked, key = { it.key }) { t -> TargetRow(t, s.direction, units) { onTarget(t) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelAction(Icons.Rounded.Add, "Add place", Modifier.weight(1f), onAddPlace)
            PanelAction(Icons.Rounded.ContentPaste, "Paste locations", Modifier.weight(1f), onPaste)
        }
        if (repeaters.isNotEmpty()) {
            // Each directory's required credit, wherever its repeaters appear.
            CreditLine(
                Fmt.credits(providers, repeaters.mapNotNull { it.repeater }), OffsetTheme.type.caption,
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun Legend(direction: Direction) {
    val colors = OffsetTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(
                Brush.horizontalGradient(listOf(colors.warning.copy(alpha = 0.15f), colors.warning.copy(alpha = 0.6f), colors.success.copy(alpha = 0.8f)))
            ).semantics { contentDescription = "Legend: green likely, amber marginal, clear unlikely" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(if (direction == Direction.TALK) "Unlikely to hear you" else "Unlikely you hear them", style = OffsetTheme.type.caption, color = colors.secondaryLabel, modifier = Modifier.weight(1f))
            Text("Marginal", style = OffsetTheme.type.caption, color = colors.secondaryLabel, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Likely", style = OffsetTheme.type.caption, color = colors.secondaryLabel, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun TargetRow(t: ReachTarget, direction: Direction, units: Units, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    val v = t.verdict(direction)
    Row(
        Modifier.fillMaxWidth().tappable("Show link to ${t.name}", onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (v == Verdict.UNLIKELY) ReachColors.Unlikely else ReachColors.of(v)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = OffsetTheme.type.body, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.repeater?.let { Text("  ${Fmt.mhz(it.outputHz)}", style = OffsetTheme.type.subhead, color = colors.secondaryLabel) }
            }
            Text(
                listOfNotNull(
                    if (t.place != null) "Place" else t.repeater?.city,
                    "${ReachFmt.distance(t.check.distanceM, units)} ${com.vyntric.offset.domain.Geo.compass(t.check.bearingDeg)}",
                ).joinToString(" · "),
                style = OffsetTheme.type.footnote, color = colors.secondaryLabel, maxLines = 1,
            )
        }
        Text(ReachFmt.margin(t.marginDb(direction)), style = OffsetTheme.type.subhead, color = if (v == Verdict.UNLIKELY) colors.tertiaryLabel else colors.label)
    }
}

@Composable
private fun PanelAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(colors.fill).tappable(label, onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = OffsetTheme.type.subhead, color = colors.label)
    }
}

/** First run: what Reach is, and one button to set up a station. */
@Composable
private fun Welcome(modifier: Modifier, onSetUp: () -> Unit) {
    val colors = OffsetTheme.colors
    Column(
        modifier.padding(24.dp).widthIn(max = 420.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(colors.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Radar, contentDescription = null, tint = colors.accent, modifier = Modifier.size(34.dp))
        }
        Text("See how far you reach", style = OffsetTheme.type.title, color = colors.label, modifier = Modifier.semantics { heading() })
        Text(
            "Save your radio and antenna once. Reach then maps where your signal carries over the real terrain, " +
                "which repeaters you can work, and whether your team is in range.",
            style = OffsetTheme.type.subhead, color = colors.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Set up a station", onClick = onSetUp)
    }
}
