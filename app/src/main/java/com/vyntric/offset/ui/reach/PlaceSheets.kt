/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.reach.FarStation
import com.vyntric.offset.domain.reach.Place
import com.vyntric.offset.domain.reach.PlaceText
import com.vyntric.offset.domain.reach.RepeaterAssumptions
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
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import java.util.Locale

private val placeTypes = listOf(FarStation.HANDHELD, FarStation.MOBILE, FarStation.BASE)

private fun coords(p: GeoPoint) = String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude)

/** What to do with a long-pressed spot on the map. */
@Composable
fun MapPressSheet(point: GeoPoint, onAddPlace: () -> Unit, onMoveStation: () -> Unit, onDismiss: () -> Unit) {
    OffsetSheet(onDismiss = onDismiss, title = "${gridOf(point)} · ${coords(point)}") {
        InsetSection {
            cell { CellRow("Add a place here", subtitle = "A team member, a friend, a hilltop", leading = { Icon(Icons.Rounded.PinDrop, null, tint = OffsetTheme.colors.accent) }, onClick = onAddPlace) }
            cell { CellRow("Model my station from here", subtitle = "Plan from somewhere you are not yet", leading = { Icon(Icons.Rounded.MyLocation, null, tint = OffsetTheme.colors.accent) }, onClick = onMoveStation) }
        }
    }
}

/** Adds or edits one place: a name, where it is, and what kind of station is there. */
@Composable
fun PlaceSheet(point: GeoPoint?, existing: Place?, onSave: (Place) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var where by remember { mutableStateOf((existing?.point ?: point)?.let(::coords).orEmpty()) }
    var far by remember { mutableStateOf(existing?.far ?: FarStation.HANDHELD) }
    val parsed = PlaceText.parseLine(where)
    OffsetSheet(onDismiss = onDismiss, title = if (existing == null) "New place" else "Edit place",
        leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        InsetSection {
            cell { TextFieldRow("Name", name, { name = it.take(40) }, placeholder = "Alex, Base camp") }
            cell {
                TextFieldRow("Where", where, { where = it }, placeholder = "38.25, -85.76 or EM78gh",
                    error = if (where.isNotBlank() && parsed == null) "Coordinates, a grid square or a map link" else null, labelWidth = 80.dp)
            }
        }
        parsed?.let { SectionFooter("Grid ${gridOf(it.point)}") }
        InsetSection(header = "Their station", footer = "Used for whether you can hear them. Whether they hear you needs only your station.") {
            cell {
                Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                    SegmentedControl(placeTypes.map { it.label }, placeTypes.indexOf(far), { far = placeTypes[it] })
                }
            }
        }
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton(if (existing == null) "Add place" else "Save", enabled = parsed != null && (name.isNotBlank() || parsed.name != null), onClick = {
                val p = parsed ?: return@PrimaryButton
                onSave(Place(id = existing?.id ?: 0, name = name.trim().ifBlank { p.name ?: "Place" }, latitude = p.point.latitude,
                    longitude = p.point.longitude, far = far, createdAt = existing?.createdAt ?: 0))
            })
        }
    }
}

/** Adds several places at once from text a team shared: one location per line. */
@Composable
fun PastePlacesSheet(onAdd: (List<Place>) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var far by remember { mutableStateOf(FarStation.HANDHELD) }
    val parsed = PlaceText.parse(text)
    val colors = OffsetTheme.colors
    OffsetSheet(onDismiss = onDismiss, title = "Paste locations", leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        SectionFooter("One per line, as people send them: coordinates, a grid square or a map link, with a name in front. For example “Alex: 38.25, -85.76”.")
        Box(
            Modifier.padding(horizontal = Dimens.gutter).fillMaxWidth().heightIn(min = 120.dp)
                .clip(RoundedCornerShape(Dimens.groupCorner)).background(colors.surface).padding(12.dp),
        ) {
            if (text.isEmpty()) Text("Alex: 38.2527, -85.7585\nSam EM78hf\nJo https://maps.google.com/?q=38.21,-85.81", style = OffsetTheme.type.body, color = colors.tertiaryLabel)
            BasicTextField(
                value = text, onValueChange = { text = it }, textStyle = OffsetTheme.type.body.copy(color = colors.label),
                cursorBrush = SolidColor(colors.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Locations" },
            )
        }
        InsetSection {
            cell {
                CellRow("Paste from clipboard", leading = { Icon(Icons.Rounded.ContentPaste, null, tint = colors.accent) }, onClick = {
                    clipboard.getText()?.text?.let { pasted -> text = if (text.isBlank()) pasted else text.trimEnd() + "\n" + pasted }
                })
            }
        }
        if (parsed.isNotEmpty()) {
            InsetSection(header = "Found ${parsed.size}") {
                parsed.forEachIndexed { i, p ->
                    cell { CellRow(p.name ?: "Place ${i + 1}", subtitle = "${gridOf(p.point)} · ${coords(p.point)}") }
                }
            }
        }
        InsetSection(header = "Their stations") {
            cell {
                Box(Modifier.padding(horizontal = Dimens.gutter, vertical = 10.dp)) {
                    SegmentedControl(placeTypes.map { it.label }, placeTypes.indexOf(far), { far = placeTypes[it] })
                }
            }
        }
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton(if (parsed.isEmpty()) "Add places" else "Add ${parsed.size} ${if (parsed.size == 1) "place" else "places"}", enabled = parsed.isNotEmpty(), onClick = {
                onAdd(parsed.mapIndexed { i, p -> Place(name = p.name ?: "Place ${i + 1}", latitude = p.point.latitude, longitude = p.point.longitude, far = far) })
            })
        }
    }
}

/** The one set of repeater assumptions, with a way back to the defaults. */
@Composable
fun AssumptionsSheet(current: RepeaterAssumptions, units: Units, onSave: (RepeaterAssumptions) -> Unit, onDismiss: () -> Unit) {
    var height by remember { mutableStateOf(ReachFmt.heightInput(current.heightM, units)) }
    var watts by remember { mutableStateOf(current.watts.toString().removeSuffix(".0")) }
    var gain by remember { mutableStateOf(current.gainDbi.toString()) }
    var loss by remember { mutableStateOf(current.lineLossDb.toString()) }
    var sens by remember { mutableStateOf(current.sensitivityDbm.toString().removeSuffix(".0")) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val h = ReachFmt.parseHeight(height, units)
    val next = if (h != null && num(watts) != null && num(gain) != null && num(loss) != null && num(sens) != null) {
        RepeaterAssumptions(h, num(watts)!!.coerceIn(0.1, 1500.0), num(gain)!!.coerceIn(-10.0, 20.0), num(loss)!!.coerceIn(0.0, 20.0), num(sens)!!.coerceIn(-140.0, -90.0))
    } else null
    OffsetSheet(onDismiss = onDismiss, title = "Repeater assumptions", leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        InsetSection(footer = "Used for every repeater on the Reach map. A repeater on a tall tower, or one that hears poorly, changes the answer a lot.") {
            val decimal = KeyboardOptions(keyboardType = KeyboardType.Text)
            cell { TextFieldRow("Height (${ReachFmt.heightUnit(units)})", height, { height = it.take(6) }, keyboardOptions = decimal, labelWidth = 150.dp) }
            cell { TextFieldRow("Power (W)", watts, { watts = it.take(6) }, keyboardOptions = decimal, labelWidth = 150.dp) }
            cell { TextFieldRow("Gain (dBi)", gain, { gain = it.take(6) }, keyboardOptions = decimal, labelWidth = 150.dp) }
            cell { TextFieldRow("Losses (dB)", loss, { loss = it.take(6) }, keyboardOptions = decimal, labelWidth = 150.dp) }
            cell { TextFieldRow("Sensitivity (dBm)", sens, { sens = it.take(7) }, keyboardOptions = decimal, labelWidth = 150.dp) }
        }
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton("Save", enabled = next != null, onClick = { next?.let(onSave) })
        }
        InsetSection { cell { ButtonRow("Back to typical values") { onSave(RepeaterAssumptions()) } } }
    }
}
