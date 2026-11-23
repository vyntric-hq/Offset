/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

/**
 * Named zones that each hold a fixed run of channel slots, as on the RT-950 Pro: zone `z` (0
 * based) holds slots `z * size` until `(z + 1) * size`. A channel's zone is where it is stored,
 * so moving a channel to another zone moves it to a slot there.
 */
data class RadioZones(
    val names: List<String>,
    /** Slots per zone. */
    val size: Int,
    /** Longest name the radio keeps. */
    val nameLength: Int,
) {
    val count: Int get() = names.size

    fun zoneOf(slot: Int): Int = slot / size

    /** The slots of [zone] that exist on a radio with [capacity] slots. */
    fun slots(zone: Int, capacity: Int): IntRange = zone * size until minOf((zone + 1) * size, capacity)

    companion object {
        /** A name the radio can store: printable ASCII, trimmed, at most [length] characters. */
        fun clean(name: String, length: Int): String =
            name.filter { it in ' '..'~' }.trim().take(length).trim()
    }
}
