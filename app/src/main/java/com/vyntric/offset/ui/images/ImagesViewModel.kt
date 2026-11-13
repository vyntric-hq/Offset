/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.ImageSummary
import com.vyntric.offset.domain.ChannelOrigin
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.domain.ImportImage
import com.vyntric.offset.domain.StoredImage
import com.vyntric.offset.domain.WriteRadio
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.RadioImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImagesUiState(
    val loaded: Boolean = false,
    val library: List<ImageSummary> = emptyList(),
    val backups: List<ImageSummary> = emptyList(),
    val message: String? = null,
)

class ImagesViewModel(private val c: AppContainer) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ImagesUiState> = combine(c.images.observeAll(), message) { all, msg ->
        ImagesUiState(true, all.filter { it.kind != ImageKind.BACKUP }, all.filter { it.kind == ImageKind.BACKUP }, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImagesUiState())

    fun import(bytes: ByteArray, fileName: String, onSaved: (Long) -> Unit) = viewModelScope.launch {
        when (val r = c.importImage(bytes, fileName)) {
            is ImportImage.Outcome.Saved -> { message.value = "Imported \"${r.name}\"."; onSaved(r.id) }
            is ImportImage.Outcome.Rejected -> message.value = r.message
        }
    }

    fun delete(id: Long) = viewModelScope.launch { c.images.delete(id); message.value = "Image deleted." }

    fun say(text: String) { message.value = text }
    fun dismissMessage() { message.value = null }
}

data class ImageDetailUiState(
    val image: StoredImage? = null,
    val missing: Boolean = false,
    val connectedIdent: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    /** Why restore is not possible right now, or null when it is. Rule 2 is enforced again in WriteRadio. */
    val restoreBlocker: String?
        get() = when {
            image == null -> "Image not loaded."
            busy -> "Another radio operation is running."
            connectedIdent == null -> "Read the radio on the Radio tab first so Offset can confirm which radio is connected."
            connectedIdent != image.image.identHex ->
                "This image's ident (${image.image.identHex}) does not match the connected radio ($connectedIdent)."
            else -> null
        }
}

class ImageDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ImageDetailUiState> = combine(
        c.images.observe(id), c.radioSession.last, c.operations.state, message,
    ) { img, snap, op, msg ->
        ImageDetailUiState(img, img == null, snap?.image?.identHex, op !is com.vyntric.offset.domain.RadioOperations.State.Idle, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageDetailUiState())

    private suspend fun current(): RadioImage? = c.images.load(id)?.image

    fun rename(name: String) = viewModelScope.launch { c.images.rename(id, name) }
    fun setNotes(notes: String) = viewModelScope.launch { c.images.updateNotes(id, notes) }

    fun setChannel(index: Int, channel: Channel?) = viewModelScope.launch {
        val img = current() ?: return@launch
        c.images.updateImage(id, img.withChannel(index, channel))
    }

    fun firstFreeSlot(): Int? = state.value.image?.image?.channels()?.indexOfFirst { it == null }?.takeIf { it >= 0 }

    fun duplicate(onDone: (Long) -> Unit) = viewModelScope.launch {
        c.images.duplicate(id)?.let { message.value = "Duplicated."; onDone(it) }
    }

    fun delete() = viewModelScope.launch { c.images.delete(id) }

    fun exportImg(): ByteArray? = state.value.image?.let { c.exportImage.img(it.image) }
    fun exportCsv(): String? = state.value.image?.let { c.exportImage.csv(it.image) }

    fun importCsv(text: String) = viewModelScope.launch {
        val img = current() ?: return@launch
        val (updated, errors) = c.importImage.applyCsv(img, text)
        c.images.updateImage(id, updated)
        message.value = "Imported CSV into this image." + if (errors.isNotEmpty()) " ${errors.size} problems: ${errors.first()}" else ""
    }

    fun loadIntoStaging() = viewModelScope.launch {
        val img = current() ?: return@launch
        c.staging.put(c.buildStaging.fromImage(img, ChannelOrigin.IMAGE))
        message.value = "Staging now holds this image's ${img.usedChannelCount} channels."
    }

    /** Rule 6 happened on the confirmation sheet. */
    fun restore() {
        val s = state.value
        val stored = s.image ?: return
        val request = WriteRadio.Request(
            content = WriteRadio.Content.FromImage(stored.image),
            resultName = "${stored.name} (restored)",
            confirmedIdentHex = s.connectedIdent,
        )
        if (!c.operations.write(request)) message.value = "Another radio operation is running."
    }

    fun say(text: String) { message.value = text }
    fun dismissMessage() { message.value = null }
}
