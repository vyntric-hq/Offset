/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

/**
 * The only parts of the image Offset ever writes to a radio.
 *
 * Settings, calibration, squelch tables and the aux block are never written. Both regions sit
 * inside the ranges CHIRP itself uploads by default for the UV-5R.
 */
object ImageRegions {
    const val BLOCK = 0x10

    /** Image offsets of the 128 memory records (radio 0x0000..0x0800). */
    val CHANNELS: IntRange = Uv5rImage.channelOffset(0) until Uv5rImage.channelOffset(Uv5rImage.CHANNEL_COUNT)

    /** Image offsets of the 128 name records (radio 0x1000..0x1800). */
    val NAMES: IntRange = Uv5rImage.nameOffset(0) until Uv5rImage.nameOffset(Uv5rImage.CHANNEL_COUNT)

    val WRITABLE: List<IntRange> = listOf(CHANNELS, NAMES)

    fun isWritableBlock(imageOffset: Int): Boolean =
        WRITABLE.any { imageOffset in it && imageOffset + BLOCK - 1 in it } && (imageOffset - 8) % BLOCK == 0

    /** Image offsets of 16 byte blocks in the writable regions where [before] and [after] differ. */
    fun changedBlocks(before: Uv5rImage, after: Uv5rImage): List<Int> {
        val a = before.bytes()
        val b = after.bytes()
        return WRITABLE.flatMap { region ->
            (region.first..region.last step BLOCK).filter { off ->
                !a.copyOfRange(off, off + BLOCK).contentEquals(b.copyOfRange(off, off + BLOCK))
            }
        }
    }

    /**
     * Channel numbers (0 based) whose memory or name record differs between [expected] and
     * [actual]. Used to verify a write by reading the radio back.
     */
    fun mismatchedChannels(expected: Uv5rImage, actual: Uv5rImage): List<Int> {
        val e = expected.bytes()
        val a = actual.bytes()
        return (0 until Uv5rImage.CHANNEL_COUNT).filter { i ->
            val ro = Uv5rImage.channelOffset(i)
            val no = Uv5rImage.nameOffset(i)
            !e.copyOfRange(ro, ro + BLOCK).contentEquals(a.copyOfRange(ro, ro + BLOCK)) ||
                !e.copyOfRange(no, no + BLOCK).contentEquals(a.copyOfRange(no, no + BLOCK))
        }
    }

    /**
     * True when [other] differs from [base] only inside the writable regions. Used to prove an
     * image about to be written carries the radio's own settings and calibration.
     */
    fun differsOnlyInWritableRegions(base: Uv5rImage, other: Uv5rImage): Boolean {
        if (base.size != other.size) return false
        val a = base.bytes()
        val b = other.bytes()
        for (i in a.indices) {
            if (a[i] != b[i] && WRITABLE.none { i in it }) return false
        }
        return true
    }
}
