/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel

/** Where a staged channel came from, shown in the staging list. */
enum class ChannelOrigin(val label: String) { RADIO("Radio"), REPEATER("Repeater"), MANUAL("Manual"), IMAGE("Image") }

/**
 * One entry in the working set for the next write. [key] is stable across reorders; [slot] is
 * the radio memory (0 based, shown as channel slot + 1) the channel is written to. Slots are
 * unique, and gaps stay empty, so reading a radio and writing it back never renumbers channels.
 */
data class StagedChannel(
    val key: Long,
    val channel: Channel,
    val origin: ChannelOrigin,
    val note: String = "",
    val slot: Int = 0,
)

/** The [capacity] radio slots a staging list writes, empty where nothing is staged. */
fun List<StagedChannel>.toSlots(capacity: Int): List<Channel?> {
    val slots = MutableList<Channel?>(capacity) { null }
    for (s in this) if (s.slot in slots.indices) slots[s.slot] = s.channel
    return slots
}
