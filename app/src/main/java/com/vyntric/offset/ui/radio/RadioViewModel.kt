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
import com.vyntric.offset.domain.ImageKind
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
import com.vyntric.offset.domain.StagedZones
import com.vyntric.offset.domain.StoredImage
import com.vyntric.offset.protocol.RadioZones
import com.vyntric.offset.protocol.RadioImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** True once the current read has been saved to Images. */
    val readSaved: Boolean = false,
    /** The staged set's name; empty when unnamed. */
    val setName: String = "",
    /** The library image staging was loaded from, if it is still in Images. */
    val baseImage: StoredImage? = null,
    /** The set's zones, on radios with named zones. */
    val zones: StagedZones? = null,
) {
    /**
     * The image a set is saved over: the last read, else the image staging was loaded from when
     * it is for the selected radio type (loading an image selects its type).
     */
    val setBase: RadioImage? get() = snapshot?.image ?: baseImage?.image?.takeIf { it.driver == radioType }

    /** The radio staging is built for: the one read, else the selected type. */
    val driver: RadioDriver get() = snapshot?.image?.driver ?: radioType

    /** What staging is checked against: the exact model of the read or loaded image, else the selected type. */
    val target: StagingTarget get() = (setBase?.let { StagingTarget.of(it) } ?: StagingTarget.of(radioType)).copy(zones = zones)

    /** Zone names that differ from the radio read, which a write would rename. */
    val renamedZones: Int
        get() {
            val read = snapshot?.image?.namedZones?.names ?: return 0
            return zones?.names?.zip(read)?.count { (a, b) -> a != b } ?: 0
        }
    val model: RadioProfile get() = target.profile
    val busy: Boolean get() = operation is RadioOperations.State.Running
    val cableUsable: Boolean get() = cable is CableState.Ready || cable is CableState.Simulated
    val canWrite: Boolean get() = cableUsable && snapshot != null && staging.isNotEmpty() && problems.isEmpty() && !busy

    /** A set is saved over a read or a loaded image, so it needs one of them. */
    val canSaveSet: Boolean get() = setBase != null && staging.isNotEmpty() && problems.isEmpty() && staging.size <= target.capacity
}

class RadioViewModel(private val c: AppContainer) : ViewModel() {
    /** The image staging was loaded from; null when there is none or it was deleted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseImage = c.staging.observeBaseImageId().flatMapLatest { id -> id?.let { c.images.observe(it) } ?: flowOf(null) }

    private val transient = MutableStateFlow(Pair<String?, String?>(null, null))

    /** The read last saved to Images, so the same read is not saved twice. */
    private val savedRead = MutableStateFlow<RadioSession.Snapshot?>(null)

    val state: StateFlow<RadioUiState> = combine(
        combine(c.radioLink.cable, c.radioSession.last, c.settings.settings) { cable, snap, s -> Triple(cable, snap, s) },
        c.staging.observe(), c.operations.state, combine(transient, savedRead, ::Pair),
        combine(c.staging.observeName(), baseImage, c.staging.observeZones(), ::Triple),
    ) { (cable, snap, settings), staging, op, (messages, saved), (name, baseImage, zones) ->
        val (message, error) = messages
        val base = RadioUiState(
            cable, snap, staging, emptyMap(), op, message, error,
            RadioDrivers.byId(settings.radioType) ?: RadioDrivers.default,
            readSaved = snap != null && snap === saved,
            setName = name,
            baseImage = baseImage,
            zones = zones,
        )
        base.copy(problems = c.buildStaging.problems(staging, base.target, base.setBase))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    /** Selects the radio type to read. A radio read as another type is forgotten. */
    /** Picking another type by hand also drops the loaded image, so it cannot come back later as the base. */
    fun selectRadioType(driver: RadioDriver) {
        if (driver == state.value.radioType) return
        c.selectRadioType(driver)
        viewModelScope.launch { c.staging.update(forgetBaseIf = { true }, zonesFor = { _, _ -> null }) { it to Unit } }
    }

    /**
     * The set's zones after a read: none when the radio shows no zone names; the radio's names when
     * staging was [replaced] by its channels or had no zones; else the staged names, which a
     * write would put on the radio.
     */
    private fun zonesAfterRead(read: RadioImage, staged: StagedZones?, replaced: Boolean): StagedZones? {
        val radio = StagedZones.of(read) ?: return null
        return if (replaced || staged == null) radio.copy(addTo = staged?.addTo) else staged
    }

    fun renameZone(zone: Int, name: String) = viewModelScope.launch {
        c.staging.updateZones { z -> z?.takeIf { zone in it.names.indices && RadioZones.clean(name, it.zones.nameLength).isNotEmpty() }?.renamed(zone, name) ?: z }
    }

    /** Where new channels go: a zone, or null for the first free slot anywhere. */
    fun setAddZone(zone: Int?) = viewModelScope.launch { c.staging.updateZones { it?.copy(addTo = zone) } }

    /** Saves an edit, moving the channel to [zone] when that changed. */
    fun replace(key: Long, channel: Channel, zone: Int?) = viewModelScope.launch {
        val target = state.value.target
        val full = c.staging.update { list ->
            val edited = c.buildStaging.replace(list, key, channel)
            if (zone == null) edited to false
            else c.buildStaging.moveToZone(edited, key, zone, target)?.let { it to false } ?: (edited to true)
        }
        // The edit itself is kept; only the move did not happen.
        if (full && zone != null) transient.value = null to "${zoneTitle(zone)} is full, so the channel stayed in its zone."
    }

    private fun zoneTitle(zone: Int) = state.value.zones?.names?.getOrNull(zone)?.let { "Zone ${zone + 1} ($it)" } ?: "Zone ${zone + 1}"

    init {
        // Read results arrive here even if the read finished while the screen was away.
        viewModelScope.launch {
            c.operations.result.collect { r ->
                when (r) {
                    is RadioOperations.Result.ReadDone -> {
                        c.operations.consume(r)
                        // Channels taken from the radio have no image in Images behind them.
                        val wasEmpty = c.staging.update(forgetBaseIf = { it }, zonesFor = { empty, z -> zonesAfterRead(r.read.image, z, empty) }) { staged ->
                            if (staged.isEmpty()) c.buildStaging.fromImage(r.read.image) to true
                            else c.buildStaging.adoptRadioModes(staged, r.read.image) to false
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
        c.staging.update(forgetBaseIf = { true }, zonesFor = { _, z -> zonesAfterRead(image, z, replaced = true) }) {
            c.buildStaging.fromImage(image) to Unit
        }
        transient.value = "Staging replaced with the radio's ${image.usedChannelCount} channels." to null
        clearMessageLater()
    }

    /** Saves what was last read to Images, to keep or export. */
    fun saveRead() = viewModelScope.launch {
        val snap = c.radioSession.last.value ?: return@launch
        // Claimed before saving, so a second tap while the first save runs does nothing.
        val previous = savedRead.value
        if (previous === snap) return@launch
        savedRead.value = snap
        runCatching { c.images.save(snap.image, "Read ${snap.image.modelName}", ImageKind.USER, notes = "") }
            .onSuccess {
                transient.value = "Saved to Images." to null
                clearMessageLater()
            }
            .onFailure {
                savedRead.compareAndSet(snap, previous)
                transient.value = null to "Could not save the image."
            }
    }

    fun clearStaging() = viewModelScope.launch { c.staging.clear() }

    fun setName(name: String) = viewModelScope.launch { c.staging.setName(name) }

    /** Saves the staged set, laid over the last read or the loaded image, to Images under the set's name. */
    fun saveSet() = viewModelScope.launch {
        val s = state.value
        val base = s.setBase ?: return@launch
        if (!s.canSaveSet) return@launch
        val image = try {
            base.withChannels(s.staging.toSlots(s.target.capacity)).let { img ->
                val names = s.zones?.names
                if (names != null && img.namedZones != null) img.withZoneNames(names) else img
            }
        } catch (e: IllegalArgumentException) {
            transient.value = null to "The set could not be saved: ${e.message}"
            return@launch
        }
        runCatching { c.saveNamedImage(image, s.setName) }
            .onSuccess { r ->
                transient.value = (if (r.existing) "Already in Images as \"${r.name}\"." else "Saved to Images as \"${r.name}\".") to null
                clearMessageLater()
            }
            .onFailure { transient.value = null to "Could not save the set." }
    }

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


    /** Adds a channel, to [zone] when given, else where new channels go. */
    fun addManual(channel: Channel, zone: Int? = null) = viewModelScope.launch {
        val target = state.value.target.let { t -> if (zone != null && t.zones != null) t.copy(zones = t.zones.copy(addTo = zone)) else t }
        val r = c.staging.update { list -> c.buildStaging.add(list, listOf(channel), ChannelOrigin.MANUAL, target).let { it.staging to it } }
        transient.value = when {
            r.overflow > 0 -> null to (target.zones?.addTo?.let { "${zoneTitle(it)} is full." } ?: "Staging is full (${target.capacity} channels).")
            r.duplicates > 0 -> null to "That channel is already staged."
            else -> "Added ${channel.name.ifBlank { "channel" }}." to null
        }
        clearMessageLater()
    }

    /** Set while a preset with GMRS or CB channels waits for its one time warning. */
    val pendingPreset = MutableStateFlow<SimplexPresets.Preset?>(null)

    fun addPreset(preset: SimplexPresets.Preset) {
        val s = c.settings.settings.value
        if ((preset.includesGmrs && !s.gmrsAcknowledged) || (preset.includesCb && !s.cbAcknowledged)) {
            pendingPreset.value = preset
            return
        }
        viewModelScope.launch {
            val target = state.value.target
            val channels = preset.channels.map {
                it.copy(name = target.profile.sanitizeName(it.name), power = target.profile.nearestPower(it.power), mode = target.profile.nearestMode(it.mode))
            }
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

    fun confirmPreset() {
        val preset = pendingPreset.value ?: return
        c.settings.update { it.copy(gmrsAcknowledged = it.gmrsAcknowledged || preset.includesGmrs, cbAcknowledged = it.cbAcknowledged || preset.includesCb) }
        pendingPreset.value = null
        addPreset(preset)
    }

    fun cancelPreset() { pendingPreset.value = null }

    /** Rule 6 happened on the confirmation sheet; this records which radio it named. */
    fun write() {
        val s = state.value
        val request = WriteRadio.Request(
            content = WriteRadio.Content.Channels(s.staging.toSlots(s.target.capacity), zoneNames = s.zones?.names),
            resultName = s.setName,
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
