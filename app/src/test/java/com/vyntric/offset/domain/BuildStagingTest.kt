package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Squelch
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildStagingTest {
    private var key = 0L
    private val build = BuildStaging { ++key }

    private fun repeater(out: Long, input: Long?, up: Squelch = Squelch.None, down: Squelch = Squelch.None, call: String = "W1AW") =
        Repeater("id$out", call, out, input, up, down, null, null, null, null, null, RepeaterSource.HEARHAM)

    @Test fun `maps offsets, splits and tones from repeater listings`() {
        val m = RadioModel.UV5R
        assertEquals(Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW"),
            BuildStaging.toChannel(repeater(146_940_000, 146_340_000, Squelch.Ctcss(1000)), m))
        assertEquals(Duplex.PLUS, BuildStaging.toChannel(repeater(442_100_000, 447_100_000), m).duplex)
        val tsql = BuildStaging.toChannel(repeater(147_000_000, 147_600_000, Squelch.Ctcss(885), Squelch.Ctcss(885)), m)
        assertEquals(Squelch.Ctcss(885), tsql.rxSquelch)
        val split = BuildStaging.toChannel(repeater(146_000_000, 446_000_000), m)
        assertEquals(Duplex.SPLIT, split.duplex)
        assertEquals(446_000_000, split.offsetHz)
        assertEquals("KJ6ABCD", BuildStaging.toChannel(repeater(146_520_000, null, call = "kj6abcde"), m).name)
    }

    @Test fun `adding skips duplicates and stops at 128 channels`() {
        val start = build.add(emptyList(), List(127) { Channel(146_000_000L + it * 5_000L) }, ChannelOrigin.MANUAL).staging
        val r = build.add(start, listOf(Channel(146_000_000), Channel(147_000_000), Channel(147_500_000)), ChannelOrigin.REPEATER)
        assertEquals(1, r.added)
        assertEquals(1, r.duplicates)
        assertEquals(1, r.overflow)
        assertEquals(128, r.staging.size)
    }

    @Test fun `new channels fill gaps, moves keep the used slots, compact packs`() {
        val a = StagedChannel(1, Channel(146_520_000), ChannelOrigin.RADIO, slot = 0)
        val b = StagedChannel(2, Channel(147_000_000), ChannelOrigin.RADIO, slot = 5)
        val added = build.add(listOf(a, b), listOf(Channel(446_000_000)), ChannelOrigin.MANUAL).staging
        assertEquals(listOf(0, 1, 5), added.map { it.slot })
        val moved = build.move(added, 0, 2)
        assertEquals(listOf(0, 1, 5), moved.map { it.slot })
        assertEquals(listOf(added[1].key, added[2].key, added[0].key), moved.map { it.key })
        assertEquals(listOf(0, 1, 2), build.compact(moved).map { it.slot })
        assertEquals(listOf(0, 5), build.remove(listOf(a, b, StagedChannel(3, Channel(1), ChannelOrigin.MANUAL, slot = 3)), 3).map { it.slot })
        val slots = moved.toSlots()
        assertEquals(128, slots.size)
        assertEquals(null, slots[2])
        assertEquals(Channel(146_520_000), slots[5])
    }

    @Test fun `move reorders by index and keeps keys`() {
        val list = build.add(emptyList(), List(3) { Channel(146_000_000L + it * 15_000L) }, ChannelOrigin.MANUAL).staging
        val moved = build.move(list, 0, 2)
        assertEquals(listOf(list[1].key, list[2].key, list[0].key), moved.map { it.key })
    }
}
