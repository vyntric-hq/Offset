/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.radio

import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.RadioOperations
import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.SaveNamedImage
import com.vyntric.offset.domain.SimplexPresets
import com.vyntric.offset.domain.StagedChannel
import com.vyntric.offset.ui.GmrsWarningSheet
import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioDrivers
import com.vyntric.offset.protocol.Uv5rImage
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.channel.ChannelEditorSheet
import com.vyntric.offset.ui.channel.ChoiceRow
import com.vyntric.offset.ui.channel.zoneLabel
import com.vyntric.offset.domain.StagedZones
import com.vyntric.offset.protocol.RadioZones
import com.vyntric.offset.ui.components.rememberChannelNumberWidth
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellPosition
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.DetailRow
import com.vyntric.offset.ui.components.GroupedCell
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.LargeTitleScreen
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SectionHeader
import com.vyntric.offset.ui.components.StateMessage
import com.vyntric.offset.ui.components.SwipeActionRow
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.ThinProgress
import com.vyntric.offset.ui.components.dragHandle
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.components.rememberReorderState
import com.vyntric.offset.ui.components.reorderable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

@Composable
fun RadioScreen(container: AppContainer) {
    val vm: RadioViewModel = viewModel { RadioViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = OffsetTheme.colors
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()
    val fixedSlots = state.target.fixedSlots
    val reorder = rememberReorderState(listState, canReorder = { it is Long && !fixedSlots }) { from, to -> vm.move(from as Long, to as Long) }

    var editing by remember { mutableStateOf<StagedChannel?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var zonesOpen by remember { mutableStateOf(false) }
    // Drag handles only while reordering, so scrolling never moves a channel by accident.
    var reordering by rememberSaveable { mutableStateOf(false) }
    val canReorder = !fixedSlots && state.staging.size > 1
    if (!canReorder && reordering) reordering = false
    var pickingType by remember { mutableStateOf(false) }
    val capacity = state.target.capacity
    // Staging can still hold numbers above this radio's capacity right after a radio type change.
    val largestNumber = maxOf(capacity, state.staging.maxOfOrNull { it.slot + 1 } ?: 0)
    val numberColumn = rememberChannelNumberWidth(largestNumber, minWidth = 52.dp)
    val pendingPreset by vm.pendingPreset.collectAsStateWithLifecycle()

    LargeTitleScreen(
        title = "Radio",
        listState = listState,
        actions = {
            BarAction("Presets", enabled = state.staging.size < capacity) { presets = true }
            BarAction("Add", enabled = state.staging.size < capacity) { adding = true }
        },
        bottomBarHeight = 82.dp,
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(colors.chrome).padding(horizontal = Dimens.gutter, vertical = 16.dp)) {
                PrimaryButton(
                    text = if (state.staging.isEmpty()) "Write to radio" else "Write ${state.staging.size} channels to radio",
                    enabled = state.canWrite,
                    busy = state.operation is RadioOperations.State.Running && (state.operation as RadioOperations.State.Running).kind == RadioOperations.Kind.WRITE,
                    onClick = { confirming = true },
                )
            }
        },
    ) {
        item(key = "connection") {
            ConnectionSection(state, onPermission = vm::requestPermission, onRead = vm::read, onSave = { vm.saveRead() }, onPickType = { pickingType = true })
        }

        item(key = "banner") {
            AnimatedVisibility(state.message != null || state.error != null, enter = fadeIn(), exit = fadeOut()) {
                Banner(text = state.error ?: state.message.orEmpty(), isError = state.error != null) {
                    if (state.error != null) vm.dismissError() else vm.dismissMessage()
                }
            }
        }

        if (state.staging.isNotEmpty() || state.zones != null) {
            item(key = "set") {
                InsetSection(header = "Set", footer = if (state.setBase == null) "Read the radio or load an image from Images to save this set." else null) {
                    cell {
                        CellRow("Name", value = state.setName.ifBlank { SaveNamedImage.UNTITLED }, chevron = true, onClickLabel = "Rename the set") { naming = true }
                    }
                    state.zones?.let { z ->
                        cell { CellRow("Zones", value = "${z.names.size}", chevron = true, onClickLabel = "Rename zones") { zonesOpen = true } }
                        cell {
                            ChoiceRow("New channels go to", z.addTo ?: -1, listOf(-1) + z.names.indices, { if (it < 0) "Any zone" else zoneLabel(it, z.names) }) {
                                vm.setAddZone(it.takeIf { zone -> zone >= 0 })
                            }
                        }
                    }
                    if (state.snapshot == null && state.setBase != null) state.baseImage?.let { cell { DetailRow("Based on", it.name) } }
                    cell { ButtonRow("Save to Images", enabled = state.canSaveSet, onClick = { vm.saveSet() }) }
                }
            }
        }

        item(key = "staging-header") {
            SectionHeader("Staging · ${state.staging.size} of $capacity") {
                if (state.snapshot != null) {
                    Text("Load from radio", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.tappable { vm.loadFromRadio() })
                }
                if (container.buildStaging.hasGaps(state.staging, state.target)) {
                    Text("Close gaps", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.tappable("Renumber channels without gaps") { vm.compact() })
                }
                if (canReorder) {
                    Text(if (reordering) "Done" else "Reorder", style = OffsetTheme.type.footnote, color = colors.accent,
                        fontWeight = if (reordering) FontWeight.SemiBold else null,
                        modifier = Modifier.tappable(if (reordering) "Stop reordering" else "Reorder channels") { reordering = !reordering })
                }
                if (state.staging.isNotEmpty() && !reordering) {
                    Text("Clear", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.tappable { vm.clearStaging() })
                }
            }
        }

        if (state.staging.isEmpty()) {
            item(key = "staging-empty") {
                GroupedCell(CellPosition.ONLY) {
                    StateMessage(
                        title = "No channels staged",
                        message = "Read the radio to start from its channels, or add repeaters from the Repeaters tab.",
                        icon = Icons.Rounded.Radio,
                    )
                }
            }
        } else {
            val list = state.staging
            val zones = state.zones
            // With zones, each zone is its own group under its name, numbered as the radio numbers it.
            val groups = if (zones == null) listOf(null to list) else list.groupBy { zones.zones.zoneOf(it.slot) }.toList()
            groups.forEach { (zone, entries) ->
                if (zone != null && zones != null) {
                    item(key = "zone-$zone") { SectionHeader(zoneLabel(zone, zones.names)) }
                }
                entries.forEachIndexed { position, staged ->
                    val index = list.indexOf(staged)
                    item(key = staged.key) {
                        val problems = state.problems[staged.key]
                        GroupedCell(
                            CellPosition.of(position, entries.size),
                            Modifier.animateItem().let { if (reordering) it.reorderable(reorder, staged.key) else it },
                            separatorInset = numberColumn,
                        ) {
                            SwipeActionRow("Remove", onAction = { vm.remove(staged.key) }) {
                                StagedRow(
                                    number = zones?.let { staged.slot % it.zones.size + 1 } ?: (staged.slot + 1),
                                    numberColumn = numberColumn,
                                    staged = staged,
                                    problem = problems?.firstOrNull()?.message,
                                    handleModifier = if (reordering) Modifier.dragHandle(reorder, staged.key) { haptics.select() } else null,
                                    onClick = { if (staged.channel.digitalMode == null) editing = staged else vm.explainDigital(staged.channel) },
                                    onMoveUp = if (reordering && index > 0) ({ vm.move(staged.key, list[index - 1].key) }) else null,
                                    onMoveDown = if (reordering && index < list.lastIndex) ({ vm.move(staged.key, list[index + 1].key) }) else null,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "staging-footer") {
                SectionFooter(
                    if (fixedSlots) {
                        "Numbers are the radio's channel numbers. Channels keep their numbers on this radio because " +
                            "${state.target.fixedSlotsReason} Swipe left to remove."
                    } else {
                        if (reordering) "Drag the handles to reorder." else "Swipe left to remove."
                    },
                )
            }
        }
    }

    editing?.let { staged ->
        ChannelEditorSheet(
            title = "Edit channel",
            initial = staged.channel,
            model = state.model,
            onSave = { vm.replace(staged.key, it, null); editing = null },
            onDismiss = { editing = null },
            onDelete = { vm.remove(staged.key); editing = null },
            deleteLabel = "Remove from staging",
            zoneNames = state.zones?.names,
            initialZone = state.zones?.zones?.zoneOf(staged.slot) ?: 0,
            onSaveInZone = { ch, zone -> vm.replace(staged.key, ch, zone); editing = null },
        )
    }
    if (adding) {
        ChannelEditorSheet(
            title = "New channel",
            initial = null,
            model = state.model,
            onSave = { vm.addManual(it); adding = false },
            onDismiss = { adding = false },
            zoneNames = state.zones?.names,
            initialZone = state.zones?.let { z ->
                state.target.addSlots.firstOrNull { slot -> state.staging.none { it.slot == slot } }?.let { z.zones.zoneOf(it) }
            } ?: 0,
            onSaveInZone = { ch, zone -> vm.addManual(ch, zone); adding = false },
        )
    }
    if (presets) {
        PresetsSheet(SimplexPresets.ALL.filter { it.fits(state.model) }, onDismiss = { presets = false }) { presets = false; vm.addPreset(it) }
    }
    if (pickingType) {
        RadioTypeSheet(current = state.radioType, onDismiss = { pickingType = false }) { pickingType = false; vm.selectRadioType(it) }
    }
    pendingPreset?.let { p ->
        val acked = container.settings.settings.value
        GmrsWarningSheet(
            onCancel = vm::cancelPreset, onAccept = vm::confirmPreset,
            gmrs = p.includesGmrs && !acked.gmrsAcknowledged, cb = p.includesCb && !acked.cbAcknowledged,
        )
    }
    if (confirming) {
        WriteConfirmSheet(state, onCancel = { confirming = false }) {
            confirming = false
            vm.write()
        }
    }
    if (naming) {
        SetNameSheet(state.setName, onDismiss = { naming = false }) { vm.setName(it); naming = false }
    }
    val zones = state.zones
    if (zonesOpen && zones != null) {
        ZonesSheet(zones, state.staging.groupingBy { zones.zones.zoneOf(it.slot) }.eachCount(), onDismiss = { zonesOpen = false }, onRename = vm::renameZone)
    }
}

/** The zones with their channel counts; tapping one renames it. */
@Composable
private fun ZonesSheet(zones: StagedZones, counts: Map<Int, Int>, onDismiss: () -> Unit, onRename: (Int, String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var text by rememberSaveable(editing) { mutableStateOf(editing?.let { zones.names[it] }.orEmpty()) }
    val length = zones.zones.nameLength
    val clean = RadioZones.clean(text, length)
    OffsetSheet(
        onDismiss = onDismiss,
        title = editing?.let { "Zone ${it + 1}" } ?: "Zones",
        leadingAction = { if (editing == null) BarAction("Done", onClick = onDismiss) else BarAction("Zones") { editing = null } },
        trailingAction = editing?.let { z -> { BarAction("Save", enabled = clean.isNotEmpty()) { onRename(z, clean); editing = null } } },
        page = editing,
    ) {
        BackHandler(enabled = editing != null) { editing = null }
        val z = editing
        if (z == null) {
            InsetSection {
                zones.names.forEachIndexed { zone, _ ->
                    cell {
                        CellRow(zoneLabel(zone, zones.names), value = channelCount(counts[zone] ?: 0), chevron = true, onClickLabel = "Rename zone ${zone + 1}") { editing = zone }
                    }
                }
            }
        } else {
            InsetSection(footer = "Up to $length characters.") {
                cell {
                    TextFieldRow("Name", text, { text = it.filter { c -> c in ' '..'~' }.take(length) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                }
            }
        }
    }
}

@Composable
private fun ConnectionSection(state: RadioUiState, onPermission: () -> Unit, onRead: () -> Unit, onSave: () -> Unit, onPickType: () -> Unit) {
    val image = state.snapshot?.image
    val running = state.operation as? RadioOperations.State.Running
    InsetSection(
        header = "Connection",
        footer = "Seat the cable firmly, then turn the radio on." +
            state.radioType.cableAdvice.let { if (it.isBlank()) "" else " $it" },
    ) {
        cell {
            CellRow(
                "Radio type",
                subtitle = if (state.radioType.experimental) "Experimental: writes are not yet verified on this radio" else null,
                value = state.radioType.shortName,
                chevron = !state.busy,
                enabled = !state.busy,
                onClick = onPickType,
                onClickLabel = "Choose the radio type",
            )
        }
        cell {
            val (value, sub) = when (val c = state.cable) {
                CableState.NoCable -> "Not detected" to null
                is CableState.NeedsPermission -> c.deviceName to null
                is CableState.PermissionDenied -> c.deviceName to null
                is CableState.Ready -> c.deviceName to c.chip
                is CableState.Simulated -> c.description to "Debug: simulated radio"
            }
            CellRow("Cable", subtitle = sub, value = value)
        }
        if (state.cable !is CableState.Simulated) {
            cell {
                when (state.cable) {
                    is CableState.NeedsPermission -> CellRow("USB permission", value = "Needed", chevron = true, onClick = onPermission, onClickLabel = "Grant USB permission")
                    is CableState.PermissionDenied -> CellRow("USB permission", subtitle = "Denied. Tap to ask again.", value = "Denied",
                        titleColor = OffsetTheme.colors.destructive, chevron = true, onClick = onPermission)
                    is CableState.Ready -> CellRow("USB permission", value = "Granted")
                    else -> CellRow("USB permission", value = "No cable", enabled = false)
                }
            }
        }
        cell {
            CellRow(
                "Radio",
                subtitle = image?.let {
                    "Ident ${it.identity}" + if ((it as? Uv5rImage)?.detection?.recognized == false) " · firmware not recognized, treated as UV-5R" else ""
                },
                value = image?.let { listOfNotNull(it.modelName, it.firmwareVersion).joinToString(" · ") } ?: "Not read yet",
            )
        }
        if (running != null) {
            cell { ProgressRow(running) }
        } else {
            cell { ButtonRow(if (image == null) "Read radio" else "Read again", enabled = state.cableUsable && !state.busy, onClick = onRead) }
            if (image != null) {
                cell { ButtonRow(if (state.readSaved) "Read saved to Images" else "Save read to Images", enabled = !state.readSaved, onClick = onSave) }
            }
        }
    }
}

@Composable
private fun ProgressRow(running: RadioOperations.State.Running) {
    val p = running.progress
    val label = when (p?.phase) {
        null, CloneProgress.Phase.IDENTIFYING -> "Contacting radio"
        CloneProgress.Phase.READING -> "Reading radio"
        CloneProgress.Phase.WRITING -> "Writing channels"
        CloneProgress.Phase.VERIFYING -> "Verifying"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
        Row {
            Text(label, style = OffsetTheme.type.body, color = OffsetTheme.colors.label, modifier = Modifier.weight(1f))
            if (p != null && p.bytesTotal > 0) {
                Text(Fmt.bytes(p.bytesDone, p.bytesTotal), style = OffsetTheme.type.footnote, color = OffsetTheme.colors.secondaryLabel)
            }
        }
        ThinProgress(p?.takeIf { it.bytesTotal > 0 }?.fraction ?: 0f, Modifier.padding(top = 10.dp))
    }
}

@Suppress("ModifierParameter") // handleModifier targets the drag handle, not the row
@Composable
private fun StagedRow(
    /** The channel number the radio shows: within its zone on radios with zones. */
    number: Int,
    numberColumn: Dp,
    staged: StagedChannel,
    problem: String?,
    handleModifier: Modifier?,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val colors = OffsetTheme.colors
    val ch = staged.channel
    val actions = listOfNotNull(
        onMoveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
        onMoveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics { customActions = actions },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$number",
            style = OffsetTheme.type.footnote,
            color = colors.secondaryLabel,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(numberColumn).padding(start = Dimens.gutter),
        )
        Column(Modifier.weight(1f).tappable(onClick = onClick).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.mhz(ch.rxFreqHz), style = OffsetTheme.type.mono, color = colors.label)
                if (ch.name.isNotBlank()) {
                    Text("  ${ch.name}", style = OffsetTheme.type.body, color = colors.label)
                }
            }
            Text(
                problem ?: ch.digitalMode?.let { "$it channel, kept as the radio has it" } ?: Fmt.channelSummary(ch),
                style = OffsetTheme.type.subhead,
                color = if (problem != null) colors.destructive else colors.secondaryLabel,
                maxLines = 2,
            )
        }
        if (handleModifier != null) {
            Box(
                handleModifier.size(52.dp).semantics { contentDescription = "Reorder channel ${staged.slot + 1}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = colors.tertiaryLabel)
            }
        } else {
            Spacer(Modifier.width(Dimens.gutter))
        }
    }
}

@Composable
private fun SetNameSheet(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    OffsetSheet(
        onDismiss = onDismiss, title = "Set name",
        leadingAction = { BarAction("Cancel", onClick = onDismiss) },
        trailingAction = { BarAction("Done") { onSave(name) } },
    ) {
        InsetSection {
            cell {
                TextFieldRow("Name", name, { name = it }, placeholder = SaveNamedImage.UNTITLED,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            }
        }
    }
}

@Composable
private fun WriteConfirmSheet(state: RadioUiState, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val image = state.snapshot?.image
    OffsetSheet(onDismiss = onCancel, title = "Write to radio", leadingAction = { BarAction("Cancel", onClick = onCancel) }) {
        InsetSection(header = "Radio") {
            cell { DetailRow("Model", image?.modelName ?: "Unknown") }
            cell { DetailRow("Firmware", image?.firmwareVersion ?: "Unknown") }
            cell { DetailRow("Ident", image?.identity ?: "Unknown") }
        }
        if (state.driver.experimental) {
            InsetSection(header = "Experimental") {
                cell {
                    Text(
                        "Offset's ${state.driver.shortName} support comes from community reverse engineering and has not been " +
                            "verified on a radio with Offset. Only channel memory is written, a backup is saved first and the " +
                            "radio is read back afterwards; if the channels come out wrong, restore the backup from Images.",
                        style = OffsetTheme.type.subhead,
                        color = OffsetTheme.colors.label,
                        modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 12.dp),
                    )
                }
            }
        }
        InsetSection(header = "Image", footer = "Saved to Images under this name.") {
            cell { DetailRow("Name", state.setName.ifBlank { SaveNamedImage.UNTITLED }) }
            cell { DetailRow("Channels", "${state.staging.size} of ${state.target.capacity}") }
            val gmrs = state.staging.count { Gmrs.isGmrs(it.channel.rxFreqHz) }
            if (gmrs > 0) cell { DetailRow("GMRS or FRS channels", "$gmrs, transmit enabled") }
            if (state.renamedZones > 0) cell { DetailRow("Zone names", "${state.renamedZones} renamed") }
        }
        SectionFooter(
            "Offset reads the radio again, saves a backup, writes only channel memory and the lists that refer to channels" +
                (if (state.renamedZones > 0) " and zone names, " else ", ") +
                "then reads back to verify. Settings and calibration are never written. Keep this radio connected and the cable still until it finishes.",
        )
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton("Write ${state.staging.size} channels", onClick = onConfirm)
        }
    }
}

@Composable
private fun RadioTypeSheet(current: RadioDriver, onDismiss: () -> Unit, onPick: (RadioDriver) -> Unit) {
    OffsetSheet(onDismiss = onDismiss, title = "Radio type", leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        InsetSection(footer = "Each radio family speaks its own programming protocol, so Offset needs to know which one is connected.") {
            RadioDrivers.all.forEach { d ->
                cell {
                    CellRow(
                        d.displayName,
                        subtitle = listOfNotNull(
                            "${d.capacity} channels",
                            "experimental".takeIf { d.experimental },
                        ).joinToString(", "),
                        value = if (d == current) "Selected" else null,
                        chevron = d != current,
                        onClickLabel = "Use ${d.displayName}",
                    ) { onPick(d) }
                }
            }
        }
    }
}

/**
 * The presets, then one preset's channels: tapping a preset shows what it holds, and only the
 * Add button stages it.
 */
@Composable
private fun PresetsSheet(available: List<SimplexPresets.Preset>, onDismiss: () -> Unit, onPick: (SimplexPresets.Preset) -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = available.firstOrNull { it.id == openId }
    OffsetSheet(
        onDismiss = onDismiss,
        title = open?.title ?: "Simplex presets",
        leadingAction = {
            if (open == null) BarAction("Cancel", onClick = onDismiss) else BarAction("Presets") { openId = null }
        },
        trailingAction = open?.let { p -> { BarAction("Add") { onPick(p) } } },
        page = openId,
    ) {
        // Inside the sheet: it is its own window, so Back reaches handlers in its content first.
        BackHandler(enabled = open != null) { openId = null }
        if (open == null) {
            InsetSection {
                available.forEach { p ->
                    cell {
                        CellRow(p.title, subtitle = channelCount(p.channels.size), chevron = true, onClickLabel = "Show ${p.title}") { openId = p.id }
                    }
                }
            }
        } else {
            SectionFooter(open.description)
            InsetSection(header = channelCount(open.channels.size), footer = "Added to the end of staging. Channels already staged are skipped.") {
                open.channels.forEach { ch ->
                    cell {
                        CellRow(
                            listOf(Fmt.mhz(ch.rxFreqHz), ch.name).filter { it.isNotBlank() }.joinToString("  "),
                            subtitle = Fmt.channelSummary(ch),
                        )
                    }
                }
            }
            Column(Modifier.padding(Dimens.gutter)) {
                PrimaryButton("Add ${channelCount(open.channels.size)}", onClick = { onPick(open) })
            }
        }
    }
}

@Composable
private fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val colors = OffsetTheme.colors
    Row(
        Modifier
            .padding(start = Dimens.gutter, end = Dimens.gutter, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.groupCorner))
            .background(colors.surface)
            .tappable(onClick = onDismiss)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isError) Icon(Icons.Rounded.ErrorOutline, contentDescription = "Error", tint = colors.destructive, modifier = Modifier.size(22.dp))
        Text(text, style = OffsetTheme.type.subhead, color = colors.label, modifier = Modifier.weight(1f))
        Text("Dismiss", style = OffsetTheme.type.footnote, color = colors.accent)
    }
}

private fun channelCount(n: Int) = if (n == 1) "1 channel" else "$n channels"
