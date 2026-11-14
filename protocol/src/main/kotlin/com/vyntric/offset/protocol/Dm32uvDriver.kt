/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.PAGE
import com.vyntric.offset.protocol.Dm32uvLayout.putU16le
import kotlin.random.Random

/**
 * The Baofeng DM-32UV (DMR and analog), ported from CHIRP's baofeng_dm32uv driver.
 *
 * Offset programs analog channels only. DMR channels are shown and kept byte for byte (or
 * deleted), and writes touch only the channel, TX contact, scan list and zone pages.
 */
object Dm32uvDriver : RadioDriver {
    override val id = "dm32uv"
    override val displayName = "Baofeng DM-32UV"
    override val shortName = "DM-32UV"
    override val baudRate = 115200
    override val capacity = Dm32uvLayout.CH_COUNT
    override val experimental = false
    override val fixedSlots = true
    override val defaultProfile: RadioProfile = Dm32uvProfile
    override val chirpModels: Set<String> = setOf("DM-32UV")

    /** CHIRP's memory_bounds start at channel 1. */
    override val chirpFirstLocation = 1
    override val digitalModes: Set<String> = setOf(Dm32uvCodec.DMR)

    /** CHIRP's CH340 advice: some cables flip bit 7 of bytes from this radio. */
    const val CABLE_ADVICE = "Some CH340 programming cables corrupt data from this radio; " +
        "a cable with an FTDI or CP2102 chip is recommended."

    override val cableAdvice = CABLE_ADVICE

    override fun recognizes(bytes: ByteArray) = bytes.size == Dm32uvImage.SIZE

    override fun image(bytes: ByteArray): Dm32uvImage = Dm32uvImage.of(bytes)

    override fun clone(
        port: SerialPort,
        sleeper: Sleeper,
        onProgress: (CloneProgress) -> Unit,
        random: Random,
    ): RadioClone = object : RadioClone {
        private val clone = Dm32uvClone(port, sleeper, onProgress)

        override fun download(phase: CloneProgress.Phase) = clone.download(phase)

        override fun upload(target: RadioImage, before: RadioImage): Int {
            require(target is Dm32uvImage && before is Dm32uvImage) { "Not a DM-32UV image" }
            return clone.upload(target, before)
        }
    }

    override fun simulator(image: RadioImage): SimulatedDevice {
        require(image is Dm32uvImage) { "Not a DM-32UV image" }
        return Dm32uvSimulator.fromImage(image)
    }

    /**
     * A small synthetic factory codeplug: five analog channels in one zone, nothing else (no
     * settings pages, no DMR lists, no callsign or DMR ID).
     */
    override fun sampleImage(): Dm32uvImage {
        val data = ByteArray(Dm32uvLayout.IMAGE_SIZE) { 0xFF.toByte() }
        val channels = listOf(
            Channel(146_520_000, name = "2M Calling"),
            Channel(446_000_000, name = "70CM Calling"),
            Channel(147_000_000, Duplex.PLUS, 600_000, Squelch.Ctcss(1000), name = "Repeater 2M"),
            Channel(442_000_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(885), Squelch.Ctcss(885), name = "Repeater 70CM"),
            Channel(162_550_000, Duplex.OFF, power = Power.LOW, wide = false, name = "Weather 1"),
        )
        val chPage = Dm32uvLayout.CH_BASE
        data.fill(0, chPage, chPage + 0x10)
        data.putU16le(chPage, channels.size)
        channels.forEachIndexed { i, ch ->
            Dm32uvCodec.encode(ch, Dm32uvCodec.TEMPLATE, isNew = true).copyInto(data, Dm32uvLayout.channelOffset(i + 1))
        }
        data[chPage + PAGE - 1] = Dm32uvLayout.CH_TAG0.toByte()

        val zonePage = Dm32uvLayout.ZONE_BASE
        // Count 1; lines A and B show zone 1, position 1.
        Dm32uvLayout.hex("0101ff01ff01ff01").copyInto(data, zonePage)
        val zone = Dm32uvLayout.zoneOffset(1)
        data.fill(0, zone, zone + Dm32uvLayout.ZONE_REC)
        "Zone 1".toByteArray(Charsets.US_ASCII).copyInto(data, zone)
        data[zone + 16] = channels.size.toByte()
        channels.indices.forEach { data.putU16le(zone + 17 + 2 * it, it + 1) }
        data[zonePage + PAGE - 1] = Dm32uvLayout.ZONE_TAG0.toByte()
        return Dm32uvImage.of(data, Dm32uvSimulator.DEFAULT_FIRMWARE)
    }
}
