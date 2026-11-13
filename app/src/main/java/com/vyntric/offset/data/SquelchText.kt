/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.protocol.Squelch

/** Compact storage form for a squelch setting: "", "T1000" (CTCSS tenths), "D23N" or "D23I". */
object SquelchText {
    fun format(s: Squelch): String = when (s) {
        Squelch.None -> ""
        is Squelch.Ctcss -> "T${s.tenths}"
        is Squelch.Dcs -> "D${s.code}${if (s.inverted) "I" else "N"}"
    }

    fun parse(s: String): Squelch = when {
        s.startsWith("T") -> s.drop(1).toIntOrNull()?.let { Squelch.Ctcss(it) } ?: Squelch.None
        s.startsWith("D") -> s.drop(1).dropLast(1).toIntOrNull()?.let { Squelch.Dcs(it, s.endsWith("I")) } ?: Squelch.None
        else -> Squelch.None
    }
}
