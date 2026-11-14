/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from CHIRP (chirp/drivers/baofeng_dm32uv.py), GPL.
 */
package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Dm32uvLayout.CH_COUNT
import com.vyntric.offset.protocol.Dm32uvLayout.CH_SIZE
import com.vyntric.offset.protocol.Dm32uvLayout.PAGE

/**
 * An immutable DM-32UV image in CHIRP's logical layout (see [Dm32uvLayout]): 76 slots of 4 KB,
 * 311296 bytes, byte for byte what CHIRP saves.
 *
 * The image bytes do not hold the firmware version (CHIRP keeps it in the .img metadata), so
 * [firmwareVersion] is known only for an image read from a radio in this session; it is carried
 * along by edits but is not part of equality. [identity] is the model ID every DM-32UV reports.
 */
class Dm32uvImage private constructor(
    private val data: ByteArray,
    override val firmwareVersion: String?,
) : RadioImage {

    override val driver: RadioDriver get() = Dm32uvDriver
    override val profile: RadioProfile get() = Dm32uvProfile
    override val identity: String get() = Dm32uvLayout.MODEL_ID
    override val modelName: String get() = "DM-32UV"
    override val size: Int get() = data.size

    override fun bytes(): ByteArray = data.copyOf()

    private val codeplug get() = Dm32uvCodeplug(data)

    /** Number of channels the radio counts (1..count are in use or empty). */
    val channelCount: Int get() = codeplug.channelCount

    override fun channel(index: Int): Channel? {
        require(index in 0 until CH_COUNT) { "Channel index $index out of range" }
        return codeplug.channel(index + 1)
    }

    override fun channels(): List<Channel?> = codeplug.let { cp -> (1..CH_COUNT).map { cp.channel(it) } }

    override val usedChannelCount: Int get() = codeplug.let { cp -> (1..cp.channelCount).count { cp.channel(it) != null } }

    /** Zone names and members (channel numbers, 1 based), in zone order. */
    val zones: List<Pair<String, List<Int>>>
        get() = codeplug.zones().map { String(it.name.toByteArray(), Charsets.ISO_8859_1) to it.members }

    /**
     * Applies [slots] as a sequence of CHIRP set_memory calls, lowest channel first.
     *
     * - An unchanged slot keeps its bytes. A changed analog channel is encoded on top of its old
     *   record; a channel in an empty slot starts from CHIRP's default record and is added to the
     *   zone "Offset" (see [Dm32uvCodeplug.addToOffsetZone]) so it can be selected on the radio.
     * - Removing a channel clears its record and TX contact, takes it out of zones and scan lists,
     *   drops an empty last zone and lowers the channel count, as CHIRP does.
     * - A DMR channel must be passed back unchanged or removed: Offset never edits digital
     *   channels, so anything else is an [IllegalArgumentException].
     */
    override fun withChannels(slots: List<Channel?>): Dm32uvImage {
        require(slots.size <= CH_COUNT) { "At most $CH_COUNT channels, got ${slots.size}" }
        val original = codeplug
        val edit = Dm32uvCodeplug(data.copyOf())
        for (n in 1..CH_COUNT) {
            val old = original.channel(n)
            val new = slots.getOrNull(n - 1)
            if (old == null && new == null) continue
            if (old?.digitalMode != null) {
                require(new == null || new == old) { "Channel $n is a DMR channel; Offset can only keep or delete it." }
                if (new == null) edit.delete(n)
                continue
            }
            require(new?.digitalMode == null) { "Offset cannot create or edit digital channels (channel $n)." }
            when {
                new == null -> edit.delete(n)
                old == null -> {
                    edit.put(n, new, isNew = true)
                    edit.addToOffsetZone(n)
                }
                isUnchanged(n, old, new) -> Unit
                else -> edit.put(n, new, isNew = false)
            }
        }
        return Dm32uvImage(edit.data, firmwareVersion)
    }

    /** True when storing [new] over channel [n] would decode as [old]: its bytes then stay as they are. */
    private fun isUnchanged(n: Int, old: Channel, new: Channel): Boolean {
        if (new == old) return true
        val record = Dm32uvCodec.encode(new, codeplug.record(n), isNew = false)
        return Dm32uvCodec.decode(record, 0) == old
    }

    override fun withWritableRegionsOf(source: RadioImage): Dm32uvImage {
        require(source is Dm32uvImage) { "Not a DM-32UV image" }
        val out = data.copyOf()
        for (tag in Dm32uvLayout.WRITABLE_TAGS) {
            val s = Dm32uvLayout.slot(tag)
            val radioHasPage = (data[s + PAGE - 1].toInt() and 0xFF) == tag
            source.data.copyInto(out, s, s, s + PAGE)
            // A page the source never had but this radio has now (a zone page created by a later
            // write, say) must be emptied, not left behind: an empty slot that keeps its tag byte
            // tells the upload to write an empty page there, as CHIRP does for emptied pages.
            if (radioHasPage) out[s + PAGE - 1] = tag.toByte()
        }
        return Dm32uvImage(out, firmwareVersion)
    }

    override fun differsOnlyInWritableRegions(other: RadioImage): Boolean {
        if (other !is Dm32uvImage) return false
        val writable = Dm32uvLayout.WRITABLE_TAGS.map { Dm32uvLayout.slot(it) }.toSet()
        return Dm32uvLayout.IMAGE_TAGS.indices.all { i ->
            val s = i * PAGE
            s in writable || data.copyOfRange(s, s + PAGE).contentEquals(other.data.copyOfRange(s, s + PAGE))
        }
    }

    /**
     * Channels whose record, TX contact entry, decoded contents, or zone and scan list membership
     * differ from [actual]. Zone and scan list pages are compared by membership, not bytes: the
     * zone header bytes that follow the radio's display are taken from the radio on upload.
     */
    override fun mismatchedChannels(actual: RadioImage): List<Int> {
        require(actual is Dm32uvImage) { "Not a DM-32UV image" }
        val mine = codeplug
        val theirs = actual.codeplug
        val myZones = mine.zones()
        val theirZones = theirs.zones()
        val myScans = mine.scanListMembers()
        val theirScans = theirs.scanListMembers()
        fun memberships(zones: List<Dm32uvCodeplug.Zone>, scans: List<Pair<List<Byte>, List<Int>>>, n: Int) =
            zones.filter { n in it.members }.map { it.name } to scans.filter { n in it.second }.map { it.first }
        return (1..CH_COUNT).filter { n ->
            val ro = Dm32uvLayout.channelOffset(n)
            val to = Dm32uvLayout.txContactOffset(n)
            !data.copyOfRange(ro, ro + CH_SIZE).contentEquals(actual.data.copyOfRange(ro, ro + CH_SIZE)) ||
                !data.copyOfRange(to, to + 2).contentEquals(actual.data.copyOfRange(to, to + 2)) ||
                mine.channel(n) != theirs.channel(n) ||
                memberships(myZones, myScans, n) != memberships(theirZones, theirScans, n)
        }.map { it - 1 }
    }

    override val chirpMetadata: Map<String, String>
        get() = linkedMapOf(
            "rclass" to "DM32UV",
            "vendor" to "Baofeng",
            "model" to "DM-32UV",
            "variant" to "",
        ).apply { firmwareVersion?.let { put("dm32uv_firmware", it) } }

    /** The 4 KB slot of [tag], as stored in the image. */
    internal fun slotBytes(tag: Int): ByteArray = Dm32uvLayout.slot(tag).let { data.copyOfRange(it, it + PAGE) }

    internal fun withFirmware(firmware: String?): Dm32uvImage = Dm32uvImage(data, firmware)

    override fun equals(other: Any?): Boolean = other is Dm32uvImage && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "Dm32uvImage(fw=$firmwareVersion, channels=$channelCount)"

    companion object {
        val SIZE: Int = Dm32uvLayout.IMAGE_SIZE

        /** Wraps raw bytes after validating the size. [firmware] is known only from a radio read. */
        fun of(bytes: ByteArray, firmware: String? = null): Dm32uvImage {
            if (bytes.size != SIZE) {
                throw InvalidImageException("Image is ${bytes.size} bytes; a DM-32UV image is $SIZE bytes.")
            }
            return Dm32uvImage(bytes.copyOf(), firmware)
        }
    }
}

/** The DM-32UV's channel limits: two bands, three power levels, 16 character ASCII names. */
object Dm32uvProfile : RadioProfile {
    override val displayName = "DM-32UV"
    override val bands: List<LongRange> = listOf(136_000_000L until 174_000_000L, 400_000_000L until 480_000_000L)
    override val powerLevels: List<Power> = listOf(Power.HIGH, Power.MID, Power.LOW)
    override val nameLength = 16
    override val nameCharset: String = RadioProfile.ASCII
    override val uppercaseNames = false

    override fun watts(power: Power): Double = when (power) {
        Power.HIGH -> 5.0
        Power.MID -> 2.5
        Power.LOW -> 1.0
    }
}
