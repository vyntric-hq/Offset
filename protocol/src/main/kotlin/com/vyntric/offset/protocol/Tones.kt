/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Tone tables ported from CHIRP (chirp/chirp_common.py), GPL.
 */
package com.vyntric.offset.protocol

/** Standard CTCSS and DCS code tables accepted by the UV-5R family. */
object Tones {
    /** The 50 CTCSS tones CHIRP accepts, in tenths of a hertz (885 = 88.5 Hz). */
    val CTCSS_TENTHS: List<Int> = listOf(
        670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035,
        1072, 1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462, 1514, 1567,
        1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928,
        1966, 1995, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541,
    )

    /**
     * DCS codes in radio index order. CHIRP's UV5R_DTCS is the standard 104 codes plus 645,
     * sorted. The radio stores `index + 1` (normal) or `index + 1 + 0x69` (inverted).
     */
    val DCS_CODES: List<Int> = (listOf(
        23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54,
        65, 71, 72, 73, 74, 114, 115, 116, 122, 125, 131,
        132, 134, 143, 145, 152, 155, 156, 162, 165, 172, 174,
        205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252,
        255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325,
        331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412,
        413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464,
        465, 466, 503, 506, 516, 523, 526, 532, 546, 565, 606,
        612, 624, 627, 631, 632, 654, 662, 664, 703, 712, 723,
        731, 732, 734, 743, 754,
    ) + 645).sorted()

    /** Raw values at or above this are CTCSS in tenths of a hertz; below are DCS indexes. */
    internal const val CTCSS_THRESHOLD = 0x0258
    internal const val DCS_INVERT_OFFSET = 0x69

    fun isValidCtcss(tenths: Int): Boolean = tenths in CTCSS_TENTHS
    fun isValidDcs(code: Int): Boolean = code in DCS_CODES

    /** Closest standard CTCSS tone to [tenths], used when importing loosely formatted data. */
    fun nearestCtcss(tenths: Int): Int = CTCSS_TENTHS.minBy { kotlin.math.abs(it - tenths) }

    /** Formats a DCS code with three octal-style digits, as printed on radios: 23 -> "023". */
    fun formatDcs(code: Int): String = code.toString().padStart(3, '0')

    fun formatCtcss(tenths: Int): String = "${tenths / 10}.${tenths % 10}"
}
