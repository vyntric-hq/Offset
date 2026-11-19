package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Squelch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.vyntric.offset.protocol.Uv5rImage
import org.junit.Test

class BuildStagingTest {
    private val uv5r = StagingTarget.of(com.vyntric.offset.protocol.Uv5rDriver)
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
        val start = build.add(emptyList(), List(127) { Channel(146_000_000L + it * 5_000L) }, ChannelOrigin.MANUAL, uv5r).staging
        val r = build.add(start, listOf(Channel(146_000_000), Channel(147_000_000), Channel(147_500_000)), ChannelOrigin.REPEATER, uv5r)
        assertEquals(1, r.added)
        assertEquals(1, r.duplicates)
        assertEquals(1, r.overflow)
        assertEquals(128, r.staging.size)
    }

    @Test fun `new channels fill gaps, moves keep the used slots, compact packs`() {
        val a = StagedChannel(1, Channel(146_520_000), ChannelOrigin.RADIO, slot = 0)
        val b = StagedChannel(2, Channel(147_000_000), ChannelOrigin.RADIO, slot = 5)
        val added = build.add(listOf(a, b), listOf(Channel(446_000_000)), ChannelOrigin.MANUAL, uv5r).staging
        assertEquals(listOf(0, 1, 5), added.map { it.slot })
        val moved = build.move(added, 0, 2, uv5r)
        assertEquals(listOf(0, 1, 5), moved.map { it.slot })
        assertEquals(listOf(added[1].key, added[2].key, added[0].key), moved.map { it.key })
        assertEquals(listOf(0, 1, 2), build.compact(moved, uv5r).map { it.slot })
        assertEquals(listOf(0, 5), build.remove(listOf(a, b, StagedChannel(3, Channel(1), ChannelOrigin.MANUAL, slot = 3)), 3).map { it.slot })
        val slots = moved.toSlots(Uv5rImage.CHANNEL_COUNT)
        assertEquals(128, slots.size)
        assertEquals(null, slots[2])
        assertEquals(Channel(146_520_000), slots[5])
    }

    /** A radio whose zones refer to channels by number, holding DMR channels, with 10 slots. */
    private val fixed = StagingTarget(10, RadioModel.UV5R, fixedSlots = true, digitalModes = setOf("DMR"))

    @Test fun `fixed slot radios never renumber channels`() {
        val a = StagedChannel(1, Channel(146_520_000), ChannelOrigin.RADIO, slot = 0)
        val b = StagedChannel(2, Channel(147_000_000), ChannelOrigin.RADIO, slot = 5)
        val added = build.add(listOf(a, b), listOf(Channel(446_000_000)), ChannelOrigin.MANUAL, fixed).staging
        assertEquals(listOf(0, 1, 5), added.map { it.slot })
        assertEquals(added, build.move(added, 0, 2, fixed))
        assertEquals(added, build.compact(added, fixed))
    }

    @Test fun `adding stops at the target's capacity`() {
        val r = build.add(emptyList(), List(12) { Channel(146_000_000L + it * 15_000L) }, ChannelOrigin.MANUAL, fixed)
        assertEquals(10, r.added)
        assertEquals(2, r.overflow)
        assertEquals((0 until 10).toList(), r.staging.map { it.slot })
    }

    @Test fun `digital channels are kept, never replaced, and flagged on radios that cannot hold them`() {
        val dmr = StagedChannel(1, Channel(441_000_000, name = "DMR RPT", digitalMode = "DMR"), ChannelOrigin.RADIO, slot = 0)
        val analog = StagedChannel(2, Channel(146_520_000), ChannelOrigin.RADIO, slot = 1)
        val list = listOf(dmr, analog)
        assertEquals(list, build.replace(list, 1, Channel(146_000_000)))
        assertEquals(emptyMap<Long, Any>(), build.problems(list, fixed))
        val onUv5r = build.problems(list, uv5r)
        assertEquals(setOf(1L), onUv5r.keys)
        assertTrue(onUv5r.getValue(1).first().message.contains("cannot hold"))
    }

    @Test fun `channels the radio already holds are not flagged`() {
        val odd = Channel(225_000_000, name = "220")
        val radio = com.vyntric.offset.protocol.Uv5rDriver.sampleImage().withChannel(3, odd)
        val list = listOf(StagedChannel(1, odd, ChannelOrigin.RADIO, slot = 3), StagedChannel(2, odd, ChannelOrigin.MANUAL, slot = 4))
        assertEquals(setOf(2L), build.problems(list, uv5r, radio).keys)
        assertEquals(setOf(1L, 2L), build.problems(list, uv5r).keys)
    }

    @Test fun `slots beyond a smaller radio's capacity are problems`() {
        val list = listOf(StagedChannel(1, Channel(146_520_000), ChannelOrigin.RADIO, slot = 200))
        assertTrue(build.problems(list, uv5r).getValue(1).first().message.contains("128 channels"))
        assertEquals(128, list.toSlots(Uv5rImage.CHANNEL_COUNT).size)
    }

    @Test fun `repeater names follow the radio's character rules`() {
        val mixedCase = object : com.vyntric.offset.protocol.RadioProfile {
            override val displayName = "Test"
            override val bands = RadioModel.UV5R.bands
            override val powerLevels = RadioModel.UV5R.powerLevels
            override val nameLength = 16
            override val nameCharset = com.vyntric.offset.protocol.RadioProfile.ASCII
            override val uppercaseNames = false
            override fun watts(power: com.vyntric.offset.protocol.Power) = 1.0
        }
        assertEquals("KJ6abc Club RPT", BuildStaging.toChannel(repeater(146_520_000, null, call = "KJ6abc Club RPT"), mixedCase).name)
        assertEquals("KJ6ABC ", BuildStaging.toChannel(repeater(146_520_000, null, call = "KJ6abc Club RPT"), RadioModel.UV5R).name + " ")
    }

    @Test fun `move reorders by index and keeps keys`() {
        val list = build.add(emptyList(), List(3) { Channel(146_000_000L + it * 15_000L) }, ChannelOrigin.MANUAL, uv5r).staging
        val moved = build.move(list, 0, 2, uv5r)
        assertEquals(listOf(list[1].key, list[2].key, list[0].key), moved.map { it.key })
    }

    @Test fun `a staging list from before modes keeps the radio's AM channel AM`() {
        val radio = com.vyntric.offset.protocol.Rt950ProDriver.sampleImage()
        val am = (0 until radio.capacity).first { radio.channel(it)?.mode == com.vyntric.offset.protocol.Modulation.AM }
        val legacy = listOf(
            StagedChannel(1, radio.channel(am)!!.copy(mode = com.vyntric.offset.protocol.Modulation.FM), ChannelOrigin.RADIO, slot = am, modeUnknown = true),
            StagedChannel(2, Channel(146_520_000), ChannelOrigin.MANUAL, slot = 900, modeUnknown = true),
        )
        val adopted = build.adoptRadioModes(legacy, radio)
        assertEquals(com.vyntric.offset.protocol.Modulation.AM, adopted[0].channel.mode)
        assertEquals(com.vyntric.offset.protocol.Modulation.FM, adopted[1].channel.mode)
        assertEquals(listOf(false, false), adopted.map { it.modeUnknown })
        // The adopted channel matches the radio, so the write leaves the slot's bytes alone.
        val nic = com.vyntric.offset.protocol.NicFw950Driver.sampleImage()
        val veryHigh = nic.channel(5)!!
        val staged050 = StagedChannel(3, veryHigh.copy(power = com.vyntric.offset.protocol.Power.HIGH, options = emptyMap()), ChannelOrigin.RADIO, slot = 5, modeUnknown = true)
        assertEquals(com.vyntric.offset.protocol.Power.VERY_HIGH, build.adoptRadioModes(listOf(staged050), nic).single().channel.power)
        assertEquals(radio.channel(am), adopted[0].channel.copy(options = radio.channel(am)!!.options))
    }

    @Test fun `editing a channel of unknown mode keeps it unknown unless the mode changes`() {
        val s = listOf(StagedChannel(1, Channel(127_550_000, Duplex.OFF, name = "AIR"), ChannelOrigin.RADIO, modeUnknown = true))
        assertTrue(build.replace(s, 1, s[0].channel.copy(name = "TOWER")).single().modeUnknown)
        assertFalse(build.replace(s, 1, s[0].channel.copy(mode = com.vyntric.offset.protocol.Modulation.AM)).single().modeUnknown)
    }
}
