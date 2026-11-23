/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Power
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.RadioProfile
import com.vyntric.offset.protocol.RadioZones
import com.vyntric.offset.protocol.Squelch

/**
 * A staged set's zones, on radios whose zones are fixed runs of slots (the RT-950 Pro): the names
 * to write, and the zone new channels go to ([addTo], or the first free slot anywhere when null).
 */
data class StagedZones(val zones: RadioZones, val addTo: Int? = null) {
    val names: List<String> get() = zones.names

    fun renamed(zone: Int, name: String): StagedZones =
        copy(zones = zones.copy(names = zones.names.toMutableList().also { it[zone] = RadioZones.clean(name, zones.nameLength) }))

    companion object {
        fun of(image: RadioImage?): StagedZones? = image?.namedZones?.let { StagedZones(it) }
    }
}

/**
 * The radio a staging list is built for: how many slots it has, what a channel may hold, and
 * whether channels keep their numbers ([fixedSlots], when zones or scan lists refer to them).
 */
data class StagingTarget(
    val capacity: Int,
    val profile: RadioProfile,
    val fixedSlots: Boolean,
    /** Digital modes this radio can hold; a staged digital channel of another mode cannot be written. */
    val digitalModes: Set<String> = emptySet(),
    /** Why channels keep their numbers on this radio ([RadioDriver.fixedSlotsReason]). */
    val fixedSlotsReason: String = "",
    /** The set's zones, when the radio groups slots into named zones. */
    val zones: StagedZones? = null,
) {
    /** Where new channels go: the chosen zone's slots, else every slot. */
    val addSlots: IntRange
        get() = zones?.addTo?.let { zones.zones.slots(it, capacity) } ?: (0 until capacity)

    companion object {
        fun of(image: RadioImage) = StagingTarget(image.capacity, image.profile, image.driver.fixedSlots, image.driver.digitalModes, image.driver.fixedSlotsReason)
        fun of(driver: RadioDriver) = StagingTarget(driver.capacity, driver.defaultProfile, driver.fixedSlots, driver.digitalModes, driver.fixedSlotsReason)
    }
}

/** Builds and edits the staging list: the working set of channels for the next write. */
class BuildStaging(private val newKey: () -> Long) {

    data class AddResult(val staging: List<StagedChannel>, val added: Int, val duplicates: Int, val overflow: Int)

    /** Every used channel of [image], each kept at its own slot. */
    fun fromImage(image: RadioImage, origin: ChannelOrigin = ChannelOrigin.RADIO): List<StagedChannel> =
        image.channels().withIndex().mapNotNull { (slot, ch) -> ch?.let { StagedChannel(newKey(), it, origin, slot = slot) } }

    /**
     * Adds channels to the lowest free slots ([StagingTarget.addSlots]: in the chosen zone, if
     * any), skipping ones already staged; stops when every slot is used.
     */
    fun add(
        current: List<StagedChannel>,
        channels: List<Channel>,
        origin: ChannelOrigin,
        target: StagingTarget,
        notes: List<String> = emptyList(),
    ): AddResult {
        val out = current.toMutableList()
        val used = current.map { it.slot }.toMutableSet()
        var duplicates = 0
        var overflow = 0
        channels.forEachIndexed { i, ch ->
            val free = target.addSlots.firstOrNull { it !in used }
            when {
                out.any { sameRf(it.channel, ch) } -> duplicates++
                free == null -> overflow++
                else -> {
                    out += StagedChannel(newKey(), ch, origin, notes.getOrElse(i) { "" }, slot = free)
                    used += free
                }
            }
        }
        return AddResult(sorted(out), out.size - current.size, duplicates, overflow)
    }

    fun addRepeaters(current: List<StagedChannel>, repeaters: List<Repeater>, target: StagingTarget): AddResult =
        add(current, repeaters.map { toChannel(it, target.profile) }, ChannelOrigin.REPEATER, target,
            repeaters.map { r -> listOfNotNull(r.city, r.state).joinToString(", ") + " (${r.source.displayName})" })

    /**
     * Moves the entry at list position [from] to [to] (positions in slot order). The set of used
     * slots does not change: entries are permuted across them, so gaps stay where they are.
     * Radios with [StagingTarget.fixedSlots] never renumber channels, so nothing moves. With zones,
     * a move onto a channel in another zone moves only the dragged entry, to that zone's first free
     * slot, so no other channel changes zone.
     */
    fun move(current: List<StagedChannel>, from: Int, to: Int, target: StagingTarget): List<StagedChannel> {
        val ordered = sorted(current)
        if (target.fixedSlots || from !in ordered.indices || to !in ordered.indices || from == to) return ordered
        val zones = target.zones?.zones
        if (zones != null && zones.zoneOf(ordered[from].slot) != zones.zoneOf(ordered[to].slot)) {
            return moveToZone(ordered, ordered[from].key, zones.zoneOf(ordered[to].slot), target) ?: ordered
        }
        val slots = ordered.map { it.slot }
        val moved = ordered.toMutableList().apply { add(to, removeAt(from)) }
        return moved.mapIndexed { i, s -> s.copy(slot = slots[i]) }
    }

    /** Removes an entry; its slot is left empty. */
    fun remove(current: List<StagedChannel>, key: Long): List<StagedChannel> = current.filterNot { it.key == key }

    /**
     * Moves the entry [key] to the first free slot of [zone]. Returns null when that zone is full;
     * an entry already in [zone] stays where it is.
     */
    fun moveToZone(current: List<StagedChannel>, key: Long, zone: Int, target: StagingTarget): List<StagedChannel>? {
        val zones = target.zones?.zones ?: return sorted(current)
        val entry = current.firstOrNull { it.key == key } ?: return sorted(current)
        if (target.fixedSlots || zones.zoneOf(entry.slot) == zone) return sorted(current)
        val used = current.map { it.slot }.toSet()
        val free = zones.slots(zone, target.capacity).firstOrNull { it !in used } ?: return null
        return sorted(current.map { if (it.key == key) it.copy(slot = free) else it })
    }

    data class ZoneMove(val staging: List<StagedChannel>, val moved: Int, val didNotFit: Int)

    /**
     * Moves the entries [keys] to [zone], in their current order, each to the zone's first free
     * slot. Entries already in [zone] stay; those that do not fit stay where they are.
     */
    fun moveAllToZone(current: List<StagedChannel>, keys: Set<Long>, zone: Int, target: StagingTarget): ZoneMove {
        val zones = target.zones?.zones
        var list = sorted(current)
        if (zones == null || target.fixedSlots) return ZoneMove(list, 0, 0)
        var moved = 0
        var didNotFit = 0
        for (s in list.filter { it.key in keys && zones.zoneOf(it.slot) != zone }) {
            val next = moveToZone(list, s.key, zone, target)
            if (next == null) didNotFit++ else { list = next; moved++ }
        }
        return ZoneMove(list, moved, didNotFit)
    }

    data class Placement(val staging: List<StagedChannel>, val moved: Int, val didNotFit: Int)

    /**
     * Moves the entries [keys] together, in their current order, to just before [anchor] (or just
     * after it when [after]). Like [move], the used slots stay the same and the entries are
     * permuted across them, so gaps stay where they are. With zones, the entries first move into
     * [anchor]'s zone and only that zone is reordered; those that do not fit stay where they are.
     * Radios with [StagingTarget.fixedSlots] never renumber channels, so nothing moves.
     */
    fun place(current: List<StagedChannel>, keys: Set<Long>, anchor: Long, after: Boolean, target: StagingTarget): Placement {
        val original = sorted(current)
        val anchorEntry = original.firstOrNull { it.key == anchor }
        if (target.fixedSlots || anchorEntry == null || anchor in keys) return Placement(original, 0, 0)
        val order = original.withIndex().associate { (i, s) -> s.key to i }
        val zones = target.zones?.zones
        val zone = zones?.zoneOf(anchorEntry.slot)
        val (list, didNotFit) = if (zone == null) original to 0 else moveAllToZone(original, keys, zone, target).let { it.staging to it.didNotFit }
        val group = if (zones == null) list else list.filter { zones.zoneOf(it.slot) == zone }
        val chosen = group.filter { it.key in keys }.sortedBy { order.getValue(it.key) }
        val rest = group.filterNot { it.key in keys }
        val at = rest.indexOfFirst { it.key == anchor } + if (after) 1 else 0
        val ordered = rest.toMutableList().apply { addAll(at, chosen) }
        val slots = group.map { it.slot }
        val renumbered = ordered.mapIndexed { i, s -> s.key to s.copy(slot = slots[i]) }.toMap()
        return Placement(sorted(list.map { renumbered[it.key] ?: it }), chosen.size, didNotFit)
    }

    /** Removes the entries [keys]; their slots are left empty. */
    fun removeAll(current: List<StagedChannel>, keys: Set<Long>): List<StagedChannel> = current.filterNot { it.key in keys }

    /**
     * Packs channels into slots 1..n in their current order, for users who want no gaps. With
     * zones, each zone is packed from its own first slot and no channel changes zone.
     */
    fun compact(current: List<StagedChannel>, target: StagingTarget): List<StagedChannel> {
        if (target.fixedSlots) return sorted(current)
        val zones = target.zones?.zones ?: return sorted(current).mapIndexed { i, s -> s.copy(slot = i) }
        return sorted(current).groupBy { zones.zoneOf(it.slot) }.flatMap { (zone, entries) ->
            val first = zone * zones.size
            entries.mapIndexed { i, s -> s.copy(slot = first + i) }
        }
    }

    /** True when [compact] would renumber something. */
    fun hasGaps(current: List<StagedChannel>, target: StagingTarget): Boolean =
        !target.fixedSlots && compact(current, target).zip(sorted(current)).any { (a, b) -> a.slot != b.slot }

    private fun sorted(list: List<StagedChannel>) = list.sortedBy { it.slot }

    /**
     * Staged channels saved before 0.6.0 take what that version could not see from [radio]'s
     * channel in their slot: its mode, and its power where 0.5.0 would have shown it as the staged
     * level (nicFW's Very high as High, Very low and Zero as Low). They then stop being
     * [StagedChannel.modeUnknown]. An empty slot leaves the channel as it is.
     */
    fun adoptRadioModes(current: List<StagedChannel>, radio: RadioImage): List<StagedChannel> = current.map { s ->
        if (!s.modeUnknown) return@map s
        val held = radio.channel(s.slot)?.takeIf { s.slot < radio.capacity } ?: return@map s.copy(modeUnknown = false)
        val power = if (legacyPower(held.power) == s.channel.power) held.power else s.channel.power
        s.copy(channel = s.channel.copy(mode = held.mode, power = power), modeUnknown = false)
    }

    /** How Offset 0.5.0 and earlier showed [p]: three levels only. */
    private fun legacyPower(p: Power): Power = when (p) {
        Power.VERY_HIGH, Power.HIGH -> Power.HIGH
        Power.MID -> Power.MID
        Power.LOW, Power.VERY_LOW, Power.ZERO -> Power.LOW
    }

    /** Replaces a channel's settings. Digital channels are kept as the radio has them and never replaced. */
    fun replace(current: List<StagedChannel>, key: Long, channel: Channel): List<StagedChannel> =
        current.map {
            if (it.key != key || it.channel.digitalMode != null) it
            // An edit that leaves an unknown mode as shown still lets the next read settle it.
            else it.copy(channel = channel, modeUnknown = it.modeUnknown && channel.mode == it.channel.mode)
        }

    /**
     * Channels with problems for [target], keyed by staging key: validation problems for analog
     * channels, and digital channels the radio cannot hold or slots beyond its capacity. A channel
     * equal to what [radio] (the image last read) holds in that slot is not checked, matching
     * WriteRadio, which writes such channels back byte for byte.
     */
    fun problems(current: List<StagedChannel>, target: StagingTarget, radio: RadioImage? = null): Map<Long, List<ChannelValidator.Problem>> =
        current.filterNot { s -> radio != null && s.slot < radio.capacity && radio.channel(s.slot) == s.channel }
            .associate { s ->
            val digital = s.channel.digitalMode
            s.key to buildList {
                if (s.slot >= target.capacity) {
                    add(ChannelValidator.Problem(ChannelValidator.Field.FREQUENCY, "This radio has ${target.capacity} channels; slot ${s.slot + 1} does not exist."))
                }
                when {
                    digital == null -> addAll(ChannelValidator.validate(s.channel, target.profile))
                    digital !in target.digitalModes ->
                        add(ChannelValidator.Problem(ChannelValidator.Field.FREQUENCY, "A $digital channel from another radio; this radio cannot hold it. Remove it."))
                }
            }
        }.filterValues { it.isNotEmpty() }

    companion object {
        /** Converts a repeater listing into a channel the radio can use. */
        fun toChannel(r: Repeater, model: RadioProfile): Channel {
            val input = r.inputHz
            val (duplex, offset) = when {
                input == null || input == r.outputHz -> Duplex.SIMPLEX to 0L
                kotlin.math.abs(input - r.outputHz) > ChannelValidator.MAX_OFFSET_HZ -> Duplex.SPLIT to input
                input > r.outputHz -> Duplex.PLUS to input - r.outputHz
                else -> Duplex.MINUS to r.outputHz - input
            }
            // Only use tone squelch when the repeater's downlink tone is known to match the uplink.
            val rx = if (r.downlinkTone == r.uplinkTone && r.uplinkTone !is Squelch.None) r.downlinkTone else Squelch.None
            val name = model.sanitizeName(r.callsign)
            return Channel(
                rxFreqHz = r.outputHz,
                duplex = duplex,
                offsetHz = offset,
                txSquelch = r.uplinkTone,
                rxSquelch = rx,
                power = Power.HIGH,
                wide = true,
                scanAdd = true,
                name = name,
            )
        }

        private fun sameRf(a: Channel, b: Channel) =
            a.rxFreqHz == b.rxFreqHz && a.txFreqHz == b.txFreqHz && a.txSquelch == b.txSquelch
    }
}
