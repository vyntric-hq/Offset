/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelCodec
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Power
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Squelch

/** Builds and edits the staging list: the working set of channels for the next write. */
class BuildStaging(private val newKey: () -> Long) {

    data class AddResult(val staging: List<StagedChannel>, val added: Int, val duplicates: Int, val overflow: Int)

    /** Every used channel of [image], each kept at its own slot. */
    fun fromImage(image: RadioImage, origin: ChannelOrigin = ChannelOrigin.RADIO): List<StagedChannel> =
        image.channels().withIndex().mapNotNull { (slot, ch) -> ch?.let { StagedChannel(newKey(), it, origin, slot = slot) } }

    /** Adds channels to the lowest free slots, skipping ones already staged; stops when all 128 are used. */
    fun add(current: List<StagedChannel>, channels: List<Channel>, origin: ChannelOrigin, notes: List<String> = emptyList()): AddResult {
        val out = current.toMutableList()
        val used = current.map { it.slot }.toMutableSet()
        var duplicates = 0
        var overflow = 0
        channels.forEachIndexed { i, ch ->
            val free = (0 until RadioImage.CHANNEL_COUNT).firstOrNull { it !in used }
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

    fun addRepeaters(current: List<StagedChannel>, repeaters: List<Repeater>, model: RadioModel): AddResult =
        add(current, repeaters.map { toChannel(it, model) }, ChannelOrigin.REPEATER,
            repeaters.map { r -> listOfNotNull(r.city, r.state).joinToString(", ") + " (${r.source.displayName})" })

    /**
     * Moves the entry at list position [from] to [to] (positions in slot order). The set of used
     * slots does not change: entries are permuted across them, so gaps stay where they are.
     */
    fun move(current: List<StagedChannel>, from: Int, to: Int): List<StagedChannel> {
        val ordered = sorted(current)
        if (from !in ordered.indices || to !in ordered.indices || from == to) return ordered
        val slots = ordered.map { it.slot }
        val moved = ordered.toMutableList().apply { add(to, removeAt(from)) }
        return moved.mapIndexed { i, s -> s.copy(slot = slots[i]) }
    }

    /** Removes an entry; its slot is left empty. */
    fun remove(current: List<StagedChannel>, key: Long): List<StagedChannel> = current.filterNot { it.key == key }

    /** Packs channels into slots 1..n in their current order, for users who want no gaps. */
    fun compact(current: List<StagedChannel>): List<StagedChannel> = sorted(current).mapIndexed { i, s -> s.copy(slot = i) }

    private fun sorted(list: List<StagedChannel>) = list.sortedBy { it.slot }

    fun replace(current: List<StagedChannel>, key: Long, channel: Channel): List<StagedChannel> =
        current.map { if (it.key == key) it.copy(channel = channel) else it }

    /** Channels with validation problems for [model], keyed by staging key. */
    fun problems(current: List<StagedChannel>, model: RadioModel): Map<Long, List<ChannelValidator.Problem>> =
        current.associate { it.key to ChannelValidator.validate(it.channel, model) }.filterValues { it.isNotEmpty() }

    companion object {
        /** Converts a repeater listing into a channel the radio can use. */
        fun toChannel(r: Repeater, model: RadioModel): Channel {
            val input = r.inputHz
            val (duplex, offset) = when {
                input == null || input == r.outputHz -> Duplex.SIMPLEX to 0L
                kotlin.math.abs(input - r.outputHz) > ChannelValidator.MAX_OFFSET_HZ -> Duplex.SPLIT to input
                input > r.outputHz -> Duplex.PLUS to input - r.outputHz
                else -> Duplex.MINUS to r.outputHz - input
            }
            // Only use tone squelch when the repeater's downlink tone is known to match the uplink.
            val rx = if (r.downlinkTone == r.uplinkTone && r.uplinkTone !is Squelch.None) r.downlinkTone else Squelch.None
            val name = ChannelCodec(model).sanitizeName(r.callsign)
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
