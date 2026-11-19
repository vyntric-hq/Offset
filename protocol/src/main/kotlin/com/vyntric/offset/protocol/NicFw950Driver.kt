/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol and layout from nicsure's nicFW880/950 documentation and RT950Pro BTLE bridge,
 * checked against the NicFW950 RMS app. Facts only; no code copied.
 */
package com.vyntric.offset.protocol

import kotlin.random.Random

/**
 * The Radtel RT-950 Pro running nicsure's nicFW950 replacement firmware. Same radio and cable as
 * [Rt950ProDriver], but nicFW speaks its own storage protocol and stores 999 channels in its own
 * format, so it is a separate radio type. Experimental: Offset has not written one on hardware.
 */
object NicFw950Driver : RadioDriver {
    override val id = "nicfw950"
    override val displayName = "Radtel RT-950 Pro (nicFW950)"
    override val shortName = "RT-950 nicFW"
    override val baudRate = NicFw950Clone.BAUD_RATE
    override val capacity = NicFw950Image.CHANNEL_COUNT
    override val experimental = true
    /**
     * Channels keep their numbers: each record carries groups, clarifier, scrambler and a power
     * level Offset does not model, and an edit is encoded on top of the slot's own record, so
     * moving a channel would hand it another slot's settings. New channels go into free slots.
     */
    override val fixedSlots = true
    override val fixedSlotsReason =
        "each one carries groups and settings Offset keeps as they are; new channels go into free numbers."
    override val defaultProfile: RadioProfile = NicFw950Profile
    override val chirpModels: Set<String> = setOf(NicFw950Image.CHIRP_MODEL)
    override val chirpFirstLocation = 1
    override val cableAdvice =
        "Use the RT-950 Pro's own programming cable (a CH340 USB serial cable). This type is for " +
            "radios running nicFW950; for Radtel's own firmware choose \"RT-950 Pro\". After a write " +
            "nicFW restarts the radio so the new channels take effect."

    /** Only the 62,464 byte channel section; no other family uses that size. */
    override fun recognizes(bytes: ByteArray) = bytes.size == NicFw950Image.SIZE

    override fun image(bytes: ByteArray): NicFw950Image = NicFw950Image.of(bytes)

    override fun clone(
        port: SerialPort,
        sleeper: Sleeper,
        onProgress: (CloneProgress) -> Unit,
        random: Random,
    ): RadioClone = NicFw950Clone(port, sleeper, onProgress)

    override fun simulator(image: RadioImage): SimulatedDevice {
        require(image is NicFw950Image) { "Not a nicFW950 image" }
        return NicFw950Simulator(image)
    }

    /**
     * A synthetic channel section: three VFOs with the magic, a few channels covering the modes
     * Offset models, one that keeps a "very high" power and groups Offset does not edit, and an
     * inactive slot with leftover bytes. No real callsigns.
     */
    override fun sampleImage(): NicFw950Image {
        val bytes = ByteArray(NicFw950Image.SIZE)
        // VFO A/B/C: active records in slots 0 to 2; slot 3 left blank.
        listOf(146_520_000L, 446_000_000L, 145_000_000L).forEachIndexed { i, hz ->
            NicFw950Codec.encode(Channel(hz, name = "VFO ${'A' + i}")).copyInto(bytes, i * NicFw950Codec.RECORD_SIZE)
        }
        val channels = listOf(
            Channel(146_520_000, name = "Calling 2m"),
            Channel(145_290_000, Duplex.MINUS, 600_000, txSquelch = Squelch.Ctcss(1000), name = "Rpt 2m"),
            Channel(
                442_100_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(1273), Squelch.Ctcss(1273),
                power = Power.MID, name = "Rpt 70cm",
            ),
            Channel(
                446_000_000, txSquelch = Squelch.Dcs(23), rxSquelch = Squelch.Dcs(23, inverted = true),
                power = Power.LOW, wide = false, name = "PMR 1",
            ),
            Channel(162_550_000, Duplex.OFF, name = "NOAA WX 7"),
            Channel(147_420_000, busyLockout = true, pttId = PttId.BOTH, name = "Simplex group A"),
        )
        channels.forEachIndexed { i, c -> NicFw950Codec.encode(c).copyInto(bytes, NicFw950Image.channelOffset(i)) }
        // Channel 6: very high power (6) and groups A and C, both kept by Offset's edits.
        val six = NicFw950Image.channelOffset(5)
        bytes[six + 21] = 6
        bytes[six + 8] = 1
        bytes[six + 9] = 3
        // Channel 8: a deleted channel; its name is still there, but no frequency or magic.
        val eight = NicFw950Image.channelOffset(7)
        "Old".forEachIndexed { i, ch -> bytes[eight + NicFw950Codec.NAME_OFFSET + i] = ch.code.toByte() }
        return NicFw950Image.of(bytes)
    }
}

/** What nicFW950 accepts in a channel. */
object NicFw950Profile : RadioProfile {
    override val displayName = "RT-950 Pro (nicFW950)"

    /** The RT-950 Pro's tuning range, as for the stock firmware; nicFW enforces its own TX band plan. */
    override val bands: List<LongRange> = Rt950ProProfile.bands

    override val powerLevels: List<Power> = listOf(Power.HIGH, Power.MID, Power.LOW)

    /** 30 ASCII characters (CHANNAME_MAX_LENGTH), as nicFW's own programming app writes. */
    override val nameLength = NicFw950Codec.NAME_BYTES
    override val nameCharset = RadioProfile.ASCII
    override val uppercaseNames = false

    /** nicFW scans channel groups; there is no per channel scan flag. */
    override val storesScanAdd = false

    /**
     * nicFW's levels are set by its own power calibration; these are the stock firmware's
     * figures for the levels Offset writes (high, medium, low).
     */
    override fun watts(power: Power): Double = Rt950ProProfile.watts(power)
}
