/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.Maidenhead
import com.vyntric.offset.domain.reach.Antenna
import com.vyntric.offset.domain.reach.AntennaKind
import com.vyntric.offset.domain.reach.Catalog
import com.vyntric.offset.domain.reach.Feedline
import com.vyntric.offset.domain.reach.Inventory
import com.vyntric.offset.domain.reach.Radio
import com.vyntric.offset.domain.reach.RadioKind
import com.vyntric.offset.domain.reach.ReachBand
import com.vyntric.offset.domain.reach.Station
import com.vyntric.offset.domain.reach.StationLocation
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SegmentedControl
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import java.util.Locale

/** Saved stations, most recently used first, with the current one ticked. */
@Composable
fun StationsSheet(
    inventory: Inventory,
    currentId: Long?,
    units: Units,
    onPick: (Long) -> Unit,
    onEdit: (Station) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    OffsetSheet(onDismiss = onDismiss, title = "Stations", trailingAction = { BarAction("New", onClick = onNew) }) {
        InsetSection(footer = "Tap a station to model it. Tap Edit to change its radio, antenna, height or location.") {
            inventory.stationsByUse.forEach { st ->
                val setup = inventory.setup(st)
                cell {
                    CellRow(
                        st.name,
                        subtitle = setup?.let { ReachFmt.station(it, units) } ?: "Radio or antenna missing",
                        leading = {
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                if (st.id == currentId) Icon(Icons.Rounded.Check, contentDescription = "Current", tint = OffsetTheme.colors.accent)
                            }
                        },
                        trailing = {
                            Text("Edit", style = OffsetTheme.type.body, color = OffsetTheme.colors.accent,
                                modifier = Modifier.tappable("Edit ${st.name}") { onEdit(st) }.padding(8.dp))
                        },
                        onClickLabel = "Model ${st.name}",
                        onClick = { onPick(st.id) },
                    )
                }
            }
            cell { ButtonRow("New station", onClick = onNew) }
        }
    }
}

private enum class EditorPage { FORM, RADIO, ANTENNA, CUSTOM_RADIO, CUSTOM_ANTENNA }

/**
 * Sets up a station in one sheet: radio and antenna picked from the user's own or the catalog,
 * then power, height, feedline, beam heading and location, each shown only when it applies.
 */
@Composable
fun StationEditorSheet(
    existing: Station?,
    inventory: Inventory,
    units: Units,
    currentPoint: GeoPoint?,
    onSave: (Station, Radio, Antenna) -> Unit,
    onDelete: (Station) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialRadio = existing?.let { e -> inventory.radios.firstOrNull { it.id == e.radioId } }
    val initialAntenna = existing?.let { e -> inventory.antennas.firstOrNull { it.id == e.antennaId } }
    var page by remember { mutableStateOf(EditorPage.FORM) }
    var radio by remember { mutableStateOf(initialRadio) }
    var antenna by remember { mutableStateOf(initialAntenna) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var watts by remember { mutableStateOf(existing?.watts) }
    var heightText by remember { mutableStateOf(existing?.let { ReachFmt.heightInput(it.heightM, units) }.orEmpty()) }
    var feedline by remember { mutableStateOf(existing?.feedline) }
    var feedText by remember { mutableStateOf(existing?.takeIf { it.feedlineM > 0 }?.let { ReachFmt.heightInput(it.feedlineM, units) }.orEmpty()) }
    var bearing by remember { mutableStateOf((existing?.bearingDeg ?: 0.0).toFloat()) }
    var fixed by remember { mutableStateOf(existing?.location as? StationLocation.Fixed) }
    var fixedText by remember { mutableStateOf(fixed?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) }.orEmpty()) }
    var fixedError by remember { mutableStateOf<String?>(null) }

    val title = when (page) {
        EditorPage.FORM -> if (existing == null) "New station" else "Edit station"
        EditorPage.RADIO -> "Radio"
        EditorPage.ANTENNA -> "Antenna"
        EditorPage.CUSTOM_RADIO -> "Other radio"
        EditorPage.CUSTOM_ANTENNA -> "Other antenna"
    }
    OffsetSheet(
        onDismiss = onDismiss,
        title = title,
        leadingAction = { BarAction(if (page == EditorPage.FORM) "Cancel" else "Back") { if (page == EditorPage.FORM) onDismiss() else page = EditorPage.FORM } },
    ) {
        when (page) {
            EditorPage.RADIO -> RadioPicker(inventory, radio, onPick = { r ->
                radio = r
                watts = r.levels.firstOrNull()
                if (heightText.isBlank()) heightText = ReachFmt.heightInput(Catalog.defaultHeightM(r), units)
                if (antenna?.let { a -> r.bands.none { a.covers(it) } } != false) antenna = null
                if (name.isBlank()) name = r.name.substringAfter(' ').ifBlank { r.name }
                page = if (antenna == null) EditorPage.ANTENNA else EditorPage.FORM
            }, onCustom = { page = EditorPage.CUSTOM_RADIO })
            EditorPage.ANTENNA -> AntennaPicker(inventory, radio, antenna, onPick = { antenna = it; page = EditorPage.FORM },
                onCustom = { page = EditorPage.CUSTOM_ANTENNA })
            EditorPage.CUSTOM_RADIO -> CustomRadio { r -> radio = r; watts = r.levels.firstOrNull(); page = EditorPage.ANTENNA }
            EditorPage.CUSTOM_ANTENNA -> CustomAntenna { a -> antenna = a; page = EditorPage.FORM }
            EditorPage.FORM -> {
                InsetSection(header = "Name") {
                    cell { TextFieldRow("Name", name, { name = it.take(40) }, placeholder = "Truck, Home, Go-kit") }
                }
                InsetSection(header = "Equipment") {
                    cell { CellRow("Radio", value = radio?.name ?: "Choose", chevron = true, onClick = { page = EditorPage.RADIO }) }
                    cell { CellRow("Antenna", value = antenna?.name ?: "Choose", chevron = true, enabled = radio != null, onClick = { page = EditorPage.ANTENNA }) }
                }
                val r = radio
                val a = antenna
                if (r != null) {
                    InsetSection(header = "Power", footer = r.bands.joinToString(", ") { b -> "${b.label} up to ${ReachFmt.watts(r.maxWatts[b] ?: 0.0)}" }) {
                        cell {
                            Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                                val levels = r.levels
                                SegmentedControl(levels.map { ReachFmt.watts(it) }, levels.indexOf(watts ?: levels.first()).coerceAtLeast(0), { watts = levels[it] })
                            }
                        }
                    }
                }
                InsetSection(header = "Antenna height above ground") {
                    cell {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Catalog.heightPresets.forEach { (label, m) ->
                                Chip("$label · ${ReachFmt.height(m, units)}", selected = ReachFmt.parseHeight(heightText, units)?.let { kotlin.math.abs(it - m) < 0.2 } == true) {
                                    heightText = ReachFmt.heightInput(m, units)
                                }
                            }
                        }
                    }
                    cell {
                        TextFieldRow("Height (${ReachFmt.heightUnit(units)})", heightText, { heightText = it.take(6) },
                            placeholder = ReachFmt.heightInput(1.5, units), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            error = if (heightText.isNotBlank() && ReachFmt.parseHeight(heightText, units) == null) "Enter a height" else null, labelWidth = 130.dp)
                    }
                }
                if (a != null && a.kind != AntennaKind.HANDHELD_WHIP) {
                    InsetSection(header = "Feedline", footer = "Coax loses signal both ways. Long runs of thin cable cost more than most antenna upgrades gain.") {
                        cell {
                            Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                                val options = listOf<Feedline?>(null) + Feedline.entries
                                SegmentedControl(options.map { it?.label ?: "None" }, options.indexOf(feedline), { feedline = options[it] })
                            }
                        }
                        if (feedline != null) {
                            cell {
                                TextFieldRow("Length (${ReachFmt.heightUnit(units)})", feedText, { feedText = it.take(6) },
                                    placeholder = ReachFmt.heightInput(7.6, units), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), labelWidth = 130.dp,
                                    error = if (ReachFmt.parseHeight(feedText, units) == null) "Enter the cable length" else null)
                            }
                        }
                    }
                }
                if (a != null && a.kind.directional) {
                    InsetSection(header = "Beam heading", footer = "Where the beam points, from true north. The map shows its pattern.") {
                        cell {
                            Column(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                                Text(ReachFmt.bearing(bearing.toDouble()), style = OffsetTheme.type.headline, color = OffsetTheme.colors.label)
                                Slider(
                                    value = bearing, onValueChange = { bearing = (it / 5f).toInt() * 5f }, valueRange = 0f..355f,
                                    colors = SliderDefaults.colors(thumbColor = OffsetTheme.colors.accent, activeTrackColor = OffsetTheme.colors.accent),
                                )
                            }
                        }
                    }
                }
                InsetSection(header = "Location", footer = if (fixed == null) "The station moves with the phone." else "A home station stays here wherever the phone is.") {
                    cell {
                        Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                            SegmentedControl(listOf("Follow GPS", "Fixed spot"), if (fixed == null) 0 else 1, { i ->
                                if (i == 0) fixed = null else if (fixed == null) {
                                    val p = currentPoint
                                    if (p != null) {
                                        fixed = StationLocation.Fixed(p.latitude, p.longitude, "Home")
                                        fixedText = String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude)
                                    } else fixed = StationLocation.Fixed(0.0, 0.0, "Home")
                                }
                            })
                        }
                    }
                    if (fixed != null) {
                        cell {
                            TextFieldRow("Where", fixedText, { t ->
                                fixedText = t
                                val parsed = com.vyntric.offset.domain.reach.PlaceText.parseLine(t)
                                fixedError = if (parsed == null) "Coordinates, a grid square or a map link" else null
                                if (parsed != null) fixed = StationLocation.Fixed(parsed.point.latitude, parsed.point.longitude, "Home")
                            }, placeholder = "38.25, -85.76 or EM78gh", error = fixedError, labelWidth = 80.dp)
                        }
                        if (currentPoint != null) cell {
                            ButtonRow("Use where the station is now") {
                                fixed = StationLocation.Fixed(currentPoint.latitude, currentPoint.longitude, "Home")
                                fixedText = String.format(Locale.US, "%.5f, %.5f", currentPoint.latitude, currentPoint.longitude)
                                fixedError = null
                            }
                        }
                    }
                }
                val height = ReachFmt.parseHeight(heightText, units)
                val feedM = if (feedline == null) 0.0 else ReachFmt.parseHeight(feedText, units)
                val fixedOk = fixed == null || (fixedError == null && (fixed!!.latitude != 0.0 || fixed!!.longitude != 0.0))
                val ready = r != null && a != null && height != null && feedM != null && name.isNotBlank() && fixedOk && r.bands.any { a.covers(it) }
                Column(Modifier.padding(Dimens.gutter)) {
                    PrimaryButton("Save", enabled = ready, onClick = {
                        if (r == null || a == null || height == null || feedM == null) return@PrimaryButton
                        onSave(
                            Station(
                                id = existing?.id ?: 0, name = name.trim(), radioId = r.id, antennaId = a.id,
                                watts = watts ?: r.levels.first(), heightM = height, feedline = feedline, feedlineM = feedM,
                                bearingDeg = if (a.kind.directional) bearing.toDouble() else null,
                                location = fixed ?: StationLocation.Gps, lastUsedAt = existing?.lastUsedAt ?: 0,
                            ),
                            r, a,
                        )
                    })
                }
                if (r != null && a != null && r.bands.none { a.covers(it) }) {
                    SectionFooter("This antenna does not cover any band the radio has.")
                }
                if (existing != null) {
                    InsetSection { cell { ButtonRow("Delete station", destructive = true) { onDelete(existing) } } }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Text(
        text,
        style = OffsetTheme.type.footnote,
        color = if (selected) colors.onAccent else colors.label,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) colors.accent else colors.fill)
            .tappable(text, onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun RadioPicker(inventory: Inventory, current: Radio?, onPick: (Radio) -> Unit, onCustom: () -> Unit) {
    val mine = inventory.radios
    val catalog = Catalog.radios.filter { c -> mine.none { it.catalogKey != null && it.catalogKey == c.catalogKey } }
    if (mine.isNotEmpty()) {
        InsetSection(header = "Your radios") {
            mine.forEach { r -> cell { RadioRow(r, r.id == current?.id) { onPick(r) } } }
        }
    }
    RadioKind.entries.forEach { kind ->
        val list = catalog.filter { it.kind == kind }
        if (list.isNotEmpty()) InsetSection(header = kind.label + "s") {
            list.forEach { r -> cell { RadioRow(r, false) { onPick(r) } } }
        }
    }
    InsetSection(footer = "Not listed? Enter its power per band. Powers are the maker's high power figures.") {
        cell { ButtonRow("Other radio", onClick = onCustom) }
    }
}

@Composable
private fun RadioRow(r: Radio, selected: Boolean, onClick: () -> Unit) {
    CellRow(
        r.name,
        subtitle = r.bands.joinToString(" · ") { "${it.label} ${ReachFmt.watts(r.maxWatts[it] ?: 0.0)}" },
        trailing = { if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OffsetTheme.colors.accent) },
        onClick = onClick,
    )
}

@Composable
private fun AntennaPicker(inventory: Inventory, radio: Radio?, current: Antenna?, onPick: (Antenna) -> Unit, onCustom: () -> Unit) {
    val fits = { a: Antenna -> radio == null || radio.bands.any { a.covers(it) } }
    val mine = inventory.antennas.filter(fits)
    val catalog = (radio?.let { Catalog.antennasFor(it) } ?: Catalog.antennas)
        .filter { c -> mine.none { it.catalogKey != null && it.catalogKey == c.catalogKey } }
    if (mine.isNotEmpty()) {
        InsetSection(header = "Your antennas") {
            mine.forEach { a -> cell { AntennaRow(a, a.id == current?.id) { onPick(a) } } }
        }
    }
    InsetSection(header = if (radio != null) "For the ${radio.name}" else "Antennas",
        footer = "Gains are typical published figures in dBi. Real antennas, especially handheld ones, often do worse.") {
        catalog.forEach { a -> cell { AntennaRow(a, false) { onPick(a) } } }
    }
    InsetSection { cell { ButtonRow("Other antenna", onClick = onCustom) } }
}

@Composable
private fun AntennaRow(a: Antenna, selected: Boolean, onClick: () -> Unit) {
    CellRow(
        a.name,
        subtitle = a.kind.label + " · " + a.gainDbi.entries.sortedBy { it.key.ordinal }
            .joinToString(", ") { (b, g) -> "${b.label} ${String.format(Locale.US, "%+.1f dBi", g)}" },
        trailing = { if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OffsetTheme.colors.accent) },
        onClick = onClick,
    )
}

/** Bands a custom radio or antenna can list. */
private val customBands = listOf(ReachBand.SIX_METER, ReachBand.TWO_METER, ReachBand.ONE_TWENTY_FIVE, ReachBand.SEVENTY_CM, ReachBand.GMRS)

@Composable
private fun CustomRadio(onDone: (Radio) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RadioKind.HANDHELD) }
    val watts = remember { customBands.associateWith { mutableStateOf("") } }
    InsetSection {
        cell { TextFieldRow("Name", name, { name = it.take(40) }, placeholder = "My radio") }
        cell {
            Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                SegmentedControl(RadioKind.entries.map { it.label }, kind.ordinal, { kind = RadioKind.entries[it] })
            }
        }
    }
    InsetSection(header = "High power per band (W)", footer = "Leave a band empty if the radio does not have it.") {
        customBands.forEach { b ->
            cell {
                TextFieldRow(b.label, watts.getValue(b).value, { watts.getValue(b).value = it.take(5) },
                    placeholder = "None", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
    }
    val parsed = customBands.mapNotNull { b -> watts.getValue(b).value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 1500 }?.let { b to it } }.toMap()
    Column(Modifier.padding(Dimens.gutter)) {
        PrimaryButton("Use this radio", enabled = name.isNotBlank() && parsed.isNotEmpty(), onClick = {
            val top = parsed.values.max()
            val levels = listOf(top, top / 4, 1.0).filter { it >= 0.5 }.distinct().sortedDescending()
            onDone(Radio(name = name.trim(), kind = kind, maxWatts = parsed, levels = levels))
        })
    }
}

@Composable
private fun CustomAntenna(onDone: (Antenna) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AntennaKind.VERTICAL) }
    val gains = remember { customBands.associateWith { mutableStateOf("") } }
    InsetSection {
        cell { TextFieldRow("Name", name, { name = it.take(40) }, placeholder = "My antenna") }
    }
    InsetSection(header = "Type") {
        AntennaKind.entries.forEach { k ->
            cell {
                CellRow(k.label, trailing = { if (k == kind) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OffsetTheme.colors.accent) },
                    onClick = { kind = k })
            }
        }
    }
    InsetSection(header = "Gain per band (dBi)", footer = "dBi = dBd + 2.15. Leave a band empty if the antenna does not cover it.") {
        customBands.forEach { b ->
            cell {
                TextFieldRow(b.label, gains.getValue(b).value, { gains.getValue(b).value = it.take(6) },
                    placeholder = "None", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            }
        }
    }
    val parsed = customBands.mapNotNull { b -> gains.getValue(b).value.replace(',', '.').toDoubleOrNull()?.takeIf { it in -20.0..25.0 }?.let { b to it } }.toMap()
    Column(Modifier.padding(Dimens.gutter)) {
        PrimaryButton("Use this antenna", enabled = name.isNotBlank() && parsed.isNotEmpty(), onClick = {
            onDone(Antenna(name = name.trim(), kind = kind, gainDbi = parsed))
        })
    }
}

/** The grid square for a point, for showing alongside coordinates. */
fun gridOf(p: GeoPoint): String = Maidenhead.fromLatLon(p.latitude, p.longitude)
