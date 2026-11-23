/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.images

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.ui.LocalTimeDisplay
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.ImageSummary
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.CellPosition
import com.vyntric.offset.ui.components.GroupedCell
import com.vyntric.offset.ui.components.LargeTitleScreen
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SectionHeader
import com.vyntric.offset.ui.components.StateMessage
import com.vyntric.offset.ui.components.SwipeActionRow
import com.vyntric.offset.ui.components.Tag
import com.vyntric.offset.ui.components.groupedItems
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImagesScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    val vm: ImagesViewModel = viewModel { ImagesViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<ImageSummary?>(null) }
    // Select mode: tap rows to check them, then delete them together.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var deletingSelected by remember { mutableStateOf(false) }
    val all = s.library + s.backups
    // Images deleted elsewhere drop out of the selection.
    val live = selected.filterTo(HashSet()) { id -> all.any { it.id == id } }
    if (live.size != selected.size) selected = live
    if (selecting && all.isEmpty()) { selecting = false; selected = emptySet() }
    fun endSelecting() { selecting = false; selected = emptySet() }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = displayName(context, uri) ?: "image.img"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null -> vm.say("Could not read that file.", isError = true)
                bytes.size > MAX_IMPORT_BYTES -> vm.say("That file is too large to be a radio image.", isError = true)
                else -> vm.import(bytes, name, onOpen)
            }
        }
    }

    LaunchedEffect(s.message) {
        if (s.message != null) { kotlinx.coroutines.delay(4_000); vm.dismissMessage() }
    }

    BackHandler(enabled = selecting) { endSelecting() }

    LargeTitleScreen(
        title = "Images",
        toast = s.message,
        actions = {
            if (selecting) {
                BarAction("Done") { endSelecting() }
            } else {
                if (all.isNotEmpty()) BarAction("Select") { selecting = true }
                BarAction("Import") { picker.launch(arrayOf("application/octet-stream", "*/*")) }
            }
        },
        bottomBarHeight = if (selecting) 82.dp else 0.dp,
        bottomBar = if (!selecting) null else {
            {
                Box(Modifier.fillMaxWidth().background(OffsetTheme.colors.chrome).padding(horizontal = Dimens.gutter, vertical = 16.dp)) {
                    PrimaryButton(
                        if (selected.isEmpty()) "Delete" else "Delete ${selected.size}",
                        destructive = true, enabled = selected.isNotEmpty(), onClick = { deletingSelected = true },
                    )
                }
            }
        },
    ) {
        if (s.loaded && s.library.isEmpty() && s.backups.isEmpty()) {
            item(key = "empty") {
                GroupedCell(CellPosition.ONLY) {
                    StateMessage(
                        title = "No images yet",
                        message = "Reading a radio saves its factory image here. You can also import a CHIRP .img for a UV-5R, BF-F8HP or DM-32UV.",
                        icon = Icons.Rounded.Inventory2,
                        actionLabel = "Import .img",
                        onAction = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                    )
                }
            }
        }
        if (s.library.isNotEmpty()) {
            item(key = "lib-header") { SectionHeader("Library") }
            groupedItems(s.library, key = { it.id }) { img ->
                if (selecting) ImageRow(img, selected = img.id in selected) { toggle(img.id) }
                else SwipeActionRow("Delete", onAction = { deleting = img }) { ImageRow(img) { onOpen(img.id) } }
            }
            item(key = "lib-footer") { SectionFooter("Keep one image per trip or state, edit it, and restore it before you go.") }
        }
        if (s.backups.isNotEmpty()) {
            item(key = "bk-header") { SectionHeader("Backups") }
            groupedItems(s.backups, key = { it.id }) { img ->
                if (selecting) ImageRow(img, selected = img.id in selected) { toggle(img.id) }
                else SwipeActionRow("Delete", onAction = { deleting = img }) { ImageRow(img) { onOpen(img.id) } }
            }
            item(key = "bk-footer") { SectionFooter("Saved automatically before every write. Restore one if a write goes wrong.") }
        }
    }

    if (deletingSelected) {
        val n = selected.size
        OffsetSheet(onDismiss = { deletingSelected = false }, title = "Delete images", leadingAction = { BarAction("Cancel") { deletingSelected = false } }) {
            SectionFooter(if (n == 1) "1 image will be removed from this phone. This cannot be undone." else "$n images will be removed from this phone. This cannot be undone.")
            Column(Modifier.padding(Dimens.gutter)) {
                PrimaryButton(if (n == 1) "Delete 1 image" else "Delete $n images", destructive = true, onClick = {
                    vm.deleteAll(selected)
                    deletingSelected = false
                    endSelecting()
                })
            }
        }
    }

    deleting?.let { img ->
        OffsetSheet(onDismiss = { deleting = null }, title = "Delete image", leadingAction = { BarAction("Cancel") { deleting = null } }) {
            SectionFooter("\"${img.name}\" will be removed from this phone. This cannot be undone.")
            Column(Modifier.padding(Dimens.gutter)) {
                PrimaryButton("Delete", destructive = true, onClick = { vm.delete(img.id); deleting = null })
            }
        }
    }
}

@Composable
internal fun ImageRow(img: ImageSummary, selected: Boolean? = null, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    val base = Modifier.fillMaxWidth().heightIn(min = 60.dp)
    Row(
        (if (selected == null) base.tappable("Open ${img.name}", onClick)
        else base.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }))
            .padding(start = Dimens.gutter, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.tertiaryLabel,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(img.name, style = OffsetTheme.type.body, color = colors.label, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                if (img.kind == ImageKind.FACTORY) { Spacer(Modifier.width(8.dp)); Tag("Factory") }
            }
            Text(LocalTimeDisplay.current.dateTime(img.createdAtMillis), style = OffsetTheme.type.subhead, color = colors.secondaryLabel, maxLines = 1)
            Text(
                "${img.model}${img.firmware?.let { " ($it)" } ?: ""} · ${img.channelCount}${img.driver?.let { " of ${it.capacity}" } ?: ""} channels",
                style = OffsetTheme.type.footnote, color = colors.secondaryLabel, maxLines = 2,
            )
            if (img.notes.isNotBlank() && img.kind == ImageKind.USER) {
                Text(img.notes, style = OffsetTheme.type.footnote, color = colors.tertiaryLabel, maxLines = 1)
            }
        }
        if (selected == null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(22.dp))
        }
    }
}

internal fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

/** Larger than any supported image (a DM-32UV image is about 300 KB). */
private const val MAX_IMPORT_BYTES = 1024 * 1024
