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
import com.vyntric.offset.protocol.Squelch

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
) {
    companion object {
        fun of(image: RadioImage) = StagingTarget(image.capacity, image.profile, image.driver.fixedSlots, image.driver.digitalModes)
        fun of(driver: RadioDriver) = StagingTarget(driver.capacity, driver.defaultProfile, driver.fixedSlots, driver.digitalModes)
    }
}

/** Builds and edits the staging list: the working set of channels for the next write. */
class BuildStaging(private val newKey: () -> Long) {

    data class AddResult(val staging: List<StagedChannel>, val added: Int, val duplicates: Int, val overflow: Int)

    /** Every used channel of [image], each kept at its own slot. */
    fun fromImage(image: RadioImage, origin: ChannelOrigin = ChannelOrigin.RADIO): List<StagedChannel> =
        image.channels().withIndex().mapNotNull { (slot, ch) -> ch?.let { StagedChannel(newKey(), it, origin, slot = slot) } }

    /** Adds channels to the lowest free slots, skipping ones already staged; stops when every slot is used. */
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
            val free = (0 until target.capacity).firstOrNull { it !in used }
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
     * Radios with [StagingTarget.fixedSlots] never renumber channels, so nothing moves.
     */
    fun move(current: List<StagedChannel>, from: Int, to: Int, target: StagingTarget): List<StagedChannel> {
        val ordered = sorted(current)
        if (target.fixedSlots || from !in ordered.indices || to !in ordered.indices || from == to) return ordered
        val slots = ordered.map { it.slot }
        val moved = ordered.toMutableList().apply { add(to, removeAt(from)) }
        return moved.mapIndexed { i, s -> s.copy(slot = slots[i]) }
    }

    /** Removes an entry; its slot is left empty. */
    fun remove(current: List<StagedChannel>, key: Long): List<StagedChannel> = current.filterNot { it.key == key }

    /** Packs channels into slots 1..n in their current order, for users who want no gaps. */
    fun compact(current: List<StagedChannel>, target: StagingTarget): List<StagedChannel> =
        if (target.fixedSlots) sorted(current) else sorted(current).mapIndexed { i, s -> s.copy(slot = i) }

    private fun sorted(list: List<StagedChannel>) = list.sortedBy { it.slot }

    /** Replaces a channel's settings. Digital channels are kept as the radio has them and never replaced. */
    fun replace(current: List<StagedChannel>, key: Long, channel: Channel): List<StagedChannel> =
        current.map { if (it.key == key && it.channel.digitalMode == null) it.copy(channel = channel) else it }

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
