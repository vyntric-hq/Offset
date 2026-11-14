/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.protocol.Squelch
import com.vyntric.offset.protocol.Tones

/** Parses the loosely formatted tone strings directories publish ("100.0", "DCS023", "D754I", "CSQ"). */
internal object ToneText {
    private val dcs = Regex("""^(?:DCS|DPL|D)\s*0*([0-7]{2,3})\s*([NIR]|INV)?$""")

    fun parse(raw: String?): Squelch {
        val s = raw?.trim()?.uppercase().orEmpty()
        if (s.isEmpty() || s in setOf("0", "0.0", "0.00", "NONE", "CSQ", "CARRIER", "OPEN")) return Squelch.None
        if (s.startsWith("CC")) return Squelch.None // DMR color code
        dcs.find(s)?.let { m ->
            val code = m.groupValues[1].toInt()
            if (!Tones.isValidDcs(code)) return Squelch.None
            return Squelch.Dcs(code, inverted = m.groupValues[2].let { it == "I" || it == "R" || it == "INV" })
        }
        val hz = s.removeSuffix("HZ").trim().toDoubleOrNull() ?: return Squelch.None
        if (hz < 60.0 || hz > 260.0) return Squelch.None
        val tenths = Math.round(hz * 10).toInt()
        val nearest = Tones.nearestCtcss(tenths)
        // Snap tiny typos (88.4 for 88.5) to the standard tone; keep genuinely odd tones as given.
        return Squelch.Ctcss(if (kotlin.math.abs(nearest - tenths) <= 1) nearest else tenths)
    }
}
