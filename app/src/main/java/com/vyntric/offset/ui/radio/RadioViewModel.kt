/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.ChannelOrigin
import com.vyntric.offset.domain.RadioOperations
import com.vyntric.offset.domain.RadioSession
import com.vyntric.offset.domain.SimplexPresets
import com.vyntric.offset.domain.StagedChannel
import com.vyntric.offset.domain.StagingTarget
import com.vyntric.offset.domain.toSlots
import com.vyntric.offset.domain.WriteRadio
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioDrivers
import com.vyntric.offset.protocol.RadioProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class RadioUiState(
    val cable: CableState = CableState.NoCable,
    val snapshot: RadioSession.Snapshot? = null,
    val staging: List<StagedChannel> = emptyList(),
    val problems: Map<Long, List<ChannelValidator.Problem>> = emptyMap(),
    val operation: RadioOperations.State = RadioOperations.State.Idle,
    val message: String? = null,
    val error: String? = null,
    /** The radio type selected for reading. */
    val radioType: RadioDriver = RadioDrivers.default,
) {
    /** The radio staging is built for: the one read, else the selected type. */
    val driver: RadioDriver get() = snapshot?.image?.driver ?: radioType
    val target: StagingTarget get() = snapshot?.image?.let { StagingTarget.of(it) } ?: StagingTarget.of(radioType)
    val model: RadioProfile get() = target.profile
    val busy: Boolean get() = operation is RadioOperations.State.Running
    val cableUsable: Boolean get() = cable is CableState.Ready || cable is CableState.Simulated
    val canWrite: Boolean get() = cableUsable && snapshot != null && staging.isNotEmpty() && problems.isEmpty() && !busy
}

class RadioViewModel(private val c: AppContainer) : ViewModel() {
    private val transient = MutableStateFlow(Pair<String?, String?>(null, null))

    val state: StateFlow<RadioUiState> = combine(
        combine(c.radioLink.cable, c.radioSession.last, c.settings.settings) { cable, snap, s -> Triple(cable, snap, s) },
        c.staging.observe(), c.operations.state, transient,
    ) { (cable, snap, settings), staging, op, (message, error) ->
        val base = RadioUiState(cable, snap, staging, emptyMap(), op, message, error, RadioDrivers.byId(settings.radioType) ?: RadioDrivers.default)
        base.copy(problems = c.buildStaging.problems(staging, base.target, snap?.image))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    /** Selects the radio type to read. A radio read as another type is forgotten. */
    fun selectRadioType(driver: RadioDriver) {
        if (c.settings.settings.value.radioType == driver.id) return
        c.settings.update { it.copy(radioType = driver.id) }
        if (c.radioSession.last.value?.image?.driver != driver) c.radioSession.clear()
    }

    init {
        // Read results arrive here even if the read finished while the screen was away.
        viewModelScope.launch {
            c.operations.result.collect { r ->
                when (r) {
                    is RadioOperations.Result.ReadDone -> {
                        c.operations.consume(r)
                        val wasEmpty = c.staging.update { staged ->
                            if (staged.isEmpty()) c.buildStaging.fromImage(r.read.image) to true else staged to false
                        }
                        val loaded = if (wasEmpty) " Its ${r.read.image.usedChannelCount} channels are now in staging." else ""
                        val factory = r.read.factoryImageName?.let { " First read of this radio: saved \"$it\" to Images." } ?: ""
                        transient.value = "Read ${r.read.image.modelName}.$loaded$factory".trim() to null
                        clearMessageLater()
                    }
                    is RadioOperations.Result.ReadFailed -> {
                        c.operations.consume(r)
                        transient.value = null to r.message
                    }
                    else -> Unit
                }
            }
        }
    }

    fun requestPermission() = c.radioLink.requestPermission()

    fun read() {
        transient.value = null to null
        if (!c.operations.read()) transient.value = null to "Another radio operation is running."
    }

    fun loadFromRadio() = viewModelScope.launch {
        val image = c.radioSession.last.value?.image ?: return@launch
        c.staging.put(c.buildStaging.fromImage(image))
        transient.value = "Staging replaced with the radio's ${image.usedChannelCount} channels." to null
        clearMessageLater()
    }

    fun clearStaging() = viewModelScope.launch { c.staging.put(emptyList()) }

    /** Digital channels are not editable; say why instead of opening the editor. */
    fun explainDigital(channel: Channel) {
        transient.value = "${channel.digitalMode} channels are kept exactly as the radio has them. Offset programs analog channels only." to null
        clearMessageLater()
    }

    fun compact() = viewModelScope.launch { c.staging.update { c.buildStaging.compact(it, state.value.target) to Unit } }

    fun move(fromKey: Long, toKey: Long) = viewModelScope.launch {
        val target = state.value.target
        c.staging.update { list ->
            c.buildStaging.move(list, list.indexOfFirst { it.key == fromKey }, list.indexOfFirst { it.key == toKey }, target) to Unit
        }
    }

    fun remove(key: Long) = viewModelScope.launch { c.staging.update { c.buildStaging.remove(it, key) to Unit } }

    fun replace(key: Long, channel: Channel) = viewModelScope.launch { c.staging.update { c.buildStaging.replace(it, key, channel) to Unit } }

    fun addManual(channel: Channel) = viewModelScope.launch {
        val target = state.value.target
        val r = c.staging.update { list -> c.buildStaging.add(list, listOf(channel), ChannelOrigin.MANUAL, target).let { it.staging to it } }
        transient.value = when {
            r.overflow > 0 -> null to "Staging is full (${target.capacity} channels)."
            r.duplicates > 0 -> null to "That channel is already staged."
            else -> "Added ${channel.name.ifBlank { "channel" }}." to null
        }
        clearMessageLater()
    }

    /** Set while a preset with GMRS channels waits for the one time warning. */
    val pendingPreset = MutableStateFlow<SimplexPresets.Preset?>(null)

    fun addPreset(preset: SimplexPresets.Preset) {
        if (preset.includesGmrs && !c.settings.settings.value.gmrsAcknowledged) {
            pendingPreset.value = preset
            return
        }
        viewModelScope.launch {
            val target = state.value.target
            val channels = preset.channels.map { it.copy(name = target.profile.sanitizeName(it.name), power = target.profile.nearestPower(it.power)) }
            val r = c.staging.update { list -> c.buildStaging.add(list, channels, ChannelOrigin.MANUAL, target).let { it.staging to it } }
            val parts = listOfNotNull(
                "Added ${r.added} ${preset.title} channels.",
                r.duplicates.takeIf { it > 0 }?.let { "$it already staged." },
                r.overflow.takeIf { it > 0 }?.let { "$it did not fit." },
            )
            transient.value = parts.joinToString(" ") to null
            clearMessageLater()
        }
    }

    fun confirmPresetGmrs() {
        val preset = pendingPreset.value ?: return
        c.settings.update { it.copy(gmrsAcknowledged = true) }
        pendingPreset.value = null
        addPreset(preset)
    }

    fun cancelPreset() { pendingPreset.value = null }

    fun defaultImageName(): String = "Written ${LocalDate.now(c.clock)}"

    /** Rule 6 happened on the confirmation sheet; this records which radio it named. */
    fun write(imageName: String) {
        val s = state.value
        val request = WriteRadio.Request(
            content = WriteRadio.Content.Channels(s.staging.toSlots(s.target.capacity)),
            resultName = imageName,
            confirmedIdentity = s.snapshot?.image?.identity,
            driver = s.driver,
        )
        if (!c.operations.write(request)) transient.value = null to "Another radio operation is running."
    }

    private var messageJob: kotlinx.coroutines.Job? = null

    /** Success messages fade after a while; errors stay until dismissed. */
    private fun clearMessageLater() {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            dismissMessage()
        }
    }

    fun dismissMessage() {
        transient.update { null to it.second }
    }

    fun dismissError() {
        transient.update { it.first to null }
    }
}
