/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.images

import com.vyntric.offset.ui.components.Toast
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
    val message: Toast? = null,
)

class ImagesViewModel(private val c: AppContainer) : ViewModel() {
    private val message = MutableStateFlow<Toast?>(null)

    val state: StateFlow<ImagesUiState> = combine(c.images.observeAll(), message) { all, msg ->
        ImagesUiState(true, all.filter { it.kind != ImageKind.BACKUP }, all.filter { it.kind == ImageKind.BACKUP }, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImagesUiState())

    fun import(bytes: ByteArray, fileName: String, onSaved: (Long) -> Unit) = viewModelScope.launch {
        when (val r = c.importImage(bytes, fileName)) {
            is ImportImage.Outcome.Saved -> { message.value = Toast("Imported \"${r.name}\"."); onSaved(r.id) }
            is ImportImage.Outcome.Rejected -> message.value = Toast(r.message, isError = true)
        }
    }

    fun delete(id: Long) = viewModelScope.launch { c.images.delete(id); message.value = Toast("Image deleted.") }

    fun deleteAll(ids: Set<Long>) = viewModelScope.launch {
        runCatching { c.images.deleteAll(ids) }
            .onSuccess { message.value = Toast(if (ids.size == 1) "Image deleted." else "${ids.size} images deleted.") }
            .onFailure { message.value = Toast("Could not delete the images.", isError = true) }
    }

    fun say(text: String, isError: Boolean = false) { message.value = Toast(text, isError) }
    fun dismissMessage() { message.value = null }
}

data class ImageDetailUiState(
    val image: StoredImage? = null,
    val missing: Boolean = false,
    val connectedIdent: String? = null,
    val busy: Boolean = false,
    val message: Toast? = null,
    val connectedDriver: com.vyntric.offset.protocol.RadioDriver? = null,
) {
    /** Why restore is not possible right now, or null when it is. Rule 2 is enforced again in WriteRadio. */
    val restoreBlocker: String?
        get() = when {
            image == null -> "Image not loaded."
            busy -> "Another radio operation is running."
            connectedIdent == null -> "Read the radio on the Radio tab first so Offset can confirm which radio is connected."
            connectedDriver != null && connectedDriver != image.image.driver ->
                "This image is for a ${image.image.driver.displayName}; the connected radio is a ${connectedDriver.displayName}."
            connectedIdent != image.image.identity ->
                "This image's ident (${image.image.identity}) does not match the connected radio ($connectedIdent)."
            else -> null
        }
}

class ImageDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val message = MutableStateFlow<Toast?>(null)

    val state: StateFlow<ImageDetailUiState> = combine(
        c.images.observe(id), c.radioSession.last, c.operations.state, message,
    ) { img, snap, op, msg ->
        ImageDetailUiState(
            img, img == null, snap?.image?.identity, op !is com.vyntric.offset.domain.RadioOperations.State.Idle, msg,
            connectedDriver = snap?.image?.driver,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageDetailUiState())

    private suspend fun current(): RadioImage? = c.images.load(id)?.image

    fun rename(name: String) = viewModelScope.launch { c.images.rename(id, name) }
    fun setNotes(notes: String) = viewModelScope.launch { c.images.updateNotes(id, notes) }

    fun setChannel(index: Int, channel: Channel?) = viewModelScope.launch {
        val img = current() ?: return@launch
        val slots = img.channels().toMutableList()
        if (index !in slots.indices || slots[index]?.digitalMode != null && channel != null) return@launch
        slots[index] = channel
        try {
            c.images.updateImage(id, img.withChannels(slots))
        } catch (e: IllegalArgumentException) {
            message.value = Toast("The channel could not be stored: ${e.message}", isError = true)
        }
    }

    fun firstFreeSlot(): Int? = state.value.image?.image?.channels()?.indexOfFirst { it == null }?.takeIf { it >= 0 }

    fun duplicate(onDone: (Long) -> Unit) = viewModelScope.launch {
        c.images.duplicate(id)?.let { message.value = Toast("Duplicated."); onDone(it) }
    }

    fun delete() = viewModelScope.launch { c.images.delete(id) }

    fun exportImg(): ByteArray? = state.value.image?.let { c.exportImage.img(it.image) }
    fun exportCsv(): String? = state.value.image?.let { c.exportImage.csv(it.image) }

    fun importCsv(text: String) = viewModelScope.launch {
        val img = current() ?: return@launch
        val (updated, errors) = c.importImage.applyCsv(img, text)
        c.images.updateImage(id, updated)
        message.value = Toast("Imported CSV into this image." + if (errors.isNotEmpty()) " ${errors.size} problems: ${errors.first()}" else "", isError = errors.isNotEmpty())
    }

    fun loadIntoStaging() = viewModelScope.launch {
        val stored = c.images.load(id) ?: return@launch
        val img = stored.image
        // Staging is now for this image's radio, on every tab; a read of another type no longer applies.
        c.selectRadioType(img.driver)
        c.staging.put(
            c.buildStaging.fromImage(img, ChannelOrigin.IMAGE), stored.name, baseImageId = id,
            zones = com.vyntric.offset.domain.StagedZones.of(img),
        )
        message.value = Toast("Staging now holds this image's ${img.usedChannelCount} channels.")
    }

    /** Rule 6 happened on the confirmation sheet. */
    fun restore() {
        val s = state.value
        val stored = s.image ?: return
        val request = WriteRadio.Request(
            content = WriteRadio.Content.FromImage(stored.image),
            resultName = "${stored.name} (restored)",
            confirmedIdentity = s.connectedIdent,
            driver = stored.image.driver,
        )
        if (!c.operations.write(request)) message.value = Toast("Another radio operation is running.", isError = true)
    }

    fun say(text: String, isError: Boolean = false) { message.value = Toast(text, isError) }
    fun dismissMessage() { message.value = null }
}
