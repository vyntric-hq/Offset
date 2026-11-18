/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import kotlin.random.Random

/** The Baofeng UV-5R family (UV-5R and BF-F8HP profiles), ported from CHIRP's uv5r driver. */
object Uv5rDriver : RadioDriver {
    override val id = "uv5r"
    override val displayName = "Baofeng UV-5R family"
    override val shortName = "UV-5R"
    override val baudRate = Uv5rClone.BAUD_RATE
    override val capacity = Uv5rImage.CHANNEL_COUNT
    override val experimental = false
    override val fixedSlots = false
    override val oneSessionWrite = true
    override val defaultProfile: RadioProfile = RadioModel.UV5R
    override val chirpModels: Set<String> = RadioModel.entries.map { it.chirpModel }.toSet()
    override val cableAdvice = ""

    override fun recognizes(bytes: ByteArray) =
        bytes.size == Uv5rImage.FULL_SIZE || bytes.size == Uv5rImage.NO_AUX_SIZE

    override fun image(bytes: ByteArray): Uv5rImage = Uv5rImage.of(bytes)

    override fun clone(
        port: SerialPort,
        sleeper: Sleeper,
        onProgress: (CloneProgress) -> Unit,
        random: Random,
    ): RadioClone = object : RadioClone {
        private val clone = Uv5rClone(port, sleeper, onProgress)

        override fun download(phase: CloneProgress.Phase) = clone.download(phase)

        override fun upload(target: RadioImage, before: RadioImage): Int {
            require(target is Uv5rImage && before is Uv5rImage) { "Not a UV-5R image" }
            val blocks = ImageRegions.changedBlocks(before, target)
            if (blocks.isNotEmpty()) clone.upload(target, blocks, expectedBefore = before)
            return blocks.size
        }
    }

    override fun simulator(image: RadioImage): SimulatedDevice {
        require(image is Uv5rImage) { "Not a UV-5R image" }
        return SimulatedRadio.fromImage(image)
    }

    /** An empty radio: no channels, firmware BFB297. The debug app ships a real factory image instead. */
    override fun sampleImage(): Uv5rImage {
        val ident = byteArrayOf(0xAA.toByte(), 0x30, 0x76, 0x04, 0x00, 0x05, 0x20, 0xDD.toByte())
        val aux = ByteArray(Uv5rImage.AUX_SIZE) { 0xFF.toByte() }
        "BFB297".toByteArray(Charsets.US_ASCII).copyInto(aux, 0x1EF0 - Uv5rImage.AUX_RADIO_START)
        return Uv5rImage.assemble(ident, ByteArray(Uv5rImage.MAIN_SIZE) { 0xFF.toByte() }, aux)
    }
}
