/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui

import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.Geo
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Modulation
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

    /** One line about a channel: mode when not FM, offset, tones, power, then anything unusual. */
    fun channelSummary(ch: Channel): String = listOfNotNull(
        if (ch.mode == Modulation.FM) null else ch.mode.label,
        if (ch.mode.receiveOnly) "Receive only" else duplex(ch),
        tones(ch.txSquelch, ch.rxSquelch),
        if (ch.duplex == Duplex.OFF) null else "${ch.power.label} power",
        if (ch.wide) null else "Narrow",
        if (ch.scanAdd) null else "No scan",
        ch.options["nicfw.groups"]?.takeIf { it.isNotEmpty() }?.let { "Groups ${it.toList().joinToString(" ")}" },
    ).joinToString(" · ")

    fun repeaterSummary(r: Repeater): String = listOfNotNull(
        if (r.service == com.vyntric.offset.domain.Service.GMRS) "GMRS" else null,
        r.offsetHz?.takeIf { it != 0L }?.let { if (it > 0) "+${shift(it)}" else "-${shift(-it)}" } ?: "Simplex",
        tones(r.uplinkTone, r.downlinkTone),
        listOfNotNull(r.city, r.state).joinToString(", ").ifBlank { null },
        if (r.mixedMode) "Mixed mode" else null,
        verification(r),
    ).joinToString(" · ")

    /** Short verification tag for a list row, or null for a repeater listed once by the chosen source. */
    fun verification(r: Repeater): String? {
        val v = r.verification
        return when {
            v.tonesDisagree -> "Sources disagree on tone"
            v.corroborated -> "${v.independentSources} sources"
            v.lastReportMillis != null -> "IRLP node"
            !r.source.isProvider -> "Listed by ${r.source.displayName}"
            else -> null
        }
    }

    /** Credits for every directory behind [repeaters], primary [provider] first. */
    /**
     * Every directory the [repeaters] cite, chosen [providers] first; with no repeaters, the
     * chosen providers.
     */
    fun credits(providers: Collection<RepeaterSource>, repeaters: List<Repeater>): List<RepeaterSource> {
        val cited = repeaters.flatMap { it.verification.sources }.toSet()
        val chosen = RepeaterSource.providers.filter { it in providers && (repeaters.isEmpty() || it in cited) }
        return (chosen + cited).distinct()
    }

    fun distance(km: Double, units: Units): String = when (units) {
        Units.MILES -> String.format(Locale.US, if (km / Geo.KM_PER_MILE < 10) "%.1f mi" else "%.0f mi", km / Geo.KM_PER_MILE)
        Units.KILOMETERS -> String.format(Locale.US, if (km < 10) "%.1f km" else "%.0f km", km)
    }

    fun repeaterCount(n: Int): String = when (n) {
        0 -> "No repeaters found"
        1 -> "1 repeater found"
        else -> String.format(Locale.US, "%,d repeaters found", n)
    }

        fun unitLabel(units: Units) = if (units == Units.MILES) "mi" else "km"

    fun radiusKm(value: Int, units: Units): Double = if (units == Units.MILES) value * Geo.KM_PER_MILE else value.toDouble()

    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun date(millis: Long): String = dateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    fun dateTime(millis: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun bytes(done: Int, total: Int): String = String.format(Locale.US, "%,d of %,d bytes", done, total)
}
