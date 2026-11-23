/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import kotlin.random.Random

/**
 * One family of radios that share a memory layout and clone protocol. Everything that differs
 * between families lives behind this interface: the image format, the protocol, the channel
 * limits and which memory Offset may write.
 */
interface RadioDriver {
    /** Stable identifier, persisted with images and settings. Never rename one. */
    val id: String

    /** Vendor and model as shown to the user, for example "Baofeng DM-32UV". */
    val displayName: String

    /** Short label for tight spaces, for example "DM-32UV". */
    val shortName: String

    /** Serial speed of the programming cable for this family. */
    val baudRate: Int

    /** Number of memory channels; channel indexes are 0 until [capacity], shown as index + 1. */
    val capacity: Int

    /** Writes rely on a memory layout nobody has verified on a radio with Offset. */
    val experimental: Boolean

    /**
     * True when channels already on the radio must keep their numbers because other memory
     * (zones, scan lists) refers to them by number. Staging then never renumbers them.
     */
    val fixedSlots: Boolean

    /**
     * For a [fixedSlots] family, why channels keep their numbers and where new ones go, finishing
     * the sentence "Channels keep their numbers on this radio because ...".
     */
    val fixedSlotsReason: String get() = ""

    /** True when some images of this family carry renamable zones ([RadioImage.namedZones]). */
    val hasNamedZones: Boolean get() = false

    /**
     * True when the radio does not answer a new clone session soon after one ends (the UV-5R
     * stays in clone mode until it times out, then restarts). A write then reads, writes and reads
     * back in one session, on one [RadioClone]; otherwise each step is a session of its own.
     */
    val oneSessionWrite: Boolean get() = false

    /** Channel limits to use before a radio of this family has been read. */
    val defaultProfile: RadioProfile

    /** CHIRP model names (the .img metadata "model") this driver reads. */
    val chirpModels: Set<String>

    /**
     * The Location of the first channel in CHIRP CSV files for this radio (CHIRP's memory_bounds
     * start): 0 for the UV-5R, 1 for radios whose first channel is 1.
     */
    val chirpFirstLocation: Int get() = 0

    /** Digital modes the radio stores (Channel.digitalMode values) that Offset keeps as they are. */
    val digitalModes: Set<String> get() = emptySet()

    /** Advice about cables, shown when this family is selected. Empty when there is nothing to add. */
    val cableAdvice: String

    /** True when [bytes] (an image without CHIRP's trailer) has this family's size and shape. */
    fun recognizes(bytes: ByteArray): Boolean

    /** Wraps raw image bytes. Throws [InvalidImageException] when they are not this family's image. */
    fun image(bytes: ByteArray): RadioImage

    /**
     * A clone session on [port]. [random] is used where the protocol needs random bytes (the
     * RT-950 Pro key exchange); tests pass a seeded one.
     */
    fun clone(
        port: SerialPort,
        sleeper: Sleeper,
        onProgress: (CloneProgress) -> Unit = {},
        random: Random = Random.Default,
    ): RadioClone

    /** An in-memory radio holding [image] that speaks this family's protocol, for tests and debug builds. */
    fun simulator(image: RadioImage): SimulatedDevice

    /** A plausible factory image for the simulated radio in debug builds. */
    fun sampleImage(): RadioImage
}

/**
 * One clone session with a connected radio. Every failure is a [RadioException]. For a driver
 * with [RadioDriver.oneSessionWrite], download, upload and download again may follow each other
 * on the same instance without a new handshake.
 */
interface RadioClone {
    /** Reads the whole image. Rule 1: a write always starts from a full read. */
    fun download(phase: CloneProgress.Phase = CloneProgress.Phase.READING): RadioImage

    /**
     * Writes the writable memory of [target] that differs from [before], the image just read from
     * this radio. Refuses (with [RadioException.IdentMismatch]) before writing anything when the
     * connected radio is not the kind [before] was read from, or when its writable memory no
     * longer matches [before]. Returns the number of blocks or pages written.
     */
    fun upload(target: RadioImage, before: RadioImage): Int
}

/** A simulated radio: the host side is the [SerialPort]; the rest is for tests and debug builds. */
interface SimulatedDevice : SerialPort {
    /** Ends the current clone session, as when the host closes the port. */
    fun endSession()

    /** What the radio holds now. */
    fun currentImage(): RadioImage
}

/**
 * A radio image: the radio's memory in the family's file layout. Immutable; edits return a new
 * image. Channel indexes are 0 based and shown to the user as index + 1.
 */
interface RadioImage {
    val driver: RadioDriver

    /** Channel limits of the exact model in this image (a family can have several). */
    val profile: RadioProfile

    /**
     * Identifies the kind of radio this image came from (model and firmware where the protocol
     * reports them). Two radios of the same model have the same identity. Rule 2 compares these.
     */
    val identity: String

    /** Model name as detected from the image. */
    val modelName: String

    val firmwareVersion: String?

    val capacity: Int get() = driver.capacity

    val size: Int

    /** A defensive copy of the raw bytes. */
    fun bytes(): ByteArray

    /** The channel at [index], or null for an empty slot. */
    fun channel(index: Int): Channel?

    fun channels(): List<Channel?> = (0 until capacity).map { channel(it) }

    val usedChannelCount: Int

    /**
     * Returns a copy with every slot replaced by [slots] (index = slot, null or missing = empty).
     * Only memory Offset may write changes. A slot whose channel is unchanged keeps its exact
     * bytes, including fields Offset does not model (DMR settings, scramblers, icons).
     */
    fun withChannels(slots: List<Channel?>): RadioImage

    /** This image with its writable memory replaced by [source]'s ("Restore to radio"). */
    fun withWritableRegionsOf(source: RadioImage): RadioImage

    /** True when [other] differs from this image only in memory Offset may write. Rule 3. */
    fun differsOnlyInWritableRegions(other: RadioImage): Boolean

    /** Channel indexes whose stored bytes differ between this image and [actual]. Rule 5. */
    fun mismatchedChannels(actual: RadioImage): List<Int>

    /** The radio's named zones when Offset can rename them, else null. */
    val namedZones: RadioZones? get() = null

    /** A copy with the zones renamed ([namedZones] must not be null). Zone names are writable memory. */
    fun withZoneNames(names: List<String>): RadioImage =
        throw UnsupportedOperationException("${driver.displayName} zones cannot be renamed")

    /**
     * CHIRP .img metadata for this image (rclass, vendor, model, variant and any extras). A family
     * CHIRP cannot open still gets metadata, so Offset can read its own exports back.
     */
    val chirpMetadata: Map<String, String>
}

/** Every family Offset supports, in the order the radio type picker lists them. */
object RadioDrivers {
    val all: List<RadioDriver> by lazy { listOf(Uv5rDriver, Dm32uvDriver, Rt950ProDriver, NicFw950Driver) }

    val default: RadioDriver get() = Uv5rDriver

    fun byId(id: String?): RadioDriver? = all.firstOrNull { it.id == id }

    fun forChirpModel(model: String?): RadioDriver? = all.firstOrNull { model in it.chirpModels }

    /** The family whose image [bytes] look like, or null. */
    fun recognize(bytes: ByteArray): RadioDriver? = all.firstOrNull { it.recognizes(bytes) }

    /** Wraps stored bytes saved under [driverId] (falling back to recognizing them). */
    fun image(driverId: String?, bytes: ByteArray): RadioImage {
        val driver = byId(driverId) ?: recognize(bytes)
            ?: throw InvalidImageException("Offset does not recognize this ${bytes.size} byte image.")
        return driver.image(bytes)
    }
}

class InvalidImageException(message: String) : Exception(message)
