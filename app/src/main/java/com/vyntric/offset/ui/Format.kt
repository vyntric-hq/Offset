/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui

import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.Geo
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Squelch
import com.vyntric.offset.protocol.ToneMode
import com.vyntric.offset.protocol.Tones
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Display formatting shared by every screen. */
object Fmt {
    /** 146940000 -> "146.940", 446006250 -> "446.00625". */
    fun mhz(hz: Long): String {
        val s = String.format(Locale.US, "%.5f", hz / 1_000_000.0).trimEnd('0')
        val decimals = s.substringAfter('.', "").length
        return if (decimals < 3) String.format(Locale.US, "%.3f", hz / 1_000_000.0) else s
    }

    fun shift(hz: Long): String {
        val mhz = hz / 1_000_000.0
        val text = if (hz % 1_000_000L == 0L) String.format(Locale.US, "%.0f", mhz) else String.format(Locale.US, "%.3f", mhz).trimEnd('0').trimEnd('.')
        return "$text MHz"
    }

    fun duplex(ch: Channel): String = when (ch.duplex) {
        Duplex.SIMPLEX -> "Simplex"
        Duplex.PLUS -> "+${shift(ch.offsetHz)}"
        Duplex.MINUS -> "-${shift(ch.offsetHz)}"
        Duplex.SPLIT -> "TX ${mhz(ch.offsetHz)}"
        Duplex.OFF -> "TX off"
    }

    fun squelch(s: Squelch): String = when (s) {
        Squelch.None -> "None"
        is Squelch.Ctcss -> "${Tones.formatCtcss(s.tenths)} Hz"
        is Squelch.Dcs -> "D${Tones.formatDcs(s.code)}${if (s.inverted) "I" else "N"}"
    }

    fun tones(tx: Squelch, rx: Squelch): String? = when (ToneMode.of(tx, rx)) {
        ToneMode.NONE -> null
        ToneMode.TONE -> "Tone ${squelch(tx)}"
        ToneMode.TSQL -> "TSQL ${squelch(tx)}"
        ToneMode.DTCS -> "DCS ${squelch(tx)}"
        ToneMode.CROSS -> "TX ${squelch(tx)}, RX ${squelch(rx)}"
    }

    fun channelSummary(ch: Channel): String = listOfNotNull(
        duplex(ch),
        tones(ch.txSquelch, ch.rxSquelch),
        ch.power.label,
        if (ch.wide) null else "Narrow",
        if (ch.scanAdd) null else "No scan",
    ).joinToString(" · ")

    fun repeaterSummary(r: Repeater): String = listOfNotNull(
        if (r.service == com.vyntric.offset.domain.Service.GMRS) "GMRS" else null,
        r.offsetHz?.takeIf { it != 0L }?.let { if (it > 0) "+${shift(it)}" else "-${shift(-it)}" } ?: "Simplex",
        tones(r.uplinkTone, r.downlinkTone),
        listOfNotNull(r.city, r.state).joinToString(", ").ifBlank { null },
        if (r.mixedMode) "Mixed mode" else null,
    ).joinToString(" · ")

    fun distance(km: Double, units: Units): String = when (units) {
        Units.MILES -> String.format(Locale.US, if (km / Geo.KM_PER_MILE < 10) "%.1f mi" else "%.0f mi", km / Geo.KM_PER_MILE)
        Units.KILOMETERS -> String.format(Locale.US, if (km < 10) "%.1f km" else "%.0f km", km)
    }

    fun unitLabel(units: Units) = if (units == Units.MILES) "mi" else "km"

    fun radiusKm(value: Int, units: Units): Double = if (units == Units.MILES) value * Geo.KM_PER_MILE else value.toDouble()

    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun date(millis: Long): String = dateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    fun dateTime(millis: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun bytes(done: Int, total: Int): String = String.format(Locale.US, "%,d of %,d bytes", done, total)
}
