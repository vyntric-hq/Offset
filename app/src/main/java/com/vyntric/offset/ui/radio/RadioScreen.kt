/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.radio

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.RadioOperations
import com.vyntric.offset.domain.Gmrs
import com.vyntric.offset.domain.SimplexPresets
import com.vyntric.offset.domain.StagedChannel
import com.vyntric.offset.ui.GmrsWarningSheet
import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioDrivers
import com.vyntric.offset.protocol.Uv5rImage
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.channel.ChannelEditorSheet
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
    var pickingType by remember { mutableStateOf(false) }
    val capacity = state.target.capacity
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
            ConnectionSection(state, onPermission = vm::requestPermission, onRead = vm::read, onPickType = { pickingType = true })
        }

        item(key = "banner") {
            AnimatedVisibility(state.message != null || state.error != null, enter = fadeIn(), exit = fadeOut()) {
                Banner(text = state.error ?: state.message.orEmpty(), isError = state.error != null) {
                    if (state.error != null) vm.dismissError() else vm.dismissMessage()
                }
            }
        }

        item(key = "staging-header") {
            SectionHeader("Staging · ${state.staging.size} of $capacity") {
                if (state.snapshot != null) {
                    Text("Load from radio", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.padding(start = 12.dp).tappable { vm.loadFromRadio() })
                }
                if (!fixedSlots && state.staging.withIndex().any { (i, s) -> s.slot != i }) {
                    Text("Close gaps", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.padding(start = 16.dp).tappable("Renumber channels without gaps") { vm.compact() })
                }
                if (state.staging.isNotEmpty()) {
                    Text("Clear", style = OffsetTheme.type.footnote, color = colors.accent,
                        modifier = Modifier.padding(start = 16.dp).tappable { vm.clearStaging() })
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
            list.forEachIndexed { index, staged ->
                item(key = staged.key) {
                    val problems = state.problems[staged.key]
                    GroupedCell(
                        CellPosition.of(index, list.size),
                        Modifier.animateItem().let { if (fixedSlots) it else it.reorderable(reorder, staged.key) },
                        separatorInset = 52.dp,
                    ) {
                        SwipeActionRow("Remove", onAction = { vm.remove(staged.key) }) {
                            StagedRow(
                                index = index,
                                staged = staged,
                                problem = problems?.firstOrNull()?.message,
                                handleModifier = if (fixedSlots) null else Modifier.dragHandle(reorder, staged.key) { haptics.select() },
                                onClick = { if (staged.channel.digitalMode == null) editing = staged else vm.explainDigital(staged.channel) },
                                onMoveUp = if (!fixedSlots && index > 0) ({ vm.move(staged.key, list[index - 1].key) }) else null,
                                onMoveDown = if (!fixedSlots && index < list.lastIndex) ({ vm.move(staged.key, list[index + 1].key) }) else null,
                            )
                        }
                    }
                }
            }
            item(key = "staging-footer") {
                SectionFooter(
                    if (fixedSlots) {
                        "Numbers are the radio's channel numbers. Channels keep their numbers on this radio because its zones " +
                            "and scan lists refer to them; new channels go into free numbers and a zone named Offset. Swipe left to remove."
                    } else {
                        "Numbers are the radio's channel numbers; empty channels stay empty. Drag the handle to reorder, swipe left to remove."
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
            onSave = { vm.replace(staged.key, it); editing = null },
            onDismiss = { editing = null },
            onDelete = { vm.remove(staged.key); editing = null },
            deleteLabel = "Remove from staging",
        )
    }
    if (adding) {
        ChannelEditorSheet(
            title = "New channel",
            initial = null,
            model = state.model,
            onSave = { vm.addManual(it); adding = false },
            onDismiss = { adding = false },
        )
    }
    if (presets) {
        PresetsSheet(onDismiss = { presets = false }) { presets = false; vm.addPreset(it) }
    }
    if (pickingType) {
        RadioTypeSheet(current = state.radioType, onDismiss = { pickingType = false }) { pickingType = false; vm.selectRadioType(it) }
    }
    if (pendingPreset != null) {
        GmrsWarningSheet(onCancel = vm::cancelPreset, onAccept = vm::confirmPresetGmrs)
    }
    if (confirming) {
        WriteConfirmSheet(state, vm.defaultImageName(), onCancel = { confirming = false }) { name ->
            confirming = false
            vm.write(name)
        }
    }
}

@Composable
private fun ConnectionSection(state: RadioUiState, onPermission: () -> Unit, onRead: () -> Unit, onPickType: () -> Unit) {
    val image = state.snapshot?.image
    val running = state.operation as? RadioOperations.State.Running
    InsetSection(
        header = "Connection",
        footer = "Connect the cable through the USB-C OTG adapter. Turn the radio off, seat the plug firmly, " +
            "then turn it on with the volume up. " +
            state.radioType.cableAdvice.let { if (it.isBlank()) "" else "$it " } +
            "Offset reads the whole radio before every write and keeps a backup.",
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
    index: Int,
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
            "${staged.slot + 1}",
            style = OffsetTheme.type.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.width(52.dp).padding(start = Dimens.gutter),
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
private fun WriteConfirmSheet(state: RadioUiState, defaultName: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
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
        InsetSection(header = "Image", footer = "The written radio is saved to Images under this name.") {
            cell {
                TextFieldRow("Name", name, { name = it }, placeholder = "Image name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            }
            cell { DetailRow("Channels", "${state.staging.size} of ${state.target.capacity}") }
            val gmrs = state.staging.count { Gmrs.isGmrs(it.channel.rxFreqHz) }
            if (gmrs > 0) cell { DetailRow("GMRS or FRS channels", "$gmrs, transmit enabled") }
        }
        SectionFooter(
            "Offset reads the radio again, saves a backup, writes only channel memory and the lists that refer to channels, " +
                "then reads back to verify. Settings and calibration are never written. Keep this radio connected and the cable still until it finishes.",
        )
        Column(Modifier.padding(Dimens.gutter)) {
            PrimaryButton("Write ${state.staging.size} channels", onClick = { onConfirm(name) }, enabled = name.isNotBlank())
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

@Composable
private fun PresetsSheet(onDismiss: () -> Unit, onPick: (SimplexPresets.Preset) -> Unit) {
    OffsetSheet(onDismiss = onDismiss, title = "Simplex presets", leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        InsetSection(footer = "Presets are added to the end of staging. Channels already staged are skipped.") {
            SimplexPresets.ALL.forEach { p ->
                cell {
                    CellRow(p.title, subtitle = "${p.channels.size} channels. ${p.description}", chevron = true,
                        onClickLabel = "Add ${p.title}") { onPick(p) }
                }
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
