/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.images

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.protocol.Channel
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
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Editing {
    data class Slot(val index: Int, val channel: Channel?) : Editing
}

@Composable
fun ImageDetailScreen(container: AppContainer, id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val vm: ImageDetailViewModel = viewModel(key = "image-$id") { ImageDetailViewModel(container, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<Editing.Slot?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    fun writeTo(uri: android.net.Uri?, bytes: ByteArray?, label: String) {
        if (uri == null || bytes == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            vm.say(if (ok) "Exported $label." else "Could not write the file.")
        }
    }
    val exportImg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        writeTo(uri, vm.exportImg(), ".img")
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        writeTo(uri, vm.exportCsv()?.toByteArray(), "CSV")
    }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) vm.say("Could not read that file.") else vm.importCsv(text)
        }
    }

    LaunchedEffect(s.message) {
        if (s.message != null) { kotlinx.coroutines.delay(4_000); vm.dismissMessage() }
    }
    LaunchedEffect(s.missing) { if (s.missing && s.image == null && confirmDelete) onBack() }

    val stored = s.image
    LargeTitleScreen(
        title = stored?.name ?: "Image",
        onBack = onBack,
        backLabel = "Images",
        actions = {
            if (stored != null) {
                BarAction("Add", enabled = vm.firstFreeSlot() != null) { vm.firstFreeSlot()?.let { editing = Editing.Slot(it, null) } }
            }
        },
    ) {
        if (stored == null) {
            item(key = "missing") {
                StateMessage(title = if (s.missing) "Image not found" else "Loading", loading = !s.missing, icon = Icons.Rounded.Inventory2)
            }
            return@LargeTitleScreen
        }
        val image = stored.image
        s.message?.let { m -> item(key = "msg") { SectionFooter(m) } }

        item(key = "info") {
            InsetSection(header = "Radio") {
                cell { DetailRow("Model", image.modelName + if ((image as? Uv5rImage)?.detection?.recognized == false) " (assumed)" else "") }
                cell { DetailRow("Firmware", image.firmwareVersion ?: "Not in image") }
                cell { DetailRow("Ident", image.identity) }
                cell { DetailRow("Channels", "${image.usedChannelCount} of ${image.capacity}") }
                cell { DetailRow("Kind", when (stored.kind) { ImageKind.USER -> "Saved"; ImageKind.FACTORY -> "Factory read"; ImageKind.BACKUP -> "Backup" }) }
                cell { DetailRow("Updated", Fmt.dateTime(stored.updatedAtMillis)) }
                cell { CellRow("Notes", subtitle = stored.notes.ifBlank { "Add notes" }, chevron = true, onClick = { notes = true }) }
            }
        }
        item(key = "actions") {
            InsetSection(header = "Actions", footer = s.restoreBlocker?.takeIf { !s.busy }?.let { "Restore: $it" }) {
                cell { ButtonRow("Restore to radio", enabled = s.restoreBlocker == null) { confirmRestore = true } }
                cell { ButtonRow("Load into staging") { vm.loadIntoStaging() } }
                cell { ButtonRow("Rename") { renaming = true } }
                cell { ButtonRow("Duplicate") { vm.duplicate(onOpen) } }
                cell { ButtonRow("Export CHIRP .img") { exportImg.launch(fileName(stored.name, "img")) } }
                cell { ButtonRow("Export CHIRP CSV") { exportCsv.launch(fileName(stored.name, "csv")) } }
                cell { ButtonRow("Import CHIRP CSV into this image") { importCsv.launch(arrayOf("text/*", "*/*")) } }
            }
        }

        item(key = "ch-header") { SectionHeader("Channels") }
        val used = image.channels().withIndex().filter { it.value != null }
        if (used.isEmpty()) {
            item(key = "ch-empty") { GroupedCell(CellPosition.ONLY) { StateMessage(title = "No channels", message = "Tap Add to create one.") } }
        }
        used.forEachIndexed { pos, (index, ch) ->
            item(key = "slot-$index") {
                GroupedCell(CellPosition.of(pos, used.size), Modifier.animateItem(), separatorInset = 56.dp) {
                    SwipeActionRow("Clear", onAction = { vm.setChannel(index, null) }) {
                        SlotRow(index, ch!!) {
                            if (ch.digitalMode == null) editing = Editing.Slot(index, ch)
                            else vm.say("${ch.digitalMode} channels are kept exactly as the radio has them. Offset edits analog channels only.")
                        }
                    }
                }
            }
        }
        item(key = "danger") {
            InsetSection { cell { ButtonRow("Delete image", destructive = true) { confirmDelete = true } } }
        }
    }

    editing?.let { e ->
        ChannelEditorSheet(
            title = "Channel ${e.index + 1}",
            initial = e.channel,
            model = stored?.image?.profile ?: com.vyntric.offset.protocol.RadioModel.UV5R,
            onSave = { vm.setChannel(e.index, it); editing = null },
            onDismiss = { editing = null },
            onDelete = if (e.channel != null) ({ vm.setChannel(e.index, null); editing = null }) else null,
            deleteLabel = "Clear channel",
        )
    }
    if (renaming && stored != null) {
        TextSheet("Rename", stored.name, singleLine = true, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    }
    if (notes && stored != null) {
        TextSheet("Notes", stored.notes, singleLine = false, onDismiss = { notes = false }) { vm.setNotes(it); notes = false }
    }
    if (confirmDelete && stored != null) {
        OffsetSheet(onDismiss = { confirmDelete = false }, title = "Delete image", leadingAction = { BarAction("Cancel") { confirmDelete = false } }) {
            SectionFooter("\"${stored.name}\" will be removed from this phone. This cannot be undone.")
            Column(Modifier.padding(Dimens.gutter)) {
                PrimaryButton("Delete", destructive = true, onClick = { vm.delete(); confirmDelete = false; onBack() })
            }
        }
    }
    if (confirmRestore && stored != null) {
        OffsetSheet(onDismiss = { confirmRestore = false }, title = "Restore to radio", leadingAction = { BarAction("Cancel") { confirmRestore = false } }) {
            InsetSection(header = "Radio") {
                cell { DetailRow("Model", stored.image.modelName) }
                cell { DetailRow("Ident", s.connectedIdent ?: "Unknown") }
            }
            InsetSection(header = "Image") {
                cell { DetailRow("Name", stored.name) }
                cell { DetailRow("Channels", "${stored.image.usedChannelCount} of ${stored.image.capacity}") }
            }
            SectionFooter(
                "Offset reads the radio, saves a backup, writes only this image's channel memory and the lists that refer to channels, then verifies. " +
                    "The radio's own settings and calibration stay as they are.",
            )
            Column(Modifier.padding(Dimens.gutter)) {
                PrimaryButton("Restore ${stored.image.usedChannelCount} channels", onClick = { confirmRestore = false; vm.restore() })
            }
        }
    }
}

@Composable
private fun SlotRow(index: Int, ch: Channel, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).tappable("Edit channel ${index + 1}", onClick), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", style = OffsetTheme.type.footnote, color = colors.secondaryLabel, modifier = Modifier.width(56.dp).padding(start = Dimens.gutter))
        Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 0.dp).padding(end = Dimens.gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.mhz(ch.rxFreqHz), style = OffsetTheme.type.mono, color = colors.label)
                if (ch.name.isNotBlank()) Text("  ${ch.name}", style = OffsetTheme.type.body, color = colors.label)
            }
            Text(Fmt.channelSummary(ch), style = OffsetTheme.type.subhead, color = colors.secondaryLabel, maxLines = 2)
        }
    }
}

@Composable
private fun TextSheet(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OffsetSheet(
        onDismiss = onDismiss, title = title,
        leadingAction = { BarAction("Cancel", onClick = onDismiss) },
        trailingAction = { BarAction("Save", enabled = !singleLine || text.isNotBlank()) { onSave(text) } },
    ) {
        InsetSection {
            cell {
                TextFieldRow(title, text, { text = if (singleLine) it.replace("\n", "") else it }, placeholder = title,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), labelWidth = 80.dp)
            }
        }
    }
}

private fun fileName(name: String, ext: String) = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "offset-image" } + ".$ext"
