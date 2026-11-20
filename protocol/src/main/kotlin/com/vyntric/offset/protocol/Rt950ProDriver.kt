/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Protocol from Hertzz58/Radtel-RT950-Pro-Firmware (tools/cps_flash.py), GPL-3.0.
 * Channel layout and bands from NathanBarguss/Chirp_Radtel-RT-950-Pro (rt950pro/channel.py,
 * rt950pro/chirp_driver.py), MIT, Copyright (c) 2025 Nathan G. Barguss, 2E0NBS.
 */
package com.vyntric.offset.protocol

import kotlin.random.Random

/**
 * The Radtel RT-950 Pro. CHIRP has no driver for it; the protocol and layout come from the
 * reverse engineering credited above. Read and write are confirmed on a radio with the stock
 * firmware.
 */
object Rt950ProDriver : RadioDriver {
    override val id = "rt950pro"
    override val displayName = "Radtel RT-950 Pro"
    override val shortName = "RT-950 Pro"
    override val baudRate = Rt950ProClone.BAUD_RATE
    override val capacity = Rt950ProImage.CHANNEL_COUNT
    override val experimental = false
    override val fixedSlots = false
    override val defaultProfile: RadioProfile = Rt950ProProfile
    override val chirpModels: Set<String> = setOf(Rt950ProImage.MODEL_NAME)
    override val cableAdvice =
        "The RT-950 Pro programs through a USB serial cable with a CH340 chip (USB ID 1a86:7523). " +
            "Turn the radio on before connecting."

    /** Only the 33,152 byte image; no other family uses that size. */
    override fun recognizes(bytes: ByteArray) = bytes.size == Rt950ProImage.SIZE

    override fun image(bytes: ByteArray): Rt950ProImage = Rt950ProImage.of(bytes)

    override fun clone(
        port: SerialPort,
        sleeper: Sleeper,
        onProgress: (CloneProgress) -> Unit,
        random: Random,
    ): RadioClone = Rt950ProClone(port, sleeper, onProgress, random)

    override fun simulator(image: RadioImage): SimulatedDevice {
        require(image is Rt950ProImage) { "Not an RT-950 Pro image" }
        return Rt950ProSimulator(image)
    }

    /**
     * A synthetic codeplug: a few channels covering the modes Offset models and some it only
     * preserves (an AM airband channel, a receive only channel), plus VFO and settings bytes in
     * the layout Nathan Barguss's tools parse. No real callsigns.
     */
    override fun sampleImage(): Rt950ProImage {
        val bytes = ByteArray(Rt950ProImage.SIZE) { 0xFF.toByte() }
        val channels = listOf(
            Channel(146_520_000, name = "CALLING"),
            Channel(145_290_000, Duplex.MINUS, 600_000, txSquelch = Squelch.Ctcss(1000), name = "RPT 2M"),
            Channel(
                442_100_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(1273), Squelch.Ctcss(1273),
                power = Power.MID, name = "RPT 70CM",
            ),
            Channel(
                446_000_000, txSquelch = Squelch.Dcs(23), rxSquelch = Squelch.Dcs(23),
                power = Power.LOW, wide = false, name = "PMR 1",
            ),
            Channel(162_550_000, Duplex.OFF, scanAdd = false, name = "WX"),
            Channel(127_500_000, Duplex.OFF, name = "AIRBAND"),
        )
        channels.forEachIndexed { i, c -> Rt950ProCodec.encode(c).copyInto(bytes, Rt950ProImage.channelOffset(i)) }
        // The airband channel receives AM (bit0 of the flags), a mode Offset keeps but does not edit.
        val am = Rt950ProImage.channelOffset(5) + 15
        bytes[am] = (bytes[am].toInt() or 0x01).toByte()

        // VFO A/B/C: 8 decimal digit frequency (10 Hz units), tones off, high power, wide, step 12.5 kHz.
        val vfo = Rt950ProImage.SEGMENTS[1].imageOffset
        listOf("14652000", "44600000", "14500000").forEachIndexed { i, freq ->
            val o = vfo + i * 32
            bytes.fill(0, o, o + 32)
            freq.forEachIndexed { d, ch -> bytes[o + d] = (ch - '0').toByte() }
            bytes[o + 19] = 5
            "0006000".forEachIndexed { d, ch -> bytes[o + 20 + d] = (ch - '0').toByte() }
        }
        // Function settings: squelch 3, everything else at its first choice.
        val function = Rt950ProImage.SEGMENTS[2].imageOffset
        bytes.fill(0, function, function + 96)
        bytes[function] = 3
        return Rt950ProImage.of(bytes)
    }
}

/** What the RT-950 Pro accepts in a channel. */
object Rt950ProProfile : RadioProfile {
    override val displayName = "RT-950 Pro"

    /** Nathan Barguss's valid_bands, merged: 18 to 64 MHz, then 70 to 580 MHz (airband receive only). */
    override val bands: List<LongRange> = listOf(18_000_000L until 64_000_000L, 70_000_000L until 580_000_000L)

    override val powerLevels: List<Power> = listOf(Power.HIGH, Power.MID, Power.LOW)

    /** 12 bytes of GB2312; Offset writes printable ASCII, one byte per character. */
    override val nameLength = 12
    override val nameCharset = RadioProfile.ASCII
    override val uppercaseNames = false

    /** FM, or AM receive (airband); the AM bit sets how the channel is received. */
    override val modes: List<Modulation> = listOf(Modulation.FM, Modulation.AM)

    override val channelOptions: List<ChannelOption> get() = Rt950ProCodec.OPTIONS

    /** Channels pick a signalling group (an option) rather than a UV-5R style PTT-ID code. */
    override val pttIdCodes = 0

    /** Published 10 W / 5 W / 1 W (the radio's listing and Nathan Barguss's CHIRP driver). */
    override fun watts(power: Power): Double = when (power) {
        Power.VERY_HIGH, Power.HIGH -> 10.0
        Power.MID -> 5.0
        Power.LOW -> 1.0
        Power.VERY_LOW -> 0.5
        Power.ZERO -> 0.0
    }
}
